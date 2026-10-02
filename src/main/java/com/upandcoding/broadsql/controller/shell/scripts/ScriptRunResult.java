package com.upandcoding.broadsql.controller.shell.scripts;

/**
 * SPRINT 0110A: the structured result of one Script run, returned by {@link ScriptExecutor} to its caller (a
 * nested call's caller, the {@code @}/{@code LIB RUN} command, the BroadSQL Editor, {@code DUMP}/{@code PULL})
 * instead of being reconstructed from printed text (spec sections 14.2 to 14.6).
 *
 * <p>Counts include every nesting level: {@link #getExecuted()} is the number of statements that were run,
 * {@link #getFailed()} the number that failed (a failed calling statement counts once in its caller, plus the
 * called Script's own failures).
 */
public final class ScriptRunResult {

	private final String scriptReference;
	private final ScriptStatus status;
	private final int executed;
	private final int failed;
	private final String runId;
	private final int stopStatement;
	private final int stopLine;
	private final boolean topLevel;

	public ScriptRunResult(String scriptReference, ScriptStatus status, int executed, int failed, String runId, int stopStatement, int stopLine,
			boolean topLevel) {
		this.scriptReference = scriptReference;
		this.status = status;
		this.executed = executed;
		this.failed = failed;
		this.runId = runId;
		this.stopStatement = stopStatement;
		this.stopLine = stopLine;
		this.topLevel = topLevel;
	}

	/** The script reference as the caller wrote it. */
	public String getScriptReference() {
		return scriptReference;
	}

	public ScriptStatus getStatus() {
		return status;
	}

	public boolean isSuccess() {
		return status == ScriptStatus.SUCCESS;
	}

	public int getExecuted() {
		return executed;
	}

	public int getFailed() {
		return failed;
	}

	/** The Run ID of the top-level run this run belongs to. */
	public String getRunId() {
		return runId;
	}

	/** 1-based number of the statement at which {@code ON ERROR STOP} ended the run; 0 otherwise. */
	public int getStopStatement() {
		return stopStatement;
	}

	/** 1-based starting line of that statement; 0 otherwise. */
	public int getStopLine() {
		return stopLine;
	}

	/** Whether this was a top-level run (not started by a statement of a running Script). */
	public boolean isTopLevel() {
		return topLevel;
	}

	/**
	 * Whether the statement that called this run failed (spec 12.2 item 6, 14.3, 18.5): for a nested run, only
	 * when it ended {@code FAILED}; for a top-level run at the prompt, whenever it did not end {@code SUCCESS}.
	 */
	public boolean failsCallingStatement() {
		return topLevel ? status != ScriptStatus.SUCCESS : status == ScriptStatus.FAILED;
	}

	/** The final status line of a top-level run (spec 14.4). */
	public String statusLine() {
		String line = scriptReference + ": " + status + " (" + executed + " statement" + (executed == 1 ? "" : "s") + ", " + failed + " failed) [run "
				+ runId + "]";
		if (stopStatement > 0) {
			line += ", stopped at statement " + stopStatement + " (line " + stopLine + ")";
		}
		return line;
	}

	@Override
	public String toString() {
		return statusLine();
	}
}
