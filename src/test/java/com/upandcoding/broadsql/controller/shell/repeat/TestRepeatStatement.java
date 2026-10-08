package com.upandcoding.broadsql.controller.shell.repeat;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.repeat.RepeatStatement.OutputFormat;
import com.upandcoding.broadsql.controller.shell.repeat.RepeatStatement.TargetKind;

/**
 * GitHub #196: the {@code REPEAT} grammar, {@code REPEAT [<target>] EVERY <interval> [FOR <duration> | COUNT
 * <iterations>] [TO <file> [AS CSV|JSON|TEXT]]}, in its one fixed order. {@link RepeatStatement#parse} receives
 * what follows the keyword.
 */
class TestRepeatStatement {

	private static RepeatStatement parse(String afterKeyword) throws BroadSQLException {
		return RepeatStatement.parse(afterKeyword);
	}

	private static String error(String afterKeyword) {
		return Assertions.assertThrows(BroadSQLException.class, () -> parse(afterKeyword)).getMessage();
	}

	@Test
	void everyAloneRepeatsTheLastQuery() throws BroadSQLException {
		RepeatStatement s = parse("EVERY 10s");
		Assertions.assertEquals(TargetKind.LAST_QUERY, s.getTargetKind());
		Assertions.assertEquals(10, s.getEvery().seconds());
		Assertions.assertNull(s.getForDuration());
		Assertions.assertNull(s.getCount());
		Assertions.assertNull(s.getOutputFile());
		Assertions.assertFalse(s.isBounded());
	}

	@Test
	void slashIsTheSameAsNoTarget() throws BroadSQLException {
		RepeatStatement s = parse("/ EVERY 10s");
		Assertions.assertEquals(TargetKind.LAST_QUERY, s.getTargetKind());
		Assertions.assertEquals(parse("EVERY 10s").getEvery().seconds(), s.getEvery().seconds());
	}

	@Test
	void anAtScriptTargetIsKeptAsWrittenWithItsArguments() throws BroadSQLException {
		RepeatStatement s = parse("@monitor.sql EVERY 10s");
		Assertions.assertEquals(TargetKind.SCRIPT, s.getTargetKind());
		Assertions.assertEquals("@monitor.sql", s.getTargetStatement());
		Assertions.assertEquals("monitor.sql", s.getScriptReference());

		RepeatStatement args = parse("@\"my scripts/monitor.sql\" region='EU' EVERY 1m");
		Assertions.assertEquals("@\"my scripts/monitor.sql\" region='EU'", args.getTargetStatement());
		Assertions.assertEquals("my scripts/monitor.sql", args.getScriptReference());
	}

	@Test
	void aLibRunTargetIsKeptAsWritten() throws BroadSQLException {
		RepeatStatement s = parse("LIB RUN monitor EVERY 10s FOR 30m");
		Assertions.assertEquals(TargetKind.LIBRARY_SCRIPT, s.getTargetKind());
		Assertions.assertEquals("LIB RUN monitor", s.getTargetStatement());
		Assertions.assertEquals("monitor", s.getScriptReference());
		Assertions.assertEquals(1800, s.getForDuration().seconds());
		Assertions.assertTrue(s.isBounded());
		Assertions.assertEquals(TargetKind.LIBRARY_SCRIPT, parse("lib   run monitor every 10s").getTargetKind());
	}

	@Test
	void aScriptNamedEveryIsNotTakenForTheClause() throws BroadSQLException {
		RepeatStatement s = parse("LIB RUN every EVERY 10s");
		Assertions.assertEquals("every", s.getScriptReference());
		Assertions.assertEquals(10, s.getEvery().seconds());
		Assertions.assertEquals("@every", parse("@every EVERY 2s COUNT 1").getTargetStatement());
	}

	@Test
	void aBlockKeepsItsStatementsInOrder() throws BroadSQLException {
		RepeatStatement s = parse("BEGIN SELECT COUNT(*) FROM ORDERS; SELECT COUNT(*) FROM INVOICES; SELECT COUNT(*) FROM PAYMENTS WHERE STATUS = 'FAILED'; END EVERY 10s");
		Assertions.assertEquals(TargetKind.BLOCK, s.getTargetKind());
		Assertions.assertEquals(List.of("SELECT COUNT(*) FROM ORDERS", "SELECT COUNT(*) FROM INVOICES", "SELECT COUNT(*) FROM PAYMENTS WHERE STATUS = 'FAILED'"),
				s.getBlockStatements());
	}

	@Test
	void aMultiLineBlockWithCommentsAndQuotedSemicolons() throws BroadSQLException {
		RepeatStatement s = parse("BEGIN\n    -- first\n    SELECT 'a;b' AS X\n    FROM T;\n    SELECT 2; /* two; */\nEND\nEVERY 10s\nCOUNT 3");
		Assertions.assertEquals(List.of("SELECT 'a;b' AS X     FROM T", "SELECT 2"), s.getBlockStatements());
		Assertions.assertEquals(3, s.getCount());
	}

