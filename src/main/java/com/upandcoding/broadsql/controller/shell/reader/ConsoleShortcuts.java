package com.upandcoding.broadsql.controller.shell.reader;

import java.util.List;

/**
 * The keyboard shortcuts of the interactive console, as users should know them: the one list behind
 * {@code HELP SHORTCUTS} and the one the website's "Keyboard shortcuts" table is checked against
 * ({@code TestConsoleShortcuts}). Data only: how they are displayed is up to the caller.
 *
 * <p>Every entry describes verified behavior: the key bindings installed by
 * {@link JLineConsoleLineReader} (its keyboard contract), JLine's standard emacs bindings, and Ctrl+C / Ctrl+D
 * as handled by the command interpreter. {@link #TERMINAL_DEPENDENT} holds the keys that work only when
 * the terminal sends a distinct code for them.
 */
public final class ConsoleShortcuts {

	/** One shortcut: the key or keys as a user types them, and what they do. */
	public record Shortcut(String keys, String action) {
	}

	/** Shortcuts that work in every supported terminal, in the order they are presented. */
	public static final List<Shortcut> STANDARD = List.of(
			new Shortcut("Enter", "Submits the line; a command runs once its closing ; is read"),
			new Shortcut("Tab", "Completes the word; with several candidates, opens a menu (Tab next, Shift+Tab previous); at the start of a line, indents"),
			new Shortcut("Esc", "Cancels everything being typed, including earlier lines of a statement not yet ended with ;"),
			new Shortcut("Up / Down", "Previous / next command in history"),
			new Shortcut("Ctrl+R", "Searches history backward (Enter runs the match, End keeps it for editing, Esc cancels)"),
			new Shortcut("Left / Right", "Moves the cursor one character"),
			new Shortcut("Home / End, Ctrl+A / Ctrl+E", "Moves to the beginning / end of the line"),
			new Shortcut("Backspace / Delete", "Deletes the character before / at the cursor"),
			new Shortcut("Ctrl+U", "Deletes from the cursor back to the beginning of the line"),
			new Shortcut("Ctrl+K", "Deletes from the cursor to the end of the line"),
			new Shortcut("Ctrl+W, Alt+Backspace", "Deletes the word before the cursor"),
			new Shortcut("Ctrl+L", "Clears the screen and keeps what is typed"),
			new Shortcut("Ctrl+C", "Stops the running command and keeps the connection; while typing, abandons the current line"),
			new Shortcut("Ctrl+D", "Deletes the character at the cursor; on an empty line, exits BroadSQL"));

	/** Shortcuts that depend on the codes the terminal sends. */
	public static final List<Shortcut> TERMINAL_DEPENDENT = List.of(
			new Shortcut("Ctrl+Left / Ctrl+Right", "Moves to the previous / next word"),
			new Shortcut("Ctrl+Delete", "Deletes the next word (one character in the Windows console)"),
			new Shortcut("Alt+B / Alt+F", "Moves back / forward one word"));

	private ConsoleShortcuts() {
	}
}
