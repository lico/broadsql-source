package com.upandcoding.broadsql.controller.shell.commands.core.sql;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandCancellation;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.output.CapturingConsole;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.controller.shell.repeat.RepeatGuard;
import com.upandcoding.broadsql.controller.shell.repeat.RepeatLoop;
import com.upandcoding.broadsql.controller.shell.repeat.RepeatMonitorOutput;
import com.upandcoding.broadsql.controller.shell.repeat.RepeatSafety;
import com.upandcoding.broadsql.controller.shell.repeat.RepeatStatement;
import com.upandcoding.broadsql.controller.shell.repeat.RepeatStatement.TargetKind;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptContextStack;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptResolver;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptRunResult;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptStatus;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptTextIO;
import com.upandcoding.broadsql.controller.shell.scripts.StatementSplitter;

/**
 * Runs a query, a block of queries or a script again and again, in the foreground, to watch something change:
 * {@code REPEAT [<target>] EVERY <interval> [FOR <duration> | COUNT <iterations>] [TO <file> [AS CSV|JSON|TEXT]]}.
 * The parts always come in this order: what to repeat, how often, for how long, and where to record it.
 *
 * <p>The target is one of:
 *
 * - nothing, or {@code /}: the last query, the one {@code /} runs again. {@code REPEAT EVERY 10s} and
 * {@code REPEAT / EVERY 10s} are the same command. It is the last SQL query typed at the prompt, not the last
 * command: after {@code SELECT ...;} then {@code DESC JOBS;}, {@code REPEAT EVERY 5s} repeats the {@code SELECT}.
 * Written in a script, it is the last query of that script, executed before the REPEAT: never a query typed at the
 * prompt before the script started, nor a query of another script it called. A script with no query before the
 * REPEAT is refused. In both cases the most recent query is the one used: if it changes data, REPEAT refuses it.
 * - {@code BEGIN <query>; <query>; ... END}: a block of queries, each ending with {@code ;}, typed on one line or
 * over several lines. The block ends at the {@code END} word that follows the last query.
 * - {@code @<script> [name=value ...]}: a Script, found and run exactly as {@code @} finds and runs it.
 * - {@code LIB RUN <script> [name=value ...]}: a Scripts Library script, run exactly as {@code LIB RUN} runs it.
 *
 * <p>One iteration runs the whole target: the one query, every query of the block, or the whole script. Its
 * statements run one after another, in their written order, never at the same time. The first iteration starts
 * immediately. {@code EVERY} is the wait **after** an iteration has completed: if the queries take 3 seconds
 * and the command says {@code EVERY 10s}, an iteration starts about every 13 seconds. Two iterations never
 * overlap.
 *
 * <p>REPEAT runs in the foreground: it keeps the prompt until it ends, and a statement written after it (in a
 * script, or on the same line) runs only once it has ended. Several REPEAT commands are never parallel
 * monitors: to watch three queries together, put them in one {@code BEGIN ... END} block or in one script.
 * Press **Ctrl+C** to stop it, during a query or during the wait: BroadSQL stops the REPEAT, returns to the prompt
 * and stays connected. When the REPEAT was started by a script, Ctrl+C stops that script too.
 *
 * ### Durations
 *
 * {@code EVERY} and {@code FOR} take a duration: a positive whole number immediately followed by its unit.
 *
 * | Unit | Meaning | Examples |
 * |---|---|---|
 * | {@code s} | seconds | {@code EVERY 10s}, {@code FOR 30s} |
 * | {@code m} | minutes | {@code EVERY 2m}, {@code FOR 45m} |
 * | {@code h} | hours | {@code EVERY 1h}, {@code FOR 4h} |
 *
 * The unit may be written in capitals ({@code 10S}). Nothing else is accepted, and each refused form is explained:
 *
 * - no space between the number and the unit: {@code 10s}, not {@code 10 s};
 * - no milliseconds ({@code 500ms}): the shortest interval is {@code EVERY 1s};
 * - no decimals ({@code 0.5s}, {@code 1.5m}): write {@code 90s};
 * - no combined units ({@code 1h30m}): write {@code 90m};
 * - no days ({@code 1d}): write {@code 24h};
 * - no zero and no negative value.
 *
 * {@code EVERY} is mandatory, there is no default interval.
 *
 * ### How long it runs
 *
 * Without {@code FOR} or {@code COUNT}, REPEAT runs until Ctrl+C or an error.
 *
 * {@code COUNT <iterations>}, a positive whole number, runs at most that many complete iterations:
 * {@code EVERY 2s COUNT 3} runs the target three times, then ends without a last wait.
 *
 * {@code FOR <duration>} is measured from the start of the first iteration. A new iteration starts only before the
 * deadline: with {@code EVERY 10s FOR 30s} and fast queries, iterations start at about 0, 10 and 20 seconds. An
 * iteration still running when the deadline passes is never interrupted: it completes, then REPEAT ends.
 *
 * {@code FOR} and {@code COUNT} cannot be combined.
 *
 * ### Queries only
 *
 * REPEAT is for monitoring, so it applies a query-only restriction: it repeats {@code SELECT}, {@code WITH}, {@code SHOW} and
 * {@code EXPLAIN} statements, and calls of scripts ({@code @}, {@code LIB RUN}) that themselves contain only such
 * queries. {@code INSERT}, {@code UPDATE}, {@code DELETE}, {@code MERGE}, {@code CALL}, DDL, a query containing
 * {@code INTO} or {@code FOR UPDATE}, and every BroadSQL command ({@code CONNECT}, {@code DUMP}, {@code LET},
 * {@code SET}...) are refused, whatever the target: the last query, the block, the script and the scripts it
 * calls. What can be checked before the start is checked then, and every statement is checked again just before
 * it runs, so nothing that is refused ever reaches the database. A REPEAT inside a repeated target is refused
 * too: REPEAT cannot be nested. This restriction is about statements, not a guarantee that the database never
 * changes: a query can call a function that has side effects (the next value of a sequence, or a function of the
 * database product), and functions are not examined.
 *
 * ### Errors
 *
 * The first error ends the REPEAT: the error is shown as usual, the rest of the iteration is not run, no other
 * iteration starts, and the prompt returns. A repeated script stops at its first error whatever its
 * {@code ON ERROR} setting.
 *
 * ### Display
 *
 * Each iteration starts with a line giving its number and time ({@code === Repeat #3 at 2026-10-07 21:10:13 ===}).
 * The results follow, displayed as usual, one after another; the screen is never cleared. In a block of several
 * queries, each result is preceded by its position and query ({@code [2/3] SELECT ...}). After each iteration, its
 * duration and the next wait are shown.
 *
 * ### Monitoring output
 *
 * {@code TO <file> [AS CSV|JSON|TEXT]} also records every iteration in a file, in addition to the screen, which
 * keeps showing everything. Each iteration is **appended**: nothing already in the file is ever overwritten. A
 * relative file name is in the export folder ({@code DefaultFolder}); without {@code AS}, the extension
 * ({@code .csv}, {@code .json}, {@code .txt}) gives the format, and without an extension, {@code AS} adds it.
 *
 * - {@code CSV}: one row per result row, separated by the {@code CsvSeparator} setting, with a header when the
 * file is new. The first column, {@code TIMESTAMP}, is the time the iteration started, so the file is a timeline
 * without changing the query. When the result already has a {@code TIMESTAMP} column, the timestamp column is
 * named {@code REPEAT_TIMESTAMP}, then {@code REPEAT_TIMESTAMP_2}, {@code REPEAT_TIMESTAMP_3}... (the first name the
 * result does not use); the result's own columns are never renamed. An existing file is appended to only when it
 * has the same columns.
 * - {@code JSON}: JSON Lines, one object per result row and per line, with the same timestamp first.
 * - {@code TEXT}: a readable log: the iteration number and time, then each query and its rows, tab separated.
 *
 * {@code CSV} and {@code JSON} hold one table, so they need exactly one tabular result per iteration: a block or
 * script with several queries is refused with these formats, rather than mixing unrelated columns in one file.
 * {@code TEXT} records any number of results. Only complete results are recorded: a result cut at
 * {@code MaxRowsOnScreen} stops the REPEAT.
 *
 * ### In a script and in the Editor
 *
 * A REPEAT block written in a script is read as one statement, its inner {@code ;} included, by {@code @},
 * {@code LIB RUN} and the BroadSQL Editor's Run. In the Editor, which shows a run's output when it ends, REPEAT
 * needs {@code FOR} or {@code COUNT}.
 *
 * ### A reusable monitoring script
 *
 * A file {@code monitor.sql} in the Scripts Library:
 *
 * ```
 * SELECT COUNT(*) AS OPEN_ORDERS FROM ORDERS WHERE STATUS = 'OPEN';
 * SELECT COUNT(*) AS FAILED_INVOICES FROM INVOICES WHERE STATUS = 'FAILED';
 * SELECT MAX(CREATED_AT) AS LAST_ORDER FROM ORDERS;
 * ```
 *
 * {@code REPEAT LIB RUN monitor.sql EVERY 10s FOR 30m;} (or {@code REPEAT @monitor.sql EVERY 10s FOR 30m;}) runs the
 * three queries in order every 10 seconds for 30 minutes; each complete run of the script is one iteration.
 *
 * See [Running Scripts](../scripting_running.md) for how {@code @} and {@code LIB RUN} find a script, and
 * [Export files](../export_files.md) for the CSV and JSON formats.
 */
