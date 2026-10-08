package com.upandcoding.broadsql.controller.shell.scripts;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * SPRINT 1909S: the execution context of running Scripts, replacing the old bare {@code scriptDepth}
 * counter in {@code CommandInterpreter}. One frame per Script currently executing, innermost last:
 * its canonical (real) path, its parent directory (the base for an explicit {@code @./x} reference made
 * from inside it) and its origin. {@link #push} refuses a Script that is already active
 * (A calls B calls A: immediate "recursive" error naming the chain) and a chain deeper than
 * {@value #MAX_DEPTH}. Callers must {@link #pop} in a {@code finally} block.
 *
 * <p>SPRINT 0110A: each frame also holds the Script's execution controls ({@link ErrorPolicy},
 * {@link OutputMode}, inherited from the caller at push and discarded at pop, so a change never outlives the
 * Script that made it), the Run ID of its top-level run, and the state of the statement it is executing
 * ({@link StatementState}), through which a statement's outcome and the results of the runs it called reach
 * the executor. The prompt has its own {@link StatementState} for typed lines.
 *
 * <p>Not thread-safe by design: the interpreter runs a script's statements strictly one after another
 * (each on its own worker thread but joined before the next starts). {@link #isRunActive()} alone is read by
 * the CTRL+C handler's thread.
 */
public final class ScriptContextStack {

	/** Defensive maximum nesting (frames, including the outermost script). */
	public static final int MAX_DEPTH = 32;

	/** How a Script execution was started. */
	public enum Origin {
		/** typed at the prompt (or run by the editor) */
		INTERACTIVE,
		/** invoked by a statement of another Script */
		SCRIPT
	}

	/**
	 * SPRINT 0110A: the statement currently executing at one level (a frame, or the prompt): what a command
	 * states explicitly about its own outcome, and the results of the Script runs it called.
	 */
	public static final class StatementState {
		private Boolean explicitFailed;
		private final List<ScriptRunResult> calledRuns = new ArrayList<>();

		/** A command states the outcome of its statement (a script call reports its called run, spec 14.3). */
		public void setFailed(boolean failed) {
			this.explicitFailed = failed;
		}

		/** {@code null} when the command stated nothing. */
		public Boolean getExplicitFailed() {
			return explicitFailed;
		}

		public void recordCalledRun(ScriptRunResult result) {
			calledRuns.add(result);
		}

		public List<ScriptRunResult> getCalledRuns() {
			return calledRuns;
		}
	}

	/** One executing Script. */
	public static final class Frame {
		private final Path realPath;
		private final Origin origin;
		private final int depth;
		private final String runId;
		private final String reference;
		private ErrorPolicy errorPolicy;
		private OutputMode outputMode;
		private StatementState statement = new StatementState();

		Frame(Path realPath, Origin origin, int depth, String runId, String reference, ErrorPolicy errorPolicy, OutputMode outputMode) {
			this.realPath = realPath;
			this.origin = origin;
			this.depth = depth;
			this.runId = runId;
			this.reference = reference;
			this.errorPolicy = errorPolicy;
			this.outputMode = outputMode;
		}

		/** The canonical path (symlinks resolved) of the running Script. */
		public Path getRealPath() {
			return realPath;
		}

		/** The directory an explicit {@code @./x} reference made from inside this Script is relative to. */
		public Path getParentDir() {
			return realPath.getParent();
		}

		public Origin getOrigin() {
			return origin;
		}

		/** 1 for an outermost Script. */
		public int getDepth() {
			return depth;
		}

		public String getRunId() {
			return runId;
		}

		/** The script reference as its caller wrote it. */
		public String getReference() {
			return reference;
		}

		public ErrorPolicy getErrorPolicy() {
			return errorPolicy;
		}

		public void setErrorPolicy(ErrorPolicy errorPolicy) {
			this.errorPolicy = errorPolicy;
		}

		public OutputMode getOutputMode() {
			return outputMode;
		}

		public void setOutputMode(OutputMode outputMode) {
			this.outputMode = outputMode;
		}

		public StatementState getStatement() {
			return statement;
		}

		private String lastQuery;

		/**
		 * GitHub #196: the most recent SQL query ({@code SELECT}, {@code INSERT}, {@code UPDATE}, {@code DELETE}, the
		 * statements the prompt remembers for {@code /}) executed by this Script itself, {@code null} before the
		 * first one. What a {@code REPEAT EVERY ...} written in this Script repeats. Local to this frame: never the
		 * prompt's query, never a query of a Script this one calls, never visible to its caller, and never copied
		 * to the prompt's {@code /} when the Script ends.
		 */
		public String getLastQuery() {
			return lastQuery;
		}

		public void setLastQuery(String lastQuery) {
			this.lastQuery = lastQuery;
		}
	}

	private final Deque<Frame> frames = new ArrayDeque<>();
	private StatementState promptStatement = new StatementState();
	private volatile boolean runActive;
	private boolean failFast;

	public boolean isInsideScript() {
		return !frames.isEmpty();
	}

	public int depth() {
		return frames.size();
	}

	/** The innermost executing Script, or {@code null} at the prompt. */
	public Frame current() {
		return frames.peekLast();
	}

	/**
	 * @param realPath the canonical path of the Script about to run (callers pass {@code toRealPath()})
	 * @throws BroadSQLException when the Script is already executing, or the nesting limit is exceeded
	 */
	public Frame push(Path realPath) throws BroadSQLException {
		Frame caller = current();
		return push(realPath, caller == null ? null : caller.getRunId(), realPath.getFileName() == null ? realPath.toString() : realPath.getFileName().toString());
	}

	/**
	 * SPRINT 0110A: pushes a frame that inherits the caller's {@code ON ERROR} policy and {@code OUTPUT} mode
	 * ({@code CONTINUE}/{@code NORMAL} for a top-level run, spec 11.3, 12.1) and runs under {@code runId}.
	 */
	public Frame push(Path realPath, String runId, String reference) throws BroadSQLException {
		checkCanPush(realPath);
		Frame caller = current();
		Origin origin = caller == null ? Origin.INTERACTIVE : Origin.SCRIPT;
		ErrorPolicy policy = failFast ? ErrorPolicy.STOP : caller == null ? ErrorPolicy.CONTINUE : caller.getErrorPolicy();
		Frame frame = new Frame(realPath, origin, frames.size() + 1, runId, reference, policy,
				caller == null ? OutputMode.NORMAL : caller.getOutputMode());
		frames.addLast(frame);
		return frame;
	}

	/**
	 * SPRINT 0110A: the recursion and depth checks of {@link #push}, without pushing - part of a run's preflight,
	 * done before any argument is assigned (spec 12.5).
	 */
	public void checkCanPush(Path realPath) throws BroadSQLException {
		for (Frame active : frames) {
			if (active.getRealPath().equals(realPath)) {
				throw new BroadSQLException("Recursive script execution: " + chain(realPath));
			}
		}
		if (frames.size() >= MAX_DEPTH) {
			throw new BroadSQLException("Scripts are nested more than " + MAX_DEPTH + " levels deep: " + chain(realPath));
		}
	}

	/** Removes {@code frame} (and anything above it, defensively) - always call from a {@code finally}. */
	public void pop(Frame frame) {
		while (!frames.isEmpty()) {
			Frame removed = frames.removeLast();
			if (removed == frame) {
				return;
			}
		}
	}

	/** SPRINT 0110A: whether {@code OUTPUT QUIET} applies now (the innermost running Script's mode); never at the prompt. */
	public boolean isQuiet() {
		Frame frame = current();
		return frame != null && frame.getOutputMode() == OutputMode.QUIET;
	}

	/** SPRINT 0110A: the state of the statement executing at the innermost level (a frame, or the prompt). */
	public StatementState currentStatement() {
		Frame frame = current();
		return frame == null ? promptStatement : frame.getStatement();
	}

	/** SPRINT 0110A: starts a new statement at the innermost level and returns its state. */
	public StatementState beginStatement() {
		StatementState state = new StatementState();
		Frame frame = current();
		if (frame == null) {
			promptStatement = state;
		} else {
			frame.statement = state;
		}
		return state;
	}

	/** SPRINT 0110A: whether a top-level Script run is in progress (read by the CTRL+C handler, another thread). */
	public boolean isRunActive() {
		return runActive;
	}

	public void setRunActive(boolean runActive) {
		this.runActive = runActive;
	}

	/**
	 * GitHub #196: while {@code REPEAT} runs, every Script it starts (its {@code @script} or {@code LIB RUN} target,
	 * and any Script those call) stops at its first failed statement, whatever the caller's {@code ON ERROR}
	 * policy: a monitoring iteration is fail-fast.
	 */
	public boolean isFailFast() {
		return failFast;
	}

	public void setFailFast(boolean failFast) {
		this.failFast = failFast;
	}

	private String chain(Path candidate) {
		List<String> names = new ArrayList<>();
		Iterator<Frame> it = frames.iterator();
		while (it.hasNext()) {
			names.add(it.next().getRealPath().toString());
		}
		names.add(candidate.toString());
		return String.join(" -> ", names);
	}
}
