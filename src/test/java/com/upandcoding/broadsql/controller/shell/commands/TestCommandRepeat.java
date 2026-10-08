package com.upandcoding.broadsql.controller.shell.commands;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandRepeat;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.RunOutcome;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptRunContext;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptRunCoordinator;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptStatus;
import com.upandcoding.broadsql.dao.DatabaseConnection;

/**
 * GitHub #196: {@code REPEAT} through the real path: typed lines ({@code executeMultiStatementLine}, and the read
 * loop's line accumulation), the interpreter, {@code CommandDefault}, {@code @} and {@code LIB RUN} with their
 * {@code ScriptExecutor}, the Editor's Run, a real H2 database. What ran, in which order and how many at a time, is
 * recorded by an H2 function ({@link RepeatProbe}). Time is fake (no test waits for an interval) except in the
 * CTRL+C tests, which use the real wait and press CTRL+C as the signal handler does.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TestCommandRepeat extends ScriptingTestBase {

	private static final String LONG_QUERY = "SELECT COUNT(*) FROM SYSTEM_RANGE(1, 3000000000) A WHERE MOD(A.X, 7) = 3";
	private static final LocalDateTime START = LocalDateTime.of(2026, 10, 7, 21, 10, 0);

	private long fakeNow;
	private final List<Long> waits = new ArrayList<>();
	private StringBuilder typed = new StringBuilder();

	@BeforeEach
	void setUpRepeat() throws Exception {
		fakeNow = START.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		CommandRepeat.setTimingForTests(() -> fakeNow, (millis, cancelled) -> {
			waits.add(millis);
			fakeNow += millis;
			return !cancelled.getAsBoolean();
		});
		RepeatProbe.reset();
		try (Statement statement = db.getDirectConnection().createStatement()) {
			statement.execute("CREATE ALIAS REC FOR '" + RepeatProbe.class.getName() + ".rec'");
		}
		line("INSERT INTO CUSTOMER VALUES (1, 'Alice', 'FR');");
		clearOutput();
	}

	@AfterEach
	void tearDownRepeat() {
		CommandRepeat.setTimingForTests(null, null);
		Assertions.assertNull(interpreter.getRepeatGuard(), "the guard is always removed");
		Assertions.assertFalse(context().isFailFast(), "fail-fast is always restored");
	}

	private void realTime() {
		CommandRepeat.setTimingForTests(null, null);
	}

	/** Mirrors {@code CommandInterpreter.run()}'s accumulation of typed lines, with its real block decision. */
	private void type(String... lines) {
		for (String raw : lines) {
			String l = raw.trim();
			if (l.endsWith(";") && CommandInterpreter.continuesRepeatBlock(typed, l)) {
				if (!l.startsWith("--")) {
					typed.append(l).append(' ');
				}
			} else if (l.endsWith(";")) {
				if (!l.startsWith("--")) {
					typed.append(StringUtils.substringBeforeLast(l, ";")).append(' ');
				}
				line(typed.toString());
				typed = new StringBuilder();
			} else if (!l.startsWith("--")) {
				typed.append(l).append(' ');
			}
		}
	}

	private void assertOperational() throws Exception {
		RepeatProbe.reset();
		line("SELECT REC('still working') AS R;");
		Assertions.assertEquals(List.of("still working"), RepeatProbe.calls(), output());
		Assertions.assertNull(interpreter.getRepeatGuard());
	}

	private Path out(String name) {
		return Path.of(settings.getExtractFolderName()).resolve(name);
	}

	// ---- the last query ----

	@Test
	void repeatEveryRepeatsTheLastQueryNotTheLastCommand() throws Exception {
		line("SELECT REC('last') AS LAST_QUERY_MARKER FROM CUSTOMER;");
		line("DESC CUSTOMER;");
		RepeatProbe.reset();
		clearOutput();
		line("REPEAT EVERY 1s COUNT 2;");
		Assertions.assertEquals(List.of("last", "last"), RepeatProbe.calls(), output());
		Assertions.assertEquals(2, occurrences(output(), "|LAST_QUERY_MARKER"), "two result tables: " + output());
		Assertions.assertFalse(output().contains("COUNTRY"), "DESC is not repeated: " + output());
		Assertions.assertTrue(output().contains("=== Repeat #1 at 2026-10-07 21:10:00 ==="), output());
		Assertions.assertTrue(output().contains("=== Repeat #2 at 2026-10-07 21:10:01 ==="), output());
		Assertions.assertTrue(output().contains("REPEAT finished: 2 iterations (COUNT 2)."), output());
		Assertions.assertEquals(List.of(1000L), waits);
		Assertions.assertEquals("SELECT REC('last') AS LAST_QUERY_MARKER FROM CUSTOMER", interpreter.lastSQLQuery.trim(), "the last query is unchanged");
	}

	@Test
	void repeatSlashIsTheSameAsRepeat() throws Exception {
		line("SELECT REC('slash') AS R;");
		RepeatProbe.reset();
		line("REPEAT / EVERY 5s COUNT 3;");
		Assertions.assertEquals(List.of("slash", "slash", "slash"), RepeatProbe.calls(), output());
		Assertions.assertEquals(List.of(5000L, 5000L), waits);
	}

	@Test
	void withoutALastQueryNothingRuns() {
		interpreter.lastSQLQuery = null; // a fresh session
		line("REPEAT EVERY 1s;");
		Assertions.assertTrue(output().contains("No query in memory"), output());
		Assertions.assertTrue(RepeatProbe.calls().isEmpty());
	}

	@Test
	void aLastQueryThatChangesDataIsRefused() throws Exception {
		line("UPDATE CUSTOMER SET NAME = 'Bob';");
		clearOutput();
		line("REPEAT / EVERY 1s COUNT 2;");
		Assertions.assertTrue(output().contains("REPEAT repeats the last query, which cannot be repeated"), output());
		Assertions.assertTrue(waits.isEmpty());
		Assertions.assertFalse(output().contains("=== Repeat #1"), output());
	}

	// ---- the last query inside a script: local to that script ----

	@Test
	void inAScriptRepeatEveryRepeatsTheScriptsOwnLastQueryNotThePrompts() throws Exception {
		line("SELECT REC('prompt') AS R;");
		RepeatProbe.reset();
		lib("s.sql", "SELECT REC('script') AS R;\nDESC CUSTOMER;\nREPEAT EVERY 1s COUNT 2;\n");
		Assertions.assertEquals(ScriptStatus.SUCCESS, run("@s.sql;").getStatus(), output());
		Assertions.assertEquals(List.of("script", "script", "script"), RepeatProbe.calls(), "run once, then repeated twice: " + output());
		Assertions.assertEquals("SELECT REC('prompt') AS R", interpreter.lastSQLQuery.trim(), "the prompt's / is unchanged by the script");
		line("REPEAT / EVERY 1s COUNT 1;");
		Assertions.assertEquals("prompt", RepeatProbe.calls().get(3), "back at the prompt, / is the prompt's query again");
	}

	@Test
	void aScriptWithoutAQueryBeforeTheRepeatIsRefusedAndNeverReachesThePrompt() throws Exception {
		line("SELECT REC('prompt') AS R;");
		RepeatProbe.reset();
		lib("none.sql", "REPEAT / EVERY 1s COUNT 1;\n");
		run("@none.sql;");
		Assertions.assertTrue(output().contains("No query has run in none.sql before this REPEAT"), output());
		Assertions.assertTrue(RepeatProbe.calls().isEmpty(), "the prompt's query is never used inside a script");
	}

	@Test
	void inAScriptTheMostRecentQueryIsUsedEvenWhenItChangesData() throws Exception {
		lib("dml.sql", "SELECT REC('older select') AS R;\nUPDATE CUSTOMER SET NAME = 'x';\nREPEAT EVERY 1s COUNT 2;\n");
		run("@dml.sql;");
		Assertions.assertTrue(output().contains("REPEAT repeats the last query, which cannot be repeated: 'UPDATE CUSTOMER SET NAME = 'x''"), output());
		Assertions.assertEquals(List.of("older select"), RepeatProbe.calls(), "never an older query instead");
		Assertions.assertTrue(waits.isEmpty());
	}

	@Test
	void nestedScriptsEachHaveTheirOwnLastQuery() throws Exception {
		lib("inner_empty.sql", "REPEAT EVERY 1s COUNT 1;\n");
		lib("outer1.sql", "SELECT REC('outer') AS R;\n@inner_empty.sql;\n");
		run("@outer1.sql;");
		Assertions.assertTrue(output().contains("No query has run in inner_empty.sql"), "a called script does not see its caller's query: " + output());
		Assertions.assertEquals(List.of("outer"), RepeatProbe.calls());

		RepeatProbe.reset();
		lib("inner_query.sql", "SELECT REC('inner') AS R;\n");
		lib("outer2.sql", "SELECT REC('outer') AS R;\n@inner_query.sql;\nREPEAT EVERY 1s COUNT 1;\n");
		run("@outer2.sql;");
		Assertions.assertEquals(List.of("outer", "inner", "outer"), RepeatProbe.calls(), "the caller does not see its called script's query: " + output());
	}

	@Test
	void anEditorRunRepeatsTheScriptsOwnLastQuery() throws Exception {
		line("SELECT REC('prompt') AS R;");
		RepeatProbe.reset();
		lib("ed_last.sql", "SELECT REC('editor') AS R;\nREPEAT EVERY 1s COUNT 1;\n");
		ScriptRunContext context = new ScriptRunContext(db, interpreter, null, null, settings, db.getPlatform().getId());
		RunOutcome outcome = new ScriptRunCoordinator().run("ed_last.sql", context, "");
		Assertions.assertEquals(ScriptStatus.SUCCESS, outcome.scriptStatus(), outcome.capturedOutput());
		Assertions.assertEquals(List.of("editor", "editor"), RepeatProbe.calls());
	}

	// ---- BEGIN ... END blocks ----

	@Test
	void aBlockRunsItsQueriesSequentiallyInWrittenOrder() throws Exception {
		line("REPEAT BEGIN SELECT REC('1') AS R; SELECT REC('2') AS R; SELECT REC('3') AS R; END EVERY 10s COUNT 2;");
		Assertions.assertEquals(List.of("1", "2", "3", "1", "2", "3"), RepeatProbe.calls(), output());
		Assertions.assertEquals(1, RepeatProbe.maxRunning(), "never two queries at the same time");
		Assertions.assertEquals(List.of(10_000L), waits, "one wait, after the first complete iteration");
		Assertions.assertTrue(output().contains("[1/3] SELECT REC('1') AS R"), output());
		Assertions.assertTrue(output().indexOf("[2/3]") < output().indexOf("[3/3]"), output());
		Assertions.assertTrue(output().contains("Iteration #1 completed in "), output());
		Assertions.assertTrue(output().contains("Next execution in 10s..."), output());
		Assertions.assertTrue(output().contains("Repeating a block of 3 queries every 10s, 2 times. Press Ctrl+C to stop."), output());
	}

	@Test
	void aBlockTypedOverSeveralLinesRunsOnlyAtItsEnd() throws Exception {
		type("REPEAT", "BEGIN", "    SELECT REC('a') AS R;", "    -- a comment;", "    SELECT REC('b')", "    AS R;");
		Assertions.assertTrue(RepeatProbe.calls().isEmpty(), "the inner ; do not execute the block: " + output());
		type("END", "EVERY 2s", "COUNT 3;");
		Assertions.assertEquals(List.of("a", "b", "a", "b", "a", "b"), RepeatProbe.calls(), output());
		Assertions.assertEquals(List.of(2000L, 2000L), waits);
		type("SELECT REC('next') AS R;");
		Assertions.assertEquals("next", RepeatProbe.calls().get(6), "statements are executed at their ; again after the block");
	}

	@Test
	void aBlockOnOneTypedLineAndTheFollowingStatementRunsAfterIt() throws Exception {
		type("REPEAT BEGIN SELECT REC('x') AS R; END EVERY 1s COUNT 2; SELECT REC('after') AS R;");
		Assertions.assertEquals(List.of("x", "x", "after"), RepeatProbe.calls(), "foreground: the next statement waits for the REPEAT to end");
	}

	@Test
	void aFailureStopsTheIterationAndTheRepeat() throws Exception {
		line("REPEAT BEGIN SELECT REC('1') AS R; SELECT * FROM NO_SUCH_TABLE; SELECT REC('3') AS R; END EVERY 1s COUNT 5;");
		Assertions.assertEquals(List.of("1"), RepeatProbe.calls(), "query 3 and the next iterations never ran: " + output());
		Assertions.assertTrue(output().contains("NO_SUCH_TABLE"), "the normal error is shown: " + output());
		Assertions.assertTrue(output().contains("REPEAT stopped: iteration 1, query 2 of 3 failed, no further iteration was started (0 iterations completed)."),
				output());
		Assertions.assertTrue(waits.isEmpty());
		Assertions.assertEquals(Boolean.TRUE, lastStatementStatedFailed() != null ? lastStatementStatedFailed() : console.wasErrorReported());
		assertOperational();
	}

	@Test
	void aBlockThatChangesDataIsRefusedBeforeAnythingRuns() throws Exception {
		line("REPEAT BEGIN SELECT REC('1') AS R; DELETE FROM CUSTOMER; END EVERY 1s COUNT 1;");
		Assertions.assertTrue(RepeatProbe.calls().isEmpty(), output());
		Assertions.assertEquals(1L, count("CUSTOMER"));
		Assertions.assertTrue(output().contains("REPEAT not started. 'DELETE FROM CUSTOMER' cannot be repeated"), output());
		line("REPEAT BEGIN CONNECT OTHER; END EVERY 1s;");
		Assertions.assertTrue(output().contains("CONNECT is a BroadSQL command"), output());
		line("REPEAT BEGIN CREATE TABLE X (A INT); END EVERY 1s;");
		Assertions.assertTrue(output().contains("'CREATE TABLE X (A INT)' cannot be repeated"), output());
	}

	@Test
	void aNestedRepeatIsRefused() throws Exception {
		line("REPEAT BEGIN SELECT REC('1') AS R; REPEAT BEGIN SELECT REC('2') AS R; END EVERY 1s; END EVERY 10s COUNT 1;");
		Assertions.assertTrue(output().contains("Nested REPEAT is not supported"), output());
		Assertions.assertTrue(RepeatProbe.calls().isEmpty());
		line("REPEAT BEGIN REPEAT EVERY 1s; END EVERY 1s;");
		Assertions.assertEquals(2, occurrences(output(), "Nested REPEAT is not supported"), output());
	}

	@Test
	void tabsAndLineBreaksAfterSelectAreStillQueries() throws Exception {
		line("REPEAT BEGIN SELECT\tREC('tab') AS R; SELECT\nREC('newline') AS R; END EVERY 1s COUNT 1;");
		Assertions.assertEquals(List.of("tab", "newline"), RepeatProbe.calls(), output());
	}

	// ---- @script and LIB RUN ----

	@Test
	void anAtScriptIsOneIterationRunningAllItsStatementsInOrder() throws Exception {
		lib("monitor.sql", "SELECT REC('open') AS OPEN_ORDERS\nFROM CUSTOMER;\n\nSELECT REC('failed') AS FAILED_INVOICES;\n\nSELECT REC('last') AS LAST_ORDER;\n");
		line("REPEAT @monitor.sql EVERY 10s COUNT 2;");
		Assertions.assertEquals(List.of("open", "failed", "last", "open", "failed", "last"), RepeatProbe.calls(), output());
		Assertions.assertEquals(1, RepeatProbe.maxRunning());
		Assertions.assertEquals(List.of(10_000L), waits);
		Assertions.assertEquals(2, occurrences(output(), "monitor.sql: SUCCESS"), "each iteration is a normal run of the script: " + output());
	}

	@Test
	void aLibRunScriptIsOneIteration() throws Exception {
		lib("ops/monitor.sql", "SELECT REC('1') AS R;\nSELECT REC('2') AS R;\n");
		line("REPEAT LIB RUN ops/monitor.sql EVERY 10s FOR 30s;");
		Assertions.assertEquals(List.of("1", "2", "1", "2", "1", "2"), RepeatProbe.calls(), "starts at 0, 10 and 20 seconds: " + output());
		Assertions.assertTrue(output().contains("REPEAT finished: FOR 30s elapsed after 3 iterations."), output());
	}

	@Test
	void scriptArgumentsArePassedEachIteration() throws Exception {
		lib("arg.sql", "-- @params: label\nSELECT REC(${label}) AS R;\n");
		line("REPEAT LIB RUN arg.sql label='hello' EVERY 1s COUNT 2;");
		Assertions.assertEquals(List.of("hello", "hello"), RepeatProbe.calls(), output());
	}

	@Test
	void aScriptThatChangesDataIsRefusedBeforeAnythingRuns() throws Exception {
		lib("bad.sql", "SELECT REC('1') AS R;\nUPDATE CUSTOMER SET NAME = 'x';\n");
		line("REPEAT @bad.sql EVERY 1s COUNT 1;");
		Assertions.assertTrue(output().contains("REPEAT not started: bad.sql contains a statement that cannot be repeated"), output());
		line("REPEAT LIB RUN bad.sql EVERY 1s COUNT 1;");
		Assertions.assertEquals(2, occurrences(output(), "bad.sql contains a statement that cannot be repeated"), output());
		Assertions.assertTrue(RepeatProbe.calls().isEmpty());
		Assertions.assertEquals("Alice", sql("SELECT NAME FROM CUSTOMER"));
	}

	@Test
	void aStatementOfANestedScriptIsCheckedWhenItRuns() throws Exception {
		lib("inner.sql", "DELETE FROM CUSTOMER;\n");
		lib("outer.sql", "SELECT REC('o1') AS R;\n@inner.sql;\nSELECT REC('o2') AS R;\n");
		line("REPEAT @outer.sql EVERY 1s COUNT 3;");
		Assertions.assertEquals(List.of("o1"), RepeatProbe.calls(), "the script stops at the refused statement: " + output());
		Assertions.assertEquals(1L, count("CUSTOMER"), "nothing refused reaches the database");
		Assertions.assertTrue(output().contains("'DELETE FROM CUSTOMER' cannot be repeated"), output());
		Assertions.assertTrue(output().contains("REPEAT stopped: iteration 1 failed"), output());
		assertOperational();
	}

	@Test
	void aRepeatInsideARepeatedScriptIsRefused() throws Exception {
		lib("loop.sql", "SELECT REC('1') AS R;\nREPEAT EVERY 1s;\n");
		line("REPEAT @loop.sql EVERY 1s COUNT 2;");
		Assertions.assertTrue(output().contains("Nested REPEAT is not supported"), output());
		Assertions.assertTrue(RepeatProbe.calls().isEmpty(), "refused before the start");

		lib("inner_loop.sql", "REPEAT EVERY 1s;\n");
		lib("caller.sql", "SELECT REC('c') AS R;\n@inner_loop.sql;\n");
		clearOutput();
		line("REPEAT @caller.sql EVERY 1s COUNT 2;");
		Assertions.assertTrue(output().contains("Nested REPEAT is not supported"), "found when it runs: " + output());
		Assertions.assertEquals(List.of("c"), RepeatProbe.calls());
		assertOperational();
	}

	@Test
	void aRepeatedScriptStopsAtItsFirstErrorWhateverItsCaller() throws Exception {
		lib("broken.sql", "SELECT REC('1') AS R;\nSELECT * FROM NO_SUCH_TABLE;\nSELECT REC('3') AS R;\n");
		lib("host.sql", "ON ERROR CONTINUE;\nREPEAT @broken.sql EVERY 1s COUNT 3;\nSELECT REC('host continues') AS R;\n");
		run("@host.sql;");
		Assertions.assertEquals(List.of("1", "host continues"), RepeatProbe.calls(), "the repeated script stopped, the host's CONTINUE still applies to it: " + output());
		Assertions.assertTrue(output().contains("stopped by REPEAT at its first error at statement 2"), output());
	}

	@Test
	void aMissingScriptReportsTheNormalError() throws Exception {
		line("REPEAT @missing.sql EVERY 1s COUNT 2;");
		Assertions.assertTrue(output().contains("missing.sql"), output());
		Assertions.assertTrue(output().contains("REPEAT stopped: iteration 1 failed"), output());
		Assertions.assertTrue(waits.isEmpty());
	}

	// ---- REPEAT inside scripts and the Editor ----

	@Test
	void aRepeatBlockWrittenInAScriptIsOneStatement() throws Exception {
		lib("watch.sql", "SELECT REC('before') AS R;\nREPEAT\nBEGIN\n    SELECT REC('a') AS R;\n    SELECT REC('b') AS R;\nEND\nEVERY 1s\nCOUNT 2;\nSELECT REC('after') AS R;\n");
		Assertions.assertEquals(ScriptStatus.SUCCESS, run("@watch.sql;").getStatus(), output());
		Assertions.assertEquals(List.of("before", "a", "b", "a", "b", "after"), RepeatProbe.calls(), output());
		Assertions.assertTrue(output().contains("watch.sql: SUCCESS (3 statements, 0 failed)"), output());
	}

	@Test
	void anEditorRunReadsTheBlockTooAndNeedsABound() throws Exception {
		lib("ed.sql", "REPEAT\nBEGIN\n    SELECT REC('e') AS R;\nEND\nEVERY 1s\nCOUNT 2;\n");
		ScriptRunContext context = new ScriptRunContext(db, interpreter, null, null, settings, db.getPlatform().getId());
		RunOutcome outcome = new ScriptRunCoordinator().run("ed.sql", context, "");
		Assertions.assertEquals(ScriptStatus.SUCCESS, outcome.scriptStatus(), outcome.capturedOutput());
		Assertions.assertEquals(List.of("e", "e"), RepeatProbe.calls());
		Assertions.assertTrue(outcome.capturedOutput().contains("=== Repeat #2"), outcome.capturedOutput());

		lib("endless.sql", "REPEAT BEGIN SELECT REC('never') AS R; END EVERY 1s;\n");
		RunOutcome endless = new ScriptRunCoordinator().run("endless.sql", context, "");
		Assertions.assertTrue(endless.capturedOutput().contains("In the BroadSQL Editor, REPEAT needs FOR <duration> or COUNT <iterations>"),
				endless.capturedOutput());
		Assertions.assertEquals(List.of("e", "e"), RepeatProbe.calls());
	}

	@Test
	void aScriptWithAnUnterminatedBlockIsRefusedBeforeRunning() throws Exception {
		lib("open.sql", "SELECT REC('x') AS R;\nREPEAT BEGIN\n  SELECT REC('y') AS R;\n");
		Assertions.assertEquals(ScriptStatus.FAILED, run("@open.sql;").getStatus());
		Assertions.assertTrue(output().contains("Unterminated REPEAT BEGIN block (END missing) starting at line 2"), output());
		Assertions.assertTrue(RepeatProbe.calls().isEmpty());
	}

	@Test
	void syntaxErrorsAreReportedWithTheUsage() {
		line("REPEAT EVERY 10 s;");
		Assertions.assertTrue(output().contains("without a space"), output());
		line("REPEAT EVERY 10s FOR 1h COUNT 100;");
		Assertions.assertTrue(output().contains("FOR and COUNT cannot be combined"), output());
		line("REPEAT;");
		Assertions.assertTrue(output().contains("REPEAT needs EVERY <interval>"), output());
		Assertions.assertTrue(RepeatProbe.calls().isEmpty());
	}

	// ---- monitoring output ----

	@Test
	void csvOutputIsAppendedWithATimestampAndTheConsoleStaysActive() throws Exception {
		line("REPEAT BEGIN SELECT NAME, COUNTRY FROM CUSTOMER ORDER BY ID; END EVERY 10s COUNT 2 TO monitor.csv AS CSV;");
		Path file = out("monitor.csv");
		String expected = "﻿TIMESTAMP;NAME;COUNTRY\r\n2026-10-07 21:10:00;Alice;FR\r\n2026-10-07 21:10:10;Alice;FR\r\n";
		Assertions.assertEquals(expected, Files.readString(file, StandardCharsets.UTF_8), output());
		Assertions.assertEquals(2, occurrences(output(), "Alice"), "the console still shows each result: " + output());
		Assertions.assertTrue(output().contains("Each iteration is appended to " + file.toAbsolutePath().normalize() + " (CSV)."), output());

		// a second REPEAT with the same columns appends: nothing earlier is overwritten
		line("INSERT INTO CUSTOMER VALUES (2, 'Bob', 'DE');");
		line("REPEAT BEGIN SELECT NAME, COUNTRY FROM CUSTOMER ORDER BY ID; END EVERY 10s COUNT 1 TO monitor.csv;");
		Assertions.assertEquals(expected + "2026-10-07 21:10:10;Alice;FR\r\n2026-10-07 21:10:10;Bob;DE\r\n", Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	void csvOutputRefusesAFileWithOtherColumns() throws Exception {
		Path file = out("other.csv");
		Files.writeString(file, "﻿A;B\r\n1;2\r\n", StandardCharsets.UTF_8);
		line("REPEAT BEGIN SELECT NAME FROM CUSTOMER; END EVERY 1s COUNT 3 TO other.csv;");
		Assertions.assertTrue(output().contains("already exists with other columns"), output());
		Assertions.assertTrue(output().contains("REPEAT stopped: iteration 1 could not be recorded"), output());
		Assertions.assertEquals("﻿A;B\r\n1;2\r\n", Files.readString(file, StandardCharsets.UTF_8), "untouched");
		Assertions.assertTrue(waits.isEmpty());
	}

	@Test
	void aTimestampColumnOfTheQueryIsKept() throws Exception {
		line("REPEAT BEGIN SELECT NAME, TIMESTAMP '2020-01-01 00:00:00' AS \"TIMESTAMP\" FROM CUSTOMER; END EVERY 1s COUNT 1 TO ts AS CSV;");
		Assertions.assertTrue(Files.readString(out("ts.csv"), StandardCharsets.UTF_8).startsWith("﻿REPEAT_TIMESTAMP;NAME;TIMESTAMP\r\n"), output());
	}

	@Test
	void bothTimestampNamesTakenGivesRepeatTimestamp2AndKeepsTheQueryColumns() throws Exception {
		line("REPEAT BEGIN SELECT NAME, 'a' AS \"TIMESTAMP\", 'b' AS REPEAT_TIMESTAMP FROM CUSTOMER; END EVERY 1s COUNT 1 TO ts2 AS CSV;");
		Assertions.assertEquals("﻿REPEAT_TIMESTAMP_2;NAME;TIMESTAMP;REPEAT_TIMESTAMP\r\n2026-10-07 21:10:00;Alice;a;b\r\n",
				Files.readString(out("ts2.csv"), StandardCharsets.UTF_8), output());
	}

	@Test
	void jsonOutputIsJsonLines() throws Exception {
		line("SELECT ID, NAME FROM CUSTOMER;");
		line("REPEAT EVERY 1m COUNT 2 TO monitor AS JSON;");
		List<String> lines = Files.readAllLines(out("monitor.json"), StandardCharsets.UTF_8);
		Assertions.assertEquals(List.of("{ \"TIMESTAMP\": \"2026-10-07T21:10:00\", \"ID\": 1, \"NAME\": \"Alice\" }",
				"{ \"TIMESTAMP\": \"2026-10-07T21:11:00\", \"ID\": 1, \"NAME\": \"Alice\" }"), lines, output());
	}

	@Test
	void structuredOutputNeedsOneResultPerIteration() throws Exception {
		line("REPEAT BEGIN SELECT COUNT(*) FROM CUSTOMER; SELECT NAME FROM CUSTOMER; END EVERY 1s COUNT 1 TO two.csv;");
		Assertions.assertTrue(output().contains("the target runs 2 queries, and AS CSV records exactly one result per iteration"), output());
		Assertions.assertFalse(Files.exists(out("two.csv")));
		lib("two.sql", "SELECT REC('1') AS R;\nSELECT REC('2') AS R;\n");
		line("REPEAT LIB RUN two.sql EVERY 1s COUNT 1 TO two.json;");
		Assertions.assertTrue(output().contains("the target runs 2 queries, and AS JSON records exactly one"), output());
		Assertions.assertTrue(RepeatProbe.calls().isEmpty());

		// known only at run time: a nested script
		lib("nested_two.sql", "@two.sql;\n");
		line("REPEAT @nested_two.sql EVERY 1s COUNT 3 TO nested.csv;");
		Assertions.assertTrue(output().contains("Iteration 1 produced 2 tabular results"), output());
		Assertions.assertFalse(Files.exists(out("nested.csv")));
		Assertions.assertEquals(List.of("1", "2"), RepeatProbe.calls(), "one iteration ran, nothing was written, no further iteration");
	}

	@Test
	void textOutputRecordsSeveralResultsPerIteration() throws Exception {
		line("REPEAT BEGIN SELECT COUNT(*) AS N FROM CUSTOMER; SELECT NAME FROM CUSTOMER; END EVERY 30s COUNT 2 TO monitor.txt;");
		String text = Files.readString(out("monitor.txt"), StandardCharsets.UTF_8);
		Assertions.assertEquals("=== Repeat #1 at 2026-10-07 21:10:00 ===\r\n[1/2] SELECT COUNT(*) AS N FROM CUSTOMER\r\nN\r\n1\r\n\r\n"
				+ "[2/2] SELECT NAME FROM CUSTOMER\r\nNAME\r\nAlice\r\n\r\n"
				+ "=== Repeat #2 at 2026-10-07 21:10:30 ===\r\n[1/2] SELECT COUNT(*) AS N FROM CUSTOMER\r\nN\r\n1\r\n\r\n"
				+ "[2/2] SELECT NAME FROM CUSTOMER\r\nNAME\r\nAlice\r\n\r\n", text);
	}

	@Test
	void aResultCutAtMaxRowsOnScreenIsNotRecorded() throws Exception {
		settings.setMaxRowsOnScreen(1);
		db.setMaxRowsOnScreen(1);
		line("INSERT INTO CUSTOMER VALUES (2, 'Bob', 'DE');");
		line("REPEAT BEGIN SELECT NAME FROM CUSTOMER; END EVERY 1s COUNT 2 TO cut.csv;");
		Assertions.assertTrue(output().contains("REPEAT ... TO writes complete results only"), output());
		Assertions.assertFalse(Files.exists(out("cut.csv")));
	}

	// ---- CTRL+C ----

	/** Runs {@code text} as a typed line on a background thread, as the terminal does. */
	private Thread start(String text, AtomicReference<Throwable> failure) {
		Thread runner = new Thread(() -> {
			try {
				line(text);
			} catch (Throwable t) {
				failure.set(t);
			}
		}, "repeat-test-runner");
		runner.start();
		return runner;
	}

	private void waitForOutput(String fragment) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 30_000;
		while (!output().contains(fragment)) {
			Assertions.assertTrue(System.currentTimeMillis() < deadline, "never saw '" + fragment + "': " + output());
			Thread.sleep(20);
		}
	}

	private void finish(Thread runner, AtomicReference<Throwable> failure) throws InterruptedException {
		runner.join(30_000);
		Assertions.assertFalse(runner.isAlive(), "REPEAT must end after CTRL+C: " + output());
		Assertions.assertNull(failure.get());
	}

	@Test
	void ctrlCDuringTheWaitStopsTheRepeatAndBroadSqlStaysUsable() throws Exception {
		realTime();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = start("REPEAT BEGIN SELECT REC('w') AS R; END EVERY 1h; SELECT REC('rest of the line') AS R;", failure);
		waitForOutput("Next execution in 1h...");
		interpreter.handleInterrupt();
		finish(runner, failure);
		Assertions.assertTrue(output().contains("REPEAT cancelled by user after 1 complete iteration."), output());
		Assertions.assertEquals(List.of("w"), RepeatProbe.calls(), "the rest of the typed line is not executed, as for any CTRL+C");
		CommandCancellation.resetRun();
		assertOperational();
	}

	@Test
	void ctrlCDuringAQueryCancelsItAndTheRepeat() throws Exception {
		realTime();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = start("REPEAT BEGIN SELECT REC('q') AS R; " + LONG_QUERY + "; END EVERY 1s;", failure);
		pressCtrlCWhenAStatementIsRunning();
		finish(runner, failure);
		Assertions.assertTrue(output().contains("REPEAT cancelled by user after 0 complete iterations."), output());
		Assertions.assertEquals(List.of("q"), RepeatProbe.calls());
		CommandCancellation.resetRun();
		assertOperational();
	}

	@Test
	void ctrlCStopsARepeatedScriptDuringTheWait() throws Exception {
		realTime();
		lib("m.sql", "SELECT REC('m1') AS R;\nSELECT REC('m2') AS R;\n");
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = start("REPEAT LIB RUN m.sql EVERY 1h;", failure);
		waitForOutput("Next execution in 1h...");
		interpreter.handleInterrupt();
		finish(runner, failure);
		Assertions.assertEquals(List.of("m1", "m2"), RepeatProbe.calls());
		Assertions.assertTrue(output().contains("REPEAT cancelled by user after 1 complete iteration."), output());
		CommandCancellation.resetRun();
		assertOperational();
	}

	@Test
	void ctrlCStopsARepeatStartedByAScriptAndTheScript() throws Exception {
		realTime();
		lib("host.sql", "REPEAT BEGIN SELECT REC('h') AS R; END EVERY 1h;\nSELECT REC('after') AS R;\n");
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread runner = start("@host.sql;", failure);
		waitForOutput("Next execution in 1h...");
		interpreter.handleInterrupt();
		finish(runner, failure);
		Assertions.assertEquals(List.of("h"), RepeatProbe.calls(), "the script is cancelled too: " + output());
		Assertions.assertEquals(ScriptStatus.CANCELLED, lastRun().getStatus());
		CommandCancellation.resetRun();
		assertOperational();
	}

	@Test
	void ctrlCAtTheIdlePromptStillDoesNothing() {
		interpreter.handleInterrupt();
		Assertions.assertFalse(CommandCancellation.isRunCancelled());
	}

	/** CTRL+C during the statement: what the signal handler does, once the JDBC statement is running. */
	private void pressCtrlCWhenAStatementIsRunning() throws Exception {
		java.lang.reflect.Field field = DatabaseConnection.class.getDeclaredField("currentStatement");
		field.setAccessible(true);
		long deadline = System.currentTimeMillis() + 20_000;
		while (System.currentTimeMillis() < deadline) {
			Statement running = (Statement) field.get(db);
			if (running != null && output().contains("[2/2]")) {
				Thread.sleep(200);
				interpreter.handleInterrupt();
				return;
			}
			Thread.sleep(10);
		}
		Assertions.fail("the long statement never started: " + output());
	}
}