public class CommandRepeat extends Command {

	private static final DateTimeFormatter HEADER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	// Test seams: the timing of the loop (null = real time)
	private static volatile RepeatLoop.Clock clockForTests;
	private static volatile RepeatLoop.Sleeper sleeperForTests;

	public CommandRepeat() {
		super("REPEAT");
	}

	/** Replaces the clock and the wait of every following REPEAT; {@code null} restores real time. For tests only. */
	public static void setTimingForTests(RepeatLoop.Clock clock, RepeatLoop.Sleeper sleeper) {
		clockForTests = clock;
		sleeperForTests = sleeper;
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		CommandInterpreter interpreter = getConsoleCommandInterpreter();
		if (interpreter.getRepeatGuard() != null) {
			throw new BroadSQLException("Nested REPEAT is not supported: a REPEAT is already running.");
		}
		RepeatStatement statement = RepeatStatement.parse(textAfterKeyword(query));
		if (!statement.isBounded() && console instanceof CapturingConsole) {
			throw new BroadSQLException("In the BroadSQL Editor, REPEAT needs FOR <duration> or COUNT <iterations>: the Editor shows the output when "
					+ "the run ends, and has no Ctrl+C to stop it.");
		}

		List<String> statements = targetStatements(statement);
		int expectedResults = checkBeforeStart(statement, statements, interpreter);
		RepeatMonitorOutput output = openOutput(statement, expectedResults);

		RepeatLoop.Clock clock = clockForTests != null ? clockForTests : RepeatLoop.SYSTEM_CLOCK;
		RepeatLoop.Sleeper sleeper = sleeperForTests != null ? sleeperForTests : RepeatLoop.SYSTEM_SLEEPER;
		printStart(statement, statements, output);

		ScriptContextStack context = interpreter.getScriptContext();
		RepeatGuard guard = new RepeatGuard();
		boolean previousFailFast = context.isFailFast();
		String savedQuery = interpreter.getQuery();
		AtomicBoolean cancelled = new AtomicBoolean(false);
		AtomicReference<String> failure = new AtomicReference<>();
		RepeatLoop.Outcome outcome;
		interpreter.setRepeatGuard(guard);
		context.setFailFast(true);
		try {
			RepeatLoop loop = new RepeatLoop(statement.getEvery().millis(), statement.getForDuration() == null ? null : statement.getForDuration().millis(),
					statement.getCount(), clock, sleeper, () -> cancelled.get() || CommandCancellation.isRunCancelled());
			outcome = loop.run(number -> runIteration(number, statement, statements, interpreter, context, guard, output, clock, cancelled, failure),
					new RepeatLoop.Listener() {
						@Override
						public void iterationCompleted(int number, long elapsedMillis, long nextWaitMillis) {
							console.println("Iteration #" + number + " completed in " + elapsedMillis + " ms");
							if (nextWaitMillis >= 0) {
								console.println("Next execution in " + statement.getEvery() + "...");
							}
							console.println("");
						}
					});
		} finally {
			interpreter.setRepeatGuard(null);
			context.setFailFast(previousFailFast);
			interpreter.setQuery(savedQuery);
		}
		printEnd(statement, outcome, failure.get());
	}

