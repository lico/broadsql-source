package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/**
 * The editor tabs' right-click menu: Close, Close Other Tabs, Close Tabs to the Right, Close Tabs to the Left,
 * Close All Tabs, then the Script's own path actions. Every entry acts on the tab under the pointer, which is
 * not necessarily the active tab, and every close goes through {@link ScriptTabCloser}, so no entry can discard
 * unsaved work without the usual Save/Discard/Cancel prompt.
 */
final class ScriptTabContextMenu {

	/** The Script actions offered below the close entries, run for the tab under the pointer. */
	interface ScriptActions {
		void copyPath(ScriptEditorTab tab, ScriptPathText.Kind kind);

		void sendToCli(ScriptEditorTab tab);
	}

	private final ScriptEditorTabbedPane pane;
	private final ScriptTabCloser closer;
	private final ScriptActions scriptActions;

	private ScriptTabContextMenu(ScriptEditorTabbedPane pane, ScriptTabCloser closer, ScriptActions scriptActions) {
		this.pane = pane;
		this.closer = closer;
		this.scriptActions = scriptActions;
	}

	/** Installs the menu on {@code pane}: it opens on the platform's popup trigger over a tab, and nowhere else. */
	static void install(ScriptEditorTabbedPane pane, ScriptTabCloser closer, ScriptActions scriptActions) {
		ScriptTabContextMenu menu = new ScriptTabContextMenu(pane, closer, scriptActions);
		pane.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				maybeShow(e);
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				maybeShow(e);
			}

			private void maybeShow(MouseEvent e) {
				if (!e.isPopupTrigger()) {
					return;
				}
				int index = pane.indexAtLocation(e.getX(), e.getY());
				if (index >= 0 && pane.getComponentAt(index) instanceof ScriptEditorTab tab) {
					menu.build(tab).show(pane, e.getX(), e.getY());
				}
			}
		});
	}

	/** The menu for {@code tab}; entries with nothing to act on are disabled (for example Close Tabs to the Left on the first tab). */
	JPopupMenu build(ScriptEditorTab tab) {
		JPopupMenu popup = new JPopupMenu();
		popup.add(item("Close", true, () -> closer.close(tab)));
		popup.add(item("Close Other Tabs", !closer.tabsRelativeTo(tab, ScriptTabCloser.Scope.OTHERS).isEmpty(),
				() -> closer.closeRelativeTo(tab, ScriptTabCloser.Scope.OTHERS)));
		popup.add(item("Close Tabs to the Right", !closer.tabsRelativeTo(tab, ScriptTabCloser.Scope.TO_THE_RIGHT).isEmpty(),
				() -> closer.closeRelativeTo(tab, ScriptTabCloser.Scope.TO_THE_RIGHT)));
		popup.add(item("Close Tabs to the Left", !closer.tabsRelativeTo(tab, ScriptTabCloser.Scope.TO_THE_LEFT).isEmpty(),
				() -> closer.closeRelativeTo(tab, ScriptTabCloser.Scope.TO_THE_LEFT)));
		popup.add(item("Close All Tabs", true, closer::closeAll));
		popup.addSeparator();
		for (ScriptPathText.Kind kind : ScriptPathText.Kind.values()) {
			popup.add(item(kind.label, true, () -> scriptActions.copyPath(tab, kind)));
		}
		popup.add(item("Send to CLI", true, () -> scriptActions.sendToCli(tab)));
		return popup;
	}

	private static JMenuItem item(String label, boolean enabled, Runnable action) {
		JMenuItem item = new JMenuItem(label);
		item.setEnabled(enabled);
		item.addActionListener(e -> action.run());
		return item;
	}

	/** Test hook: the entry labels of {@link #build}, in order, {@code null} for a separator. */
	static java.util.List<String> labels(JPopupMenu popup) {
		java.util.List<String> labels = new java.util.ArrayList<>();
		for (java.awt.Component c : popup.getComponents()) {
			labels.add(c instanceof JMenuItem item ? item.getText() : null);
		}
		return labels;
	}

	/** Test hook: builds the menu for {@code tab} without a mouse event. */
	static JPopupMenu menuFor(ScriptEditorTabbedPane pane, ScriptTabCloser closer, ScriptEditorTab tab, Consumer<String> sink) {
		return new ScriptTabContextMenu(pane, closer, new ScriptActions() {
			@Override
			public void copyPath(ScriptEditorTab t, ScriptPathText.Kind kind) {
				sink.accept("copy:" + kind + ":" + t.asset().relativePath());
			}

			@Override
			public void sendToCli(ScriptEditorTab t) {
				sink.accept("send:" + t.asset().relativePath());
			}
		}).build(tab);
	}
}
