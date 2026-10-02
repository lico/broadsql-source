package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.text.JTextComponent;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader;

/**
 * SPRINT 3009A (#186): the right-hand Metadata tab (one column, label above field, a multi-line Description that is
 * still one header value, read-only Path and Last modified) and the icon-only toolbar (order, groups, tooltips,
 * accessible names, icons, shortcuts). Both are plain components, checked headlessly; how they look on screen is a
 * manual check.
 */
class TestEditorInspectorAndToolBar {

	// ---- Metadata tab ----

	@Test
	void everyLabelSitsAboveItsFieldInOneColumn() {
		MetadataPanel panel = new MetadataPanel();
		GridBagLayout layout = (GridBagLayout) panel.getLayout();
		List<String> order = new ArrayList<>();
		for (Component c : panel.getComponents()) {
			GridBagConstraints gc = layout.getConstraints(c);
			Assertions.assertEquals(0, gc.gridx, "one column: " + c);
			if (c instanceof JLabel label) {
				order.add(label.getText());
				Component field = label.getLabelFor();
				Component row = rowAt(panel, layout, gc.gridy + 1);
				Assertions.assertTrue(row == field || isAncestor(row, field), label.getText() + " is directly above its field");
			}
		}
		Assertions.assertEquals(List.of("Description", "Instance (Database Group)", "Environment", "Tags", "Status", "Parameters", "Path", "Last modified"), order);
	}

	private static Component rowAt(java.awt.Container panel, GridBagLayout layout, int gridy) {
		for (Component c : panel.getComponents()) {
			if (layout.getConstraints(c).gridy == gridy) {
				return c;
			}
		}
		return null;
	}

	private static boolean isAncestor(Component ancestor, Component c) {
		return ancestor instanceof java.awt.Container container && container.isAncestorOf(c);
	}

	@Test
	void descriptionIsASmallMultiLineAreaThatStaysOneHeaderValue() {
		MetadataPanel panel = new MetadataPanel();
		List<MetadataPanel.Change> commits = new ArrayList<>();
		panel.setCommitListener(commits::add);
		panel.load(ScriptMetadataHeader.parse("-- @description: old\nselect 1;"));

		JTextArea description = (JTextArea) panel.field("description");
		Assertions.assertEquals(4, description.getRows());
		Assertions.assertTrue(description.getLineWrap());

		description.setText("Lorem ipsum\ndolor sit amet,\r\n   consectetur");
		Assertions.assertEquals("Lorem ipsum dolor sit amet, consectetur", description.getText(), "typed or pasted line breaks become spaces");

		description.getActionMap().get("commit").actionPerformed(null); // what Enter does
		Assertions.assertEquals(1, commits.size());
		Assertions.assertEquals("Lorem ipsum dolor sit amet, consectetur", ScriptMetadataHeader.parse(commits.get(0).applyTo("-- @description: old\nselect 1;")).metadata().getDescription());
	}

	@Test
	void pathAndLastModifiedAreReadOnlyAndShowTheFile() {
		MetadataPanel panel = new MetadataPanel();
		long millis = java.time.LocalDateTime.of(2026, 9, 30, 14, 5).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();

		panel.showFile("reports/QR13.sql", "C:\\lib\\reports\\QR13.sql", millis);

		Assertions.assertFalse(((JTextComponent) panel.field("path")).isEditable());
		Assertions.assertFalse(((JTextComponent) panel.field("modified")).isEditable());
		Assertions.assertEquals("reports/QR13.sql", panel.fieldText("path"), "the library path, not the machine path");
		Assertions.assertEquals("C:\\lib\\reports\\QR13.sql", panel.field("path").getToolTipText(), "the full path is one hover away");
		Assertions.assertEquals(HistoryDialog.TIMESTAMP_FORMAT.format(java.time.Instant.ofEpochMilli(millis)), panel.fieldText("modified"),
				"the Editor's date format, as in History");

		panel.showFile(null, null, null);
		Assertions.assertEquals("", panel.fieldText("path"));
		Assertions.assertEquals("", panel.fieldText("modified"));
	}

	@Test
	void theCopyButtonCopiesTheLibraryPathThroughTheOwner() {
		MetadataPanel panel = new MetadataPanel();
		List<ScriptPathText.Kind> copies = new ArrayList<>();
		panel.setPathCopyHandler(copies::add);
		JButton copy = findButton(panel, "Copy Path");

		copy.doClick();
		Assertions.assertTrue(copies.isEmpty(), "nothing to copy while no Script is shown");
		panel.showFile("reports/QR13.sql", "C:\\x", 1L);
		copy.doClick();
		Assertions.assertEquals(List.of(ScriptPathText.Kind.LIBRARY_PATH), copies);
		Assertions.assertEquals(3, panel.field("path").getComponentPopupMenu().getComponentCount(), "library path, full path, CLI command");
	}