	/**
	 * The query {@code REPEAT EVERY} and {@code REPEAT / EVERY} repeat. At the prompt: the last query, the one
	 * {@code /} runs. In a Script: the last SQL query that Script itself executed ({@code ScriptContextStack.Frame#getLastQuery}),
	 * never the prompt's query nor one of a Script it called. The most recent one is used as it is: a data change is
	 * refused, never replaced by an older query.
	 */
	private String lastQuery() throws BroadSQLException {
		CommandInterpreter interpreter = getConsoleCommandInterpreter();
		if (interpreter.isRunningInsideScript()) {
			ScriptContextStack.Frame frame = interpreter.getScriptContext().current();
			if (StringUtils.isBlank(frame.getLastQuery())) {
				throw new BroadSQLException("No query has run in " + frame.getReference() + " before this REPEAT: inside a script, REPEAT EVERY "
						+ "<interval> repeats the last query of that script. Write the query before the REPEAT, or give REPEAT a target "
						+ "(BEGIN <query>; END).");
			}
			return frame.getLastQuery().trim();
		}
		if (StringUtils.isBlank(getLastSQLQuery())) {
			throw new BroadSQLException("No query in memory: run a query first, then REPEAT EVERY <interval> repeats it.");
		}
		return getLastSQLQuery().trim();
	}

