package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptRunResult;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptStatus;

/**
 * The result of {@link ScriptRunCoordinator#run} - captured console output (spec section 7.6), and any execution error (already reflected in that output too, since the delegate command reports it the normal way - kept separately here only so the caller can decide how to present it, e.g. a status bar message).
 *
 * <p>SPRINT 0110A: also the structured {@link ScriptRunResult} of the run; {@link #hasErrors()} is true for every
 * status other than {@code SUCCESS}.
 */
public final class RunOutcome {

	public enum Status {
		EXECUTED, NO_ACTIVE_CONNECTION
	}

	private final Status status;
	private final String capturedOutput;
	private final BroadSQLException error;
	private final int reportedErrors;
	private final ScriptRunResult runResult;

	private RunOutcome(Status status, String capturedOutput, BroadSQLException error, int reportedErrors, ScriptRunResult runResult) {
		this.reportedErrors = reportedErrors;
		this.status = status;
		this.capturedOutput = capturedOutput;
		this.error = error;
		this.runResult = runResult;
	}

	public static RunOutcome noActiveConnection() {
		return new RunOutcome(Status.NO_ACTIVE_CONNECTION, null, null, 0, null);
	}

	/** The run finished; {@code reportedErrors} error messages were written to its output; {@code runResult} may be {@code null} after an unexpected failure. */
	public static RunOutcome executed(String capturedOutput, BroadSQLException error, int reportedErrors, ScriptRunResult runResult) {
		return new RunOutcome(Status.EXECUTED, capturedOutput, error, Math.max(reportedErrors, error == null ? 0 : 1), runResult);
	}

	public static RunOutcome executed(String capturedOutput, BroadSQLException error) {
		return new RunOutcome(Status.EXECUTED, capturedOutput, error, error == null ? 0 : 1, null);
	}

	/** Whether anything went wrong: the run did not end {@code SUCCESS}, failed unexpectedly, or reported an error. */
	public boolean hasErrors() {
		if (runResult != null) {
			return !runResult.isSuccess();
		}
		return reportedErrors > 0;
	}

	/** The structured result of the run; {@code null} when it did not run or failed unexpectedly. */
	public ScriptRunResult runResult() {
		return runResult;
	}

	/** The run's status; {@code null} when it did not run or failed unexpectedly. */
	public ScriptStatus scriptStatus() {
		return runResult == null ? null : runResult.getStatus();
	}

	public Status status() {
		return status;
	}

	public String capturedOutput() {
		return capturedOutput;
	}

	public BroadSQLException error() {
		return error;
	}
}
