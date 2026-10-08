package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.Component;

import javax.swing.JOptionPane;

/**
 * The conflict prompt when a managed asset changed outside BroadSQL while its tab has unsaved edits
 * (SPRINT 0917-01, spec section 29) - modeled directly on {@code UnsavedChangesDialog}'s own
 * "ask only the question, let the caller decide what each choice means" shape. A clean tab never
 * reaches this - it is reloaded silently (see {@code ScriptLibraryFrame#checkExternalChangeForTab}).
 *
 * <p>{@code Compare} (the fourth option the spec lists, added once {@code CompareDialog} existed in
 * Phase E) shows the difference and re-asks - it is never a terminal choice on its own.
 */
public final class ExternalChangeDialog {

	private ExternalChangeDialog() {
	}

	public enum Decision {
		RELOAD_EXTERNAL, KEEP_EDITOR, COMPARE, CANCEL
	}

	public static Decision ask(Component parent, String assetLabel) {
		Object[] options = { "Compare", "Reload External Version", "Keep Editor Version", "Cancel" };
		int choice = JOptionPane.showOptionDialog(parent,
				"'" + assetLabel + "' was changed outside BroadSQL, and this tab has unsaved changes.",
				"External Change Detected",
				JOptionPane.YES_NO_CANCEL_OPTION,
				JOptionPane.WARNING_MESSAGE,
				null, options, options[3]);
		return switch (choice) {
			case 0 -> Decision.COMPARE;
			case 1 -> Decision.RELOAD_EXTERNAL;
			case 2 -> Decision.KEEP_EDITOR;
			default -> Decision.CANCEL;
		};
	}
}