	/** What one iteration executes, statement by statement. */
	private List<String> targetStatements(RepeatStatement statement) throws BroadSQLException {
		switch (statement.getTargetKind()) {
			case LAST_QUERY:
				return List.of(lastQuery());
			case BLOCK:
				return statement.getBlockStatements();
			default:
				return List.of(statement.getTargetStatement());
		}
	}

	/**
	 * The query-only restriction and the nesting rule, checked on everything known before the first iteration: the statements of
	 * the target and, for a script target, the statements of the script file. A script that cannot be read is not
	 * refused here: its first run reports why, as {@code @} or {@code LIB RUN} would.
	 *
	 * @return how many tabular results an iteration is known to produce at least
	 */
	private int checkBeforeStart(RepeatStatement statement, List<String> statements, CommandInterpreter interpreter) throws BroadSQLException {
		int queries = 0;
		for (String text : statements) {
			Command command = interpreter.getCommands().getCommandClassFromName(text.toUpperCase());
			String problem = RepeatSafety.problem(command, text);
			if (problem != null) {
				throw new BroadSQLException(statement.getTargetKind() == TargetKind.LAST_QUERY
						? "REPEAT repeats the last query, which cannot be repeated: " + problem
						: "REPEAT not started. " + problem);
			}
			if (command == null || command instanceof CommandDefault) {
				queries++;
			}
		}
		if (statement.getTargetKind() == TargetKind.SCRIPT || statement.getTargetKind() == TargetKind.LIBRARY_SCRIPT) {
			queries += checkScriptFile(statement, interpreter);
		}
		return queries;
	}

	private int checkScriptFile(RepeatStatement statement, CommandInterpreter interpreter) throws BroadSQLException {
		Path script;
		String text;
		try {
			ScriptResolver resolver = new ScriptResolver(consoleSettings.getScriptsLibraryPath());
			script = statement.getTargetKind() == TargetKind.SCRIPT
					? resolver.resolveForExecution(statement.getScriptReference(), interpreter.getScriptContext()).getPath()
					: resolver.resolveLibraryScript(statement.getScriptReference()).getPath();
			text = ScriptTextIO.read(script).getText();
		} catch (BroadSQLException | IOException e) {
			return 0;
		}
		StatementSplitter.Result split = StatementSplitter.splitSpans(text);
		if (!split.isTerminated()) {
			return 0;
		}
		int queries = 0;
		for (StatementSplitter.Span span : split.getSpans()) {
			Command command = interpreter.getCommands().getCommandClassFromName(span.getText().toUpperCase());
			String problem = RepeatSafety.problem(command, span.getText());
			if (problem != null) {
				throw new BroadSQLException("REPEAT not started: " + statement.getScriptReference() + " contains a statement that cannot be repeated. " + problem);
			}
			if (command == null || command instanceof CommandDefault) {
				queries++;
			}
		}
		return queries;
	}