	private static JButton findButton(java.awt.Container container, String accessibleName) {
		for (Component c : container.getComponents()) {
			if (c instanceof JButton b && accessibleName.equals(b.getAccessibleContext().getAccessibleName())) {
				return b;
			}
			if (c instanceof java.awt.Container inner) {
				JButton found = findButton(inner, accessibleName);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	// ---- Toolbar ----

	private static JToolBar toolBar(List<EditorActions.Command> invoked) {
		Map<EditorActions.Command, Runnable> handlers = new EnumMap<>(EditorActions.Command.class);
		for (EditorActions.Command command : EditorActions.Command.values()) {
			handlers.put(command, () -> invoked.add(command));
		}
		return EditorActions.toolBar(EditorActions.actions(handlers));
	}

	@Test
	void theToolbarIsIconOnlyInTheSprintOrderWithSeparatorsBetweenGroups() {
		JToolBar bar = toolBar(new ArrayList<>());
		List<String> layout = new ArrayList<>();
		for (Component c : bar.getComponents()) {
			if (c instanceof JButton b) {
				layout.add(b.getAccessibleContext().getAccessibleName());
				Assertions.assertTrue(b.getHideActionText(), "icon only");
				Assertions.assertEquals("", b.getText() == null ? "" : b.getText());
				Assertions.assertEquals(EditorIcons.SIZE, b.getIcon().getIconWidth(), "16 px icons");
				Assertions.assertEquals("toolBarButton", b.getClientProperty("JButton.buttonType"), "flat until hovered");
			} else if (c instanceof JToolBar.Separator) {
				layout.add("|");
			}
		}
		Assertions.assertEquals(List.of("New", "New Folder", "Save", "|", "Format", "Run", "History", "|", "Rename", "Duplicate", "Delete"), layout,
				"no Validate, no Refresh, no dangling separator");
		Assertions.assertFalse(bar.isFloatable());
	}

	@Test
	void everyButtonHasATooltipItsCommandsIconAndRunsItsCommand() {
		List<EditorActions.Command> invoked = new ArrayList<>();
		JToolBar bar = toolBar(invoked);
		List<EditorActions.Command> expected = new ArrayList<>();
		for (Component c : bar.getComponents()) {
			if (c instanceof JButton b) {
				EditorActions.Command command = EditorActions.Command.valueOf(b.getAccessibleContext().getAccessibleName().toUpperCase().replace(' ', '_'));
				Assertions.assertEquals(command.icon, ((EditorIcons.VectorIcon) b.getIcon()).kind());
				Assertions.assertTrue(b.getToolTipText().startsWith(command.label), b.getToolTipText());
				b.doClick();
				expected.add(command);
			}
		}
		Assertions.assertEquals(expected, invoked, "each button runs its own command's handler");
	}

	@Test
	void theKeyboardShortcutsAreKeptAndShownInTheTooltips() {
		Map<EditorActions.Command, Runnable> handlers = new EnumMap<>(EditorActions.Command.class);
		for (EditorActions.Command command : EditorActions.Command.values()) {
			handlers.put(command, () -> {
			});
		}
		Map<EditorActions.Command, Action> actions = EditorActions.actions(handlers);
		Assertions.assertEquals(KeyStroke.getKeyStroke("ctrl N"), actions.get(EditorActions.Command.NEW).getValue(Action.ACCELERATOR_KEY));
		Assertions.assertEquals(KeyStroke.getKeyStroke("ctrl S"), actions.get(EditorActions.Command.SAVE).getValue(Action.ACCELERATOR_KEY));
		Assertions.assertEquals(KeyStroke.getKeyStroke("F5"), actions.get(EditorActions.Command.RUN).getValue(Action.ACCELERATOR_KEY));
		Assertions.assertEquals("Run (F5)", actions.get(EditorActions.Command.RUN).getValue(Action.SHORT_DESCRIPTION));
		Assertions.assertTrue(((String) actions.get(EditorActions.Command.SAVE).getValue(Action.SHORT_DESCRIPTION)).matches("Save \\(Ctrl\\+S\\)|Save \\(.+\\+S\\)"));
		Assertions.assertNull(actions.get(EditorActions.Command.SAVE).getValue(Action.SMALL_ICON), "menus stay text only");
	}

	@Test
	void aMenuItemAndAToolbarButtonShareOneActionAndItsEnabledState() {
		Map<EditorActions.Command, Runnable> handlers = new EnumMap<>(EditorActions.Command.class);
		for (EditorActions.Command command : EditorActions.Command.values()) {
			handlers.put(command, () -> {
			});
		}
		Map<EditorActions.Command, Action> actions = EditorActions.actions(handlers);
		JButton button = EditorActions.toolBarButton(actions.get(EditorActions.Command.RUN));
		javax.swing.JMenuItem item = EditorActions.menuItem(actions.get(EditorActions.Command.RUN), "Run");

		actions.get(EditorActions.Command.RUN).setEnabled(false);

		Assertions.assertFalse(button.isEnabled());
		Assertions.assertFalse(item.isEnabled());
		Assertions.assertNull(item.getIcon(), "the menu item has no icon");
	}

	@Test
	void onlyRunAndDeleteAreColored() {
		for (EditorIcons.Kind kind : EditorIcons.Kind.values()) {
			java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D g = image.createGraphics();
			EditorIcons.get(kind).paintIcon(null, g, 0, 0);
			g.dispose();
			boolean colored = false;
			for (int x = 0; x < 16; x++) {
				for (int y = 0; y < 16; y++) {
					int argb = image.getRGB(x, y);
					if ((argb >>> 24) > 200) {
						int r = (argb >> 16) & 0xff, gr = (argb >> 8) & 0xff, b = argb & 0xff;
						colored |= Math.max(r, Math.max(gr, b)) - Math.min(r, Math.min(gr, b)) > 60;
					}
				}
			}
			boolean expected = kind == EditorIcons.Kind.RUN || kind == EditorIcons.Kind.DELETE || kind == EditorIcons.Kind.FOLDER_CLOSED
					|| kind == EditorIcons.Kind.FOLDER_OPEN;
			Assertions.assertEquals(expected, colored, kind + (expected ? " is colored" : " is neutral"));
		}
	}
}
