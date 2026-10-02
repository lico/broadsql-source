package com.upandcoding.broadsql.controller.shell.scriptlibrary;

/**
 * The result of {@link ScriptLibraryService#save}, per the Save/history partial-failure semantics in
 * {@code docs/plans/SPRINT_0917-01_IMPLEMENTATION_PLAN.md}: the working-file write and the history
 * write are two separate durable operations a filesystem vault cannot make transactional. A failed
 * working-file write is a thrown {@link com.upandcoding.broadsql.controller.errors.BroadSQLException} (the
 * caller's tab stays dirty); this class only represents the two ways a <b>successful</b> working-file
 * write can conclude.
 */
public final class SaveOutcome {

	public enum Status {
		/** Both the working file and the history revision were written successfully. */
		SAVED,
		/** The working file was saved (the tab must be cleared as saved relative to disk) but recording the history revision failed - never claim the file itself failed to save. */
		SAVED_HISTORY_FAILED
	}

	private final Status status;
	private final boolean revisionCreated;
	private final String historyFailureMessage;

	private SaveOutcome(Status status, boolean revisionCreated, String historyFailureMessage) {
		this.status = status;
		this.revisionCreated = revisionCreated;
		this.historyFailureMessage = historyFailureMessage;
	}

	public static SaveOutcome saved(boolean revisionCreated) {
		return new SaveOutcome(Status.SAVED, revisionCreated, null);
	}

	public static SaveOutcome savedHistoryFailed(String historyFailureMessage) {
		return new SaveOutcome(Status.SAVED_HISTORY_FAILED, false, historyFailureMessage);
	}

	public Status status() {
		return status;
	}

	/** Whether the Save actually changed the logical asset state (a new revision was created) - meaningless when {@link #status()} is {@link Status#SAVED_HISTORY_FAILED}. */
	public boolean revisionCreated() {
		return revisionCreated;
	}

	/** Non-null only for {@link Status#SAVED_HISTORY_FAILED} - shown to the user as a distinct warning, per the plan: "File saved, but revision history could not be updated." */
	public String historyFailureMessage() {
		return historyFailureMessage;
	}
}