	private RepeatMonitorOutput openOutput(RepeatStatement statement, int expectedResults) throws BroadSQLException {
		if (statement.getOutputFile() == null) {
			return null;
		}
		RepeatMonitorOutput output = RepeatMonitorOutput.open(consoleSettings.getExtractFolderName(), statement.getOutputFile(), statement.getOutputFormat(),
				statement.getOutputFormat() == RepeatStatement.OutputFormat.CSV ? consoleSettings.getCsvExportSeparator() : ',');
		if (!output.acceptsSeveralResults() && expectedResults > 1) {
			throw new BroadSQLException("REPEAT not started: the target runs " + expectedResults + " queries, and AS " + statement.getOutputFormat()
					+ " records exactly one result per iteration, so unrelated columns are never mixed in one file. Use AS TEXT to record several "
					+ "results, or repeat one query.");
		}
		return output;
	}

	/** One iteration: every statement of the target in order, stopping at the first failure or at CTRL+C. */
	private boolean runIteration(int number, RepeatStatement statement, List<String> statements, CommandInterpreter interpreter, ScriptContextStack context,
			RepeatGuard guard, RepeatMonitorOutput output, RepeatLoop.Clock clock, AtomicBoolean cancelled, AtomicReference<String> failure) {
		LocalDateTime started = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.nowMillis()), ZoneId.systemDefault()).withNano(0);
		guard.beginIteration();
		console.println("=== Repeat #" + number + " at " + started.format(HEADER_TIME) + " ===");
		boolean numbered = statement.getTargetKind() == TargetKind.BLOCK && statements.size() > 1;
		for (int index = 0; index < statements.size(); index++) {
			if (CommandCancellation.isRunCancelled()) {
				cancelled.set(true); // CTRL+C between two statements: the next one does not start
				return true;
			}
			String text = statements.get(index);
			if (numbered) {
				console.println("[" + (index + 1) + "/" + statements.size() + "] " + CommandUtils.toSingleLine(text));
			}
			ScriptContextStack.StatementState state = context.beginStatement();
			long errorsBefore = ShellConsole.errorSerial();
			boolean thrown = false;
			try {
				interpreter.setQuery(text);
				interpreter.executeCommand();
			} catch (BroadSQLException e) {
				thrown = true;
				console.error(e);
				console.println("");
			}
			boolean calledRunCancelled = false;
			for (ScriptRunResult called : state.getCalledRuns()) {
				calledRunCancelled |= called.getStatus() == ScriptStatus.CANCELLED;
			}
			if (calledRunCancelled || CommandCancellation.isRequested() || CommandCancellation.isRunCancelled()) {
				cancelled.set(true);
				return true; // the loop sees the cancellation
			}
			Boolean stated = state.getExplicitFailed();
			if (thrown || (stated != null ? stated : ShellConsole.errorSerial() != errorsBefore)) {
				failure.set("iteration " + number + (numbered ? ", query " + (index + 1) + " of " + statements.size() : "") + " failed");
				return false;
			}
		}
		if (output != null) {
			try {
				output.append(number, started, guard.results());
			} catch (BroadSQLException e) {
				console.error(e);
				console.println("");
				failure.set("iteration " + number + " could not be recorded in " + output.getFile());
				return false;
			}
		}
		return true;
	}

	private void printStart(RepeatStatement statement, List<String> statements, RepeatMonitorOutput output) {
		String what;
		switch (statement.getTargetKind()) {
			case LAST_QUERY:
				what = "the last query (" + CommandUtils.toSingleLine(statements.get(0)) + ")";
				break;
			case BLOCK:
				int size = statement.getBlockStatements().size();
				what = size == 1 ? "a block of 1 query" : "a block of " + size + " queries";
				break;
			default:
				what = CommandUtils.toSingleLine(statement.getTargetStatement());
				break;
		}
		String until = statement.getCount() != null ? ", " + statement.getCount() + (statement.getCount() == 1 ? " time" : " times")
				: statement.getForDuration() != null ? ", for " + statement.getForDuration() : "";
		console.println("Repeating " + what + " every " + statement.getEvery() + until + ". Press Ctrl+C to stop.");
		if (output != null) {
			console.println("Each iteration is appended to " + output.getFile() + " (" + output.getFormat() + ").");
		}
		console.println("");
	}

	private void printEnd(RepeatStatement statement, RepeatLoop.Outcome outcome, String failure) throws BroadSQLException {
		int n = outcome.iterations();
		String iterations = n + (n == 1 ? " iteration" : " iterations");
		switch (outcome.ending()) {
			case COUNT_REACHED:
				console.println("REPEAT finished: " + iterations + " (COUNT " + statement.getCount() + ").");
				console.println("");
				break;
			case DURATION_ELAPSED:
				console.println("REPEAT finished: FOR " + statement.getForDuration() + " elapsed after " + iterations + ".");
				console.println("");
				break;
			case CANCELLED:
				console.println("REPEAT cancelled by user after " + n + " complete " + (n == 1 ? "iteration." : "iterations."));
				console.println("");
				break;
			default:
				throw new BroadSQLException("REPEAT stopped: " + (failure == null ? "an iteration failed" : failure) + ", no further iteration was started ("
						+ iterations + " completed).");
		}
	}

	@Override
	public String getDescription() {
		return "Repeats a query, a block of queries or a script at a fixed interval, to monitor";
	}

	@Override
	public String getDetailedDescription() {
		return "Runs a query, a block of queries or a script again and again, in the foreground, to watch something change. "
				+ "The parts always come in this order: REPEAT [<target>] EVERY <interval> [FOR <duration> | COUNT <iterations>] [TO <file> [AS CSV|JSON|TEXT]]. "
				+ "The target is nothing or / (the last query), BEGIN <query>; ... END (a block of queries run in order), @<script> or LIB RUN <script>. "
				+ "The first iteration starts at once; EVERY is the wait after each iteration ends, so iterations never overlap. "
				+ "Durations are a whole number followed by s, m or h (10s, 5m, 2h). Only queries can be repeated. Ctrl+C stops it.";
	}

	@Override
	public String getArguments() {
		return "[<target>] EVERY <interval> [FOR <duration> | COUNT <iterations>] [TO <file> [AS CSV|JSON|TEXT]], where <target> is nothing or / "
				+ "(the last query), BEGIN <query>; <query>; ... END, @<script> [name=value ...] or LIB RUN <script> [name=value ...]; <interval> and "
				+ "<duration> are a positive whole number immediately followed by s, m or h (minimum 1s); <iterations> is a positive whole number; "
				+ "FOR and COUNT cannot be combined";
	}

	@Override
	public String getExamples() {
		return "SELECT COUNT(*) FROM JOBS WHERE STATUS = 'RUNNING';\n"
				+ "REPEAT EVERY 5s;\n"
				+ "REPEAT / EVERY 10s COUNT 6;\n"
				+ "REPEAT\n"
				+ "BEGIN\n"
				+ "    SELECT COUNT(*) FROM ORDERS;\n"
				+ "    SELECT COUNT(*) FROM INVOICES;\n"
				+ "    SELECT COUNT(*) FROM PAYMENTS WHERE STATUS = 'FAILED';\n"
				+ "END\n"
				+ "EVERY 10s;\n"
				+ "REPEAT @monitor.sql EVERY 10s FOR 30m;\n"
				+ "REPEAT LIB RUN monitor.sql EVERY 1m COUNT 60;\n"
				+ "REPEAT\n"
				+ "BEGIN\n"
				+ "    SELECT STATUS, COUNT(*) AS CNT FROM PROCESS_QUEUE GROUP BY STATUS;\n"
				+ "END\n"
				+ "EVERY 10s\n"
				+ "FOR 1h\n"
				+ "TO queue-monitor.csv AS CSV;";
	}
}
