package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.UIManager;

/**
 * The BroadSQL Editor's commands as Swing {@link Action}s, so the toolbar, the menus, the context menus and the
 * keyboard shortcuts all invoke the same object: one handler, one enabled state (Run is disabled everywhere at
 * once when there is no connection). Also builds the icon-only toolbar from those actions.
 *
 * <p>An action carries its icon under {@link Action#LARGE_ICON_KEY} only: buttons show it, while menu items
 * (which read {@link Action#SMALL_ICON}) stay text-only like every other BroadSQL window's menus.
 */
final class EditorActions {

	private EditorActions() {
	}

	/** The toolbar commands: name (tooltip, accessible name), icon and keyboard shortcut, in toolbar order. */
	enum Command {
		NEW("New", EditorIcons.Kind.NEW_SCRIPT, KeyStroke.getKeyStroke(KeyEvent.VK_N, java.awt.event.InputEvent.CTRL_DOWN_MASK)),
		NEW_FOLDER("New Folder", EditorIcons.Kind.NEW_FOLDER, null),
		SAVE("Save", EditorIcons.Kind.SAVE, KeyStroke.getKeyStroke(KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK)),
		FORMAT("Format", EditorIcons.Kind.FORMAT, null),
		RUN("Run", EditorIcons.Kind.RUN, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0)),
		HISTORY("History", EditorIcons.Kind.HISTORY, null),
		RENAME("Rename", EditorIcons.Kind.RENAME, null),
		DUPLICATE("Duplicate", EditorIcons.Kind.DUPLICATE, null),
		DELETE("Delete", EditorIcons.Kind.DELETE, null);

		final String label;
		final EditorIcons.Kind icon;
		final KeyStroke accelerator;

		Command(String label, EditorIcons.Kind icon, KeyStroke accelerator) {
			this.label = label;
			this.icon = icon;
			this.accelerator = accelerator;
		}
	}

	/**
	 * The toolbar's groups, separated on screen: files | the Script's text, execution and history | the Script
	 * itself. (SPRINT 3009A correction pass: Validate was removed from the Editor, and Refresh, only needed after
	 * changes made outside BroadSQL, lives in the Scripts pane's context menu.)
	 */
	static final List<List<Command>> TOOLBAR_GROUPS = List.of(
			List.of(Command.NEW, Command.NEW_FOLDER, Command.SAVE),
			List.of(Command.FORMAT, Command.RUN, Command.HISTORY),
			List.of(Command.RENAME, Command.DUPLICATE, Command.DELETE));

	static KeyStroke ctrl(int keyCode) {
		return KeyStroke.getKeyStroke(keyCode, java.awt.event.InputEvent.CTRL_DOWN_MASK);
	}

	/** One action per {@link Command}, each running its handler; every command must have one. */
	static java.util.Map<Command, Action> actions(java.util.Map<Command, Runnable> handlers) {
		java.util.Map<Command, Action> actions = new java.util.EnumMap<>(Command.class);
		for (Command command : Command.values()) {
			Runnable handler = java.util.Objects.requireNonNull(handlers.get(command), command.name());
			actions.put(command, action(command.label, command.icon, command.accelerator, handler));
		}
		return actions;
	}

	/** The toolbar of {@link #TOOLBAR_GROUPS} over {@code actions}. */
	static JToolBar toolBar(java.util.Map<Command, Action> actions) {
		return toolBar(TOOLBAR_GROUPS.stream().map(group -> group.stream().map(actions::get).toList()).toList());
	}

	/**
	 * @param name        the command's name: toolbar tooltip and accessible name, and menu text unless a menu
	 *                    gives its own label with {@link #menuItem}
	 * @param icon        the toolbar icon, or {@code null} for a menu-only action
	 * @param accelerator the keyboard shortcut, or {@code null}
	 */
	static Action action(String name, EditorIcons.Kind icon, KeyStroke accelerator, Runnable handler) {
		Action action = new AbstractAction(name) {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				handler.run();
			}
		};
		if (icon != null) {
			action.putValue(Action.LARGE_ICON_KEY, EditorIcons.get(icon));
		}
		if (accelerator != null) {
			action.putValue(Action.ACCELERATOR_KEY, accelerator);
		}
		action.putValue(Action.SHORT_DESCRIPTION, tooltip(name, accelerator));
		return action;
	}

	/**
	 * Run's enabled state (one {@link Action}, so the toolbar button and the menu item always agree): a Script is
	 * active, BroadSQL has an open connection to run it on, and no run is in progress. Whether the Script has unsaved
	 * changes plays no part: Run on unsaved changes offers Save and Run.
	 */
	static boolean runEnabled(ScriptEditorTab activeTab, boolean connected, boolean running) {
		return activeTab != null && connected && !running;
	}

	/** {@code Save (Ctrl+S)}: the name, plus the shortcut when there is one. */
	static String tooltip(String name, KeyStroke accelerator) {
		if (accelerator == null) {
			return name;
		}
		String modifiers = KeyEvent.getModifiersExText(accelerator.getModifiers());
		String key = KeyEvent.getKeyText(accelerator.getKeyCode());
		return name + " (" + (modifiers.isEmpty() ? key : modifiers + "+" + key) + ")";
	}

	/** A menu item for {@code action}, with its own menu wording (for example {@code New...} for the action {@code New}). */
	static JMenuItem menuItem(Action action, String label) {
		JMenuItem item = new JMenuItem(action);
		item.setText(label);
		item.setToolTipText(null);
		return item;
	}

	/**
	 * The icon-only toolbar: one flat button per action (FlatLaf's toolbar button style, borderless until hovered),
	 * the groups separated by a separator. Each button's tooltip and accessible name are the action's name.
	 */
	static JToolBar toolBar(List<List<Action>> groups) {
		JToolBar toolBar = new JToolBar();
		toolBar.setFloatable(false);
		toolBar.setRollover(true);
		toolBar.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, separatorColor()),
				BorderFactory.createEmptyBorder(2, 4, 2, 4)));
		for (int i = 0; i < groups.size(); i++) {
			if (i > 0) {
				toolBar.addSeparator();
			}
			for (Action action : groups.get(i)) {
				toolBar.add(toolBarButton(action));
			}
		}
		return toolBar;
	}

	static JButton toolBarButton(Action action) {
		JButton button = new JButton(action);
		button.setHideActionText(true);
		button.setFocusable(false);
		button.putClientProperty("JButton.buttonType", "toolBarButton");
		button.getAccessibleContext().setAccessibleName((String) action.getValue(Action.NAME));
		return button;
	}

	static java.awt.Color separatorColor() {
		java.awt.Color c = UIManager.getColor("Separator.foreground");
		return c != null ? c : java.awt.Color.LIGHT_GRAY;
	}
}
