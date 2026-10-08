package com.upandcoding.broadsql.controller.shell.swing.api;

import java.awt.Component;

import javax.swing.JOptionPane;

/**
 * The shared Save/Discard/Cancel prompt for a real, unsaved edit on the {@code CONFIG API} screen -
 * API Quality and UX Consolidation sprint, Phase 2. No such shared dialog existed before this: both
 * {@code JSettingsFrame} and {@code JApiSettingsFrame} previously hand-rolled their own
 * {@code JOptionPane.showConfirmDialog} call (and, before this phase, {@code JApiSettingsFrame}'s own
 * call fired <i>unconditionally</i> - see {@code docs/TECHNICAL_CHANGE.md}, 2026-09-14, "the false-dirty
 * close bug"). This class only asks the question and reports the choice - callers decide what SAVE/
 * DISCARD/CANCEL actually do (persist the current object, proceed without persisting, or abort the
 * navigation that triggered the prompt) since that varies by call site (window close, API/environment/
 * endpoint selection change, {@code File > Exit}, creating another object).
 */
public final class UnsavedChangesDialog {

	private UnsavedChangesDialog() {
	}

	public enum Decision {
		SAVE, DISCARD, CANCEL
	}

	/**
	 * @param objectLabel what to call the unsaved object in the prompt text (e.g. {@code "API"},
	 *                    {@code "endpoint"}, {@code "environment"})
	 */
	public static Decision ask(Component parent, String objectLabel) {
		Object[] options = { "Save", "Discard", "Cancel" };
		int choice = JOptionPane.showOptionDialog(parent,
				"This " + objectLabel + " has unsaved changes.",
				"Unsaved changes",
				JOptionPane.YES_NO_CANCEL_OPTION,
				JOptionPane.WARNING_MESSAGE,
				null, options, options[0]);
		return switch (choice) {
			case 0 -> Decision.SAVE;
			case 1 -> Decision.DISCARD;
			default -> Decision.CANCEL;
		};
	}
}
