package com.upandcoding.broadsql.controller.shell.scripts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandCancellation;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.export.TabularResultCapture;
import com.upandcoding.broadsql.dao.export.TabularResultSpool;

/**
 * SPRINT 1909S: the ONE pipeline that executes a BroadSQL Script. {@code @script}, {@code LIB RUN
 * script}, the BroadSQL Editor's Run and the script sources of {@code DUMP}/{@code PULL} all end here; nothing
 * else in BroadSQL executes script text, so a given Script behaves identically however it was started.
 *
 * <p>A Script is a text file holding SQL statements, BroadSQL commands, or both, separated by
 * {@code ;} ({@link StatementSplitter}; a statement may span several lines). The file extension plays no
 * part. Each statement is executed exactly as if typed at the prompt, through
 * {@link CommandInterpreter#executeCommand()}; a statement that itself starts with {@code @} runs another
 * Script, so Scripts can call Scripts.
 *
 * <p>SPRINT 0110A (spec {@code docs/06. work/SCRIPTING_VARIABLES_SPECIFICATION.md}): every run returns a
 * {@link ScriptRunResult}. A call is, in this order (spec 17.3): its named arguments parsed and evaluated from
 * the session without assigning anything; the Script resolved, read, split and checked (preflight, spec 12.5:
 * not found, unreadable, unterminated quote or comment, empty, recursion, depth, invalid {@code @params},
 * missing {@code @params} argument, invalid {@code ON ERROR}/{@code OUTPUT}); the arguments assigned into the
 * session namespace; the statements executed. A failure before execution assigns nothing, executes nothing and
 * ends the run {@code FAILED}. Each statement's outcome is computed from every error channel (thrown, printed
 * through {@code ShellConsole.error}, stated by a called run), counted, and handled by the Script's
 * {@code ON ERROR} policy; CTRL+C cancels the whole top-level run. A top-level run prints its final status
 * line with its Run ID (no start line), then a notice when uncommitted changes are pending.
 */
public final class ScriptExecutor {

	private final ShellConsole console;
	private final CommandInterpreter interpreter;
	private final DatabaseDefinitionsVault vault;

	public ScriptExecutor(ShellConsole console, CommandInterpreter interpreter, DatabaseDefinitionsVault vault) {
		this.console = console;
		this.interpreter = interpreter;
		this.vault = vault;
	}

	public static ScriptExecutor forCommand(Command host) {
		return new ScriptExecutor(host.getConsole(), host.getConsoleCommandInterpreter(), host.getDatabaseConnectionsVault());
	}

	/** Everything the preflight established, so execution starts only when all of it succeeded. */
	/** The mode the top-level run ended in: its {@code SUCCESS} status line follows it (spec 11.4) although the frame is gone. */
	private boolean endedQuiet;

	private record Prepared(Path real, String text, List<StatementSplitter.Span> spans, LinkedHashMap<String, ScriptValue> arguments) {
	}

	/**
	 * Runs {@code call} and returns its result. Never throws for a failure of the Script itself (that is the
	 * {@code FAILED} status, with the error printed); records the result for the statement that made the call
	 * ({@link ScriptContextStack#currentStatement()}).
	 */
	public ScriptRunResult run(ScriptCall call) {
		ScriptContextStack context = interpreter.getScriptContext();
		boolean topLevel = !context.isInsideScript();
		String runId = topLevel ? RunIds.next() : context.current().getRunId();
		String previousRunId = null;
		if (topLevel) {
			CommandCancellation.resetRun();
			context.setRunActive(true);
			previousRunId = MDC.get(RunIds.MDC_KEY);
			MDC.put(RunIds.MDC_KEY, runId);
		}
		installRoutineOutputSuppressor(context);
		ScriptRunResult result;
		try {
			result = execute(call, context, runId, topLevel);
			if (topLevel) {
				reportEnd(result);
			}
		} finally {
			if (topLevel) {
				context.setRunActive(false);
				if (previousRunId == null) {
					MDC.remove(RunIds.MDC_KEY);
				} else {
					MDC.put(RunIds.MDC_KEY, previousRunId);
				}
			}
		}
		ScriptContextStack.StatementState caller = context.currentStatement();
		caller.recordCalledRun(result);
		caller.setFailed(result.failsCallingStatement());
		return result;
	}

	/**
	 * SPRINT 2309T (#163): runs {@code call} exactly like {@link #run(ScriptCall)} inside an explicit capture scope,
	 * so every tabular result the script produces goes into {@code spool} instead of the screen, the last one
	 * replacing the previous ones. Used by {@code DUMP LIB}/{@code DUMP @}; {@code LIB RUN} and {@code @} never
	 * open a capture scope.
	 */
	public ScriptRunResult runCapturingResults(ScriptCall call, TabularResultSpool spool) {
		TabularResultCapture.begin(spool);
		try {
			return run(call);
		} finally {
			TabularResultCapture.end(spool);
		}
	}