	@Test
	void theFullGrammar() throws BroadSQLException {
		RepeatStatement s = parse("BEGIN SELECT STATUS, COUNT(*) AS CNT FROM PROCESS_QUEUE GROUP BY STATUS; END EVERY 10s FOR 1h TO queue-monitor.csv AS CSV");
		Assertions.assertEquals(3600, s.getForDuration().seconds());
		Assertions.assertEquals("queue-monitor.csv", s.getOutputFile());
		Assertions.assertEquals(OutputFormat.CSV, s.getOutputFormat());
		RepeatStatement quoted = parse("EVERY 5s COUNT 2 TO \"my monitor.log\" as text");
		Assertions.assertEquals("my monitor.log", quoted.getOutputFile());
		Assertions.assertEquals(OutputFormat.TEXT, quoted.getOutputFormat());
	}

	@ParameterizedTest
	@CsvSource({ "a.csv,CSV", "a.CSV,CSV", "b.json,JSON", "c.txt,TEXT" })
	void withoutAsTheExtensionGivesTheFormat(String file, OutputFormat format) throws BroadSQLException {
		Assertions.assertEquals(format, parse("EVERY 1s TO " + file).getOutputFormat());
	}

	@Test
	void aFileWithoutAKnownExtensionNeedsAs() {
		Assertions.assertTrue(error("EVERY 1s TO monitor").contains("AS CSV"));
		Assertions.assertTrue(error("EVERY 1s TO monitor.xlsx").contains("AS CSV"));
	}

	@Test
	void everyIsMandatory() {
		Assertions.assertTrue(error("").contains("EVERY"));
		Assertions.assertTrue(error("/").contains("EVERY <interval> is mandatory"));
		Assertions.assertTrue(error("@monitor.sql").contains("EVERY <interval> is mandatory"));
		Assertions.assertTrue(error("BEGIN SELECT 1; END").contains("END must be followed by EVERY"));
		Assertions.assertTrue(error("BEGIN SELECT 1; END COUNT 3").contains("END must be followed by EVERY"));
	}

	@Test
	void theOrderIsFixed() {
		Assertions.assertTrue(error("COUNT 3 EVERY 1s").contains(RepeatStatement.USAGE), "COUNT before EVERY is not a target");
		Assertions.assertTrue(error("FOR 1m").contains("comes first"));
		Assertions.assertTrue(error("EVERY 10s @monitor.sql").contains("Unexpected '@monitor.sql'"), "the target comes before EVERY");
		Assertions.assertTrue(error("EVERY 1s TO a.csv COUNT 3").contains("must come before TO"));
		Assertions.assertTrue(error("EVERY 1s AS CSV").contains("AS needs TO"));
		Assertions.assertTrue(error("EVERY 1s EVERY 2s").contains("only once"));
	}

	@Test
	void forAndCountAreMutuallyExclusive() {
		Assertions.assertTrue(error("EVERY 10s FOR 1h COUNT 100").contains("FOR and COUNT cannot be combined"));
		Assertions.assertTrue(error("EVERY 10s COUNT 100 FOR 1h").contains("FOR and COUNT cannot be combined"));
		Assertions.assertTrue(error("EVERY 10s FOR 1h FOR 2h").contains("FOR can be given only once"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-1", "abc", "1.5", "99999999999" })
	void countIsAPositiveWholeNumber(String count) {
		Assertions.assertTrue(error("EVERY 1s COUNT " + count).contains("COUNT"));
	}

	@Test
	void durationErrorsComeFromTheDurationRule() {
		Assertions.assertTrue(error("EVERY 10 s").contains("without a space"));
		Assertions.assertTrue(error("EVERY 500ms").contains("milliseconds"));
		Assertions.assertTrue(error("EVERY").contains("EVERY needs an interval"));
		Assertions.assertTrue(error("EVERY 10s FOR 1h30m").contains("90m"));
		Assertions.assertTrue(error("EVERY 10s FOR").contains("FOR needs a duration"));
	}

	@Test
	void unknownTargetsAreRefused() {
		Assertions.assertTrue(error("SELECT 1 EVERY 1s").contains("REPEAT cannot repeat 'SELECT 1'"));
		Assertions.assertTrue(error("/ QA EVERY 1s").contains("REPEAT / takes nothing else"));
		Assertions.assertTrue(error("@ EVERY 1s").contains("script name"));
		Assertions.assertTrue(error("LIB RUN EVERY 1s").contains("Scripts Library script"));
	}

	@Test
	void blockErrors() {
		Assertions.assertTrue(error("BEGIN SELECT 1;").contains("no END"));
		Assertions.assertTrue(error("BEGIN END EVERY 1s").contains("empty"));
		Assertions.assertTrue(error("BEGIN SELECT 'x; END EVERY 1s").contains("cannot be read"));
	}

	@Test
	void aNestedBlockIsKeptAsOneStatementForTheNestedRepeatCheck() throws BroadSQLException {
		RepeatStatement s = parse("BEGIN SELECT 1; REPEAT BEGIN SELECT 2; END EVERY 1s; END EVERY 10s");
		Assertions.assertEquals(List.of("SELECT 1", "REPEAT BEGIN SELECT 2; END EVERY 1s"), s.getBlockStatements());
	}
}