	private ScriptRunResult execute(ScriptCall call, ScriptContextStack context, String runId, boolean topLevel) {
		String reference = call.getReference();
		Prepared prepared;
		try {
			prepared = preflight(call, context);
		} catch (BroadSQLException e) {
			console.error(e);
			console.println("");
			return new ScriptRunResult(reference, ScriptStatus.FAILED, 0, 0, runId, 0, 0, topLevel);
		}

		interpreter.getScriptVariables().assignAll(prepared.arguments());
		ScriptContextStack.Frame frame;
		try {
			frame = context.push(prepared.real(), runId, reference);
		} catch (BroadSQLException cannotHappenAfterPreflight) {
			console.error(cannotHappenAfterPreflight);
			return new ScriptRunResult(reference, ScriptStatus.FAILED, 0, 0, runId, 0, 0, topLevel);
		}

		String savedQuery = interpreter.getQuery();
		List<StatementSplitter.Span> spans = prepared.spans();
		int executed = 0;
		int failed = 0;
		int stopStatement = 0;
		int stopLine = 0;
		ScriptStatus status = null;
		try {
			warnIfMetadataMismatch(prepared.text(), prepared.real());
			if (spans.size() > 1) {
				console.routineln("Found " + spans.size() + " queries");
			}
			for (int index = 0; index < spans.size(); index++) {
				if (CommandCancellation.isRunCancelled()) {
					status = ScriptStatus.CANCELLED;
					break;
				}
				String statement = spans.get(index).getText();
				console.routineln(statement);
				ScriptContextStack.StatementState state = context.beginStatement();
				long errorsBefore = ShellConsole.errorSerial();
				boolean thrown = false;
				try {
					interpreter.setQuery(statement);
					interpreter.executeCommand();
				} catch (BroadSQLException se) {
					thrown = true;
					console.error(se);
					console.println("");
				}
				executed++;
				boolean calledRunCancelled = false;
				for (ScriptRunResult called : state.getCalledRuns()) {
					executed += called.getExecuted();
					failed += called.getFailed();
					calledRunCancelled |= called.getStatus() == ScriptStatus.CANCELLED;
				}
				if (calledRunCancelled || CommandCancellation.isRequested() || CommandCancellation.isRunCancelled()) {
					status = ScriptStatus.CANCELLED;
					break;
				}
				Boolean stated = state.getExplicitFailed();
				boolean statementFailed = thrown || (stated != null ? stated : ShellConsole.errorSerial() != errorsBefore);
				if (statementFailed) {
					failed++;
					if (frame.getErrorPolicy() == ErrorPolicy.STOP) {
						stopStatement = index + 1;
						stopLine = startLine(prepared.text(), spans.get(index));
						console.error("Script " + reference + " stopped by ON ERROR STOP at statement " + stopStatement + " (line " + stopLine + ")");
						status = ScriptStatus.FAILED;
						break;
					}
				}
			}
			if (status == null) {
				status = failed > 0 ? ScriptStatus.COMPLETED_WITH_ERRORS : ScriptStatus.SUCCESS;
			}
		} finally {
			endedQuiet = frame.getOutputMode() == OutputMode.QUIET;
			context.pop(frame);
			interpreter.setQuery(savedQuery);
		}
		return new ScriptRunResult(reference, status, executed, failed, runId, stopStatement, stopLine, topLevel);
	}

	/** Steps 1 to 3 of a call (spec 17.3), nothing assigned or executed: arguments, Script, preflight, {@code @params}. */
	private Prepared preflight(ScriptCall call, ScriptContextStack context) throws BroadSQLException {
		ScriptArguments arguments = ScriptArguments.parse(call.getArgumentText());
		LinkedHashMap<String, ScriptValue> values = arguments.evaluate(interpreter.getScriptVariables());

		Path script = call.resolve();
		Path real;
		ScriptTextIO.TextContent content;
		try {
			real = script.toRealPath();
			content = ScriptTextIO.read(real);
		} catch (IOException e) {
			throw new BroadSQLException(e.getMessage() != null && e.getMessage().contains("not a text file") ? e.getMessage()
					: "Cannot read script " + script + ": " + e.getMessage());
		}
		String text = content.getText();
		StatementSplitter.Result split = StatementSplitter.splitSpans(text);
		if (!split.isTerminated()) {
			throw new BroadSQLException(split.describeUnterminated() + " in " + real);
		}
		List<StatementSplitter.Span> spans = split.getSpans();
		if (spans.isEmpty()) {
			throw new BroadSQLException("The script " + real + " does not contain queries");
		}
		context.checkCanPush(real);

		EntryMetadata metadata = EntryMetadata.parse(text);
		for (String declared : metadata.getDeclaredParams()) {
			String problem = VariableNames.problem(declared, "name '" + declared + "' in @params");
			if (problem != null) {
				throw new BroadSQLException(call.getReference() + " declares an invalid parameter: " + problem
						+ ". The script cannot be called until its @params declaration is fixed");
			}
		}
		for (int index = 0; index < spans.size(); index++) {
			String statement = spans.get(index).getText();
			try {
				if (ScriptDirectives.isOnError(statement)) {
					ScriptDirectives.parseOnError(statement);
				} else if (ScriptDirectives.isOutput(statement)) {
					ScriptDirectives.parseOutput(statement);
				}
			} catch (BroadSQLException e) {
				throw new BroadSQLException(e.getLocalizedMessage() + " (statement " + (index + 1) + ", line " + startLine(text, spans.get(index)) + " of "
						+ call.getReference() + ")");
			}
		}
		Set<String> given = arguments.nameKeys();
		List<String> missing = new ArrayList<>();
		for (String param : metadata.getParams()) {
			if (!given.contains(VariableNames.key(param))) {
				missing.add(param);
			}
		}
		if (!missing.isEmpty()) {
			throw new BroadSQLException(call.getReference() + " requires argument" + (missing.size() > 1 ? "s " : " ") + String.join(", ", missing)
					+ " (declared in @params): pass " + (missing.size() > 1 ? "each one" : "it") + " explicitly as name=value, even when a session variable of that name exists");
		}
		return new Prepared(real, text, spans, values);
	}

	/** The final status line and the pending-changes notice of a top-level run (spec 14.4, 15.5). */
	private void reportEnd(ScriptRunResult result) {
		if (result.getStatus() == ScriptStatus.CANCELLED) {
			console.println("Cancelled by user.");
		}
		if (result.isSuccess() && endedQuiet) {
			console.logOnlyln(result.statusLine());
		} else if (result.isSuccess()) {
			console.routineln(result.statusLine());
		} else {
			console.println(result.statusLine());
		}
		DatabaseConnection db = interpreter.getSqlDatabase();
		if (db != null && !db.isAutoCommit() && db.isHasUncommitted()) {
			String connection = db.getPlatform() != null ? db.getPlatform().getId() : "the current connection";
			console.warn("Uncommitted changes are pending on " + connection + ": COMMIT or ROLLBACK ends the transaction.");
		}
	}

	private void installRoutineOutputSuppressor(ScriptContextStack context) {
		console.setRoutineOutputSuppressor(context::isQuiet);
		if (interpreter.getConsole() != null) {
			interpreter.getConsole().setRoutineOutputSuppressor(context::isQuiet);
		}
		DatabaseConnection db = interpreter.getSqlDatabase();
		if (db != null && db.getCmdLineConsole() != null) {
			db.getCmdLineConsole().setRoutineOutputSuppressor(context::isQuiet);
		}
	}

	/**
	 * The 1-based line where {@code span}'s statement starts in {@code text}: its first character that is
	 * neither whitespace nor part of a comment.
	 */
	static int startLine(String text, StatementSplitter.Span span) {
		int i = span.getStart();
		int end = Math.min(span.getEnd(), text.length());
		while (i < end) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
			} else if (c == '-' && i + 1 < end && text.charAt(i + 1) == '-') {
				while (i < end && text.charAt(i) != '\n') {
					i++;
				}
			} else if (c == '/' && i + 1 < end && text.charAt(i + 1) == '*') {
				int close = text.indexOf("*/", i + 2);
				i = close < 0 ? end : close + 2;
			} else {
				break;
			}
		}
		int line = 1;
		for (int k = 0; k < i && k < text.length(); k++) {
			if (text.charAt(k) == '\n') {
				line++;
			}
		}
		return line;
	}

	private void warnIfMetadataMismatch(String text, Path script) {
		for (String warning : metadataWarnings(text, interpreter.getPlatform(), vault, script.getFileName().toString())) {
			console.warningText(warning);
		}
	}

	/**
	 * The one rule for the {@code @instance}/{@code @environment} safeguards: warnings (never a refusal)
	 * for each tag of {@code scriptText} that does not match the current connection's Database Group or
	 * environment. Empty when there is no connection ({@code platform} blank). Also used by {@code LIB SHOW}.
	 */
	public static List<String> metadataWarnings(String scriptText, String platform, DatabaseDefinitionsVault vault, String scriptName) {
		List<String> warnings = new ArrayList<>();
		if (StringUtils.isBlank(platform)) {
			return warnings;
		}
		EntryMetadata metadata = EntryMetadata.parse(scriptText);
		String currentInstance = CommandUtils.currentInstance(platform, vault);
		if (!metadata.appliesToInstance(currentInstance)) {
			warnings.add("The script " + scriptName + " is tagged for instance " + metadata.instanceDisplayValue()
					+ ", the current connection's instance is " + (StringUtils.isBlank(currentInstance) ? "unknown" : currentInstance));
		}
		String currentEnvironment = CommandUtils.currentEnvironment(platform, vault);
		if (!metadata.appliesToEnvironment(currentEnvironment)) {
			warnings.add("The script " + scriptName + " is tagged for environment " + metadata.environmentDisplayValue()
					+ ", the current connection's environment is " + (StringUtils.isBlank(currentEnvironment) ? "unknown" : currentEnvironment));
		}
		return warnings;
	}
}
