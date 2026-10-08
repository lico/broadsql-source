package com.upandcoding.broadsql.controller.shell.scripts;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * GitHub #196: the one exception of the statement model, a statement starting with {@code REPEAT BEGIN} keeps its
 * inner {@code ;} up to the inner statement starting with {@code END}. Everything else splits exactly as before,
 * {@code BEGIN}/{@code END} included.
 */
class TestStatementSplitterRepeatBlock {

	@Test
	void aRepeatBlockIsOneStatementWithItsInnerSemicolons() {
		List<String> result = StatementSplitter.split("REPEAT BEGIN SELECT 1; SELECT 2; SELECT 3; END EVERY 10s;");
		Assertions.assertEquals(List.of("REPEAT BEGIN SELECT 1; SELECT 2; SELECT 3; END EVERY 10s"), result);
	}

	@Test
	void aMultiLineBlockBetweenOtherStatements() {
		String script = "SELECT 0;\nREPEAT\nBEGIN\n    SELECT COUNT(*) FROM ORDERS;\n    SELECT COUNT(*) FROM INVOICES;\nEND\nEVERY 10s\nCOUNT 3;\nSELECT 4;\n";
		StatementSplitter.Result result = StatementSplitter.splitSpans(script);
		Assertions.assertTrue(result.isTerminated());
		List<StatementSplitter.Span> spans = result.getSpans();
		Assertions.assertEquals(3, spans.size());
		Assertions.assertEquals("SELECT 0", spans.get(0).getText());
		Assertions.assertEquals("REPEAT BEGIN     SELECT COUNT(*) FROM ORDERS;     SELECT COUNT(*) FROM INVOICES; END EVERY 10s COUNT 3", spans.get(1).getText());
		Assertions.assertEquals("SELECT 4", spans.get(2).getText());
		Assertions.assertEquals(2, ScriptExecutor.startLine(script, spans.get(1)), "the block starts on its REPEAT line");
	}

	@Test
	void caseCommentsAndQuotesInsideTheBlock() {
		List<String> result = StatementSplitter.split("repeat -- monitor\nbegin select 'a;END;b'; /* ; */ select 2; end every 1s; SELECT 9;");
		Assertions.assertEquals(List.of("repeat  begin select 'a;END;b';   select 2; end every 1s", "SELECT 9"), result);
	}

	@Test
	void aBlockWithoutTheFinalSemicolonIsComplete() {
		// what the interactive loop passes after stripping the line's final ;
		StatementSplitter.Result result = StatementSplitter.splitSpans("REPEAT BEGIN SELECT 1; END EVERY 5s ");
		Assertions.assertTrue(result.isTerminated());
		Assertions.assertFalse(result.isRepeatBlockOpen());
		Assertions.assertEquals(List.of("REPEAT BEGIN SELECT 1; END EVERY 5s"), StatementSplitter.split("REPEAT BEGIN SELECT 1; END EVERY 5s "));
	}

	@Test
	void anOpenBlockIsReportedWithItsStartLine() {
		StatementSplitter.Result result = StatementSplitter.splitSpans("SELECT 1;\n\n  REPEAT BEGIN\n  SELECT 2;\n  SELECT 3;\n");
		Assertions.assertFalse(result.isTerminated());
		Assertions.assertTrue(result.isRepeatBlockOpen());
		Assertions.assertEquals(3, result.getUnterminatedLine());
		Assertions.assertEquals(3, result.getUnterminatedColumn());
		Assertions.assertTrue(result.describeUnterminated().contains("REPEAT BEGIN block"), result.describeUnterminated());
		Assertions.assertEquals("SELECT 1", result.getSpans().get(0).getText(), "the statements before it are still returned");
	}

	@Test
	void isRepeatBlockOpenFollowsTheTypingOfABlock() {
		Assertions.assertTrue(StatementSplitter.isRepeatBlockOpen("REPEAT BEGIN"));
		Assertions.assertTrue(StatementSplitter.isRepeatBlockOpen("REPEAT BEGIN SELECT 1"));
		Assertions.assertTrue(StatementSplitter.isRepeatBlockOpen("REPEAT BEGIN SELECT 1; SELECT 2"));
		Assertions.assertFalse(StatementSplitter.isRepeatBlockOpen("REPEAT BEGIN SELECT 1; END"), "END alone closes the block (the command then wants EVERY)");
		Assertions.assertFalse(StatementSplitter.isRepeatBlockOpen("REPEAT BEGIN SELECT 1; END EVERY 10s"));
		Assertions.assertFalse(StatementSplitter.isRepeatBlockOpen("REPEAT BEGIN END EVERY 1s"), "an empty block closes at once");
		Assertions.assertFalse(StatementSplitter.isRepeatBlockOpen("REPEAT EVERY 10s"));
		Assertions.assertFalse(StatementSplitter.isRepeatBlockOpen("SELECT 1"));
		Assertions.assertTrue(StatementSplitter.isRepeatBlockOpen("SELECT 0; REPEAT BEGIN SELECT 1"), "a block after another statement on the same line");
	}

	@Test
	void endAloneClosesTheBlock() {
		// "REPEAT BEGIN SELECT 1; END" with no EVERY: complete for the splitter, refused by the command
		Assertions.assertFalse(StatementSplitter.isRepeatBlockOpen("REPEAT BEGIN SELECT 1; END "));
		Assertions.assertEquals(List.of("REPEAT BEGIN SELECT 1; END", "SELECT 2"), StatementSplitter.split("REPEAT BEGIN SELECT 1; END; SELECT 2;"));
	}

	@Test
	void aNestedRepeatBlockStaysInsideTheOuterOne() {
		List<String> result = StatementSplitter.split("REPEAT BEGIN SELECT 1; REPEAT BEGIN SELECT 2; END EVERY 1s; END EVERY 10s; SELECT 3;");
		Assertions.assertEquals(List.of("REPEAT BEGIN SELECT 1; REPEAT BEGIN SELECT 2; END EVERY 1s; END EVERY 10s", "SELECT 3"), result);
	}

	// ---- unchanged behavior: BEGIN/END outside a REPEAT block ----

	@Test
	void proceduralSqlStillSplitsAtEveryInnerSemicolon() {
		List<String> result = StatementSplitter.split("BEGIN\n  UPDATE t SET a = 1;\n  UPDATE t SET b = 2;\nEND;");
		Assertions.assertEquals(List.of("BEGIN   UPDATE t SET a = 1", "UPDATE t SET b = 2", "END"), result);
		Assertions.assertTrue(StatementSplitter.splitSpans("BEGIN UPDATE t SET a = 1;").isTerminated());
	}

	@Test
	void repeatOrBeginAppearingLaterInAStatementChangesNothing() {
		Assertions.assertEquals(List.of("SELECT REPEAT('x', 3) FROM T", "SELECT 2"), StatementSplitter.split("SELECT REPEAT('x', 3) FROM T; SELECT 2;"));
		Assertions.assertEquals(List.of("SELECT \"BEGIN\", \"END\" FROM EVENTS", "SELECT 2"),
				StatementSplitter.split("SELECT \"BEGIN\", \"END\" FROM EVENTS; SELECT 2;"));
		Assertions.assertEquals(List.of("CREATE TRIGGER t BEGIN INSERT INTO a VALUES (1)", "END"),
				StatementSplitter.split("CREATE TRIGGER t BEGIN INSERT INTO a VALUES (1); END;"));
	}

	@Test
	void aRepeatThatIsNotABlockSplitsNormally() {
		Assertions.assertEquals(List.of("REPEAT EVERY 10s", "SELECT 1"), StatementSplitter.split("REPEAT EVERY 10s; SELECT 1;"));
		Assertions.assertEquals(List.of("REPEATBEGIN SELECT 1", "END EVERY 1s"), StatementSplitter.split("REPEATBEGIN SELECT 1; END EVERY 1s;"));
		Assertions.assertEquals(List.of("REPEAT BEGINNING", "SELECT 1"), StatementSplitter.split("REPEAT BEGINNING; SELECT 1;"));
	}

	@Test
	void anUnterminatedQuoteIsStillReportedFirst() {
		StatementSplitter.Result result = StatementSplitter.splitSpans("REPEAT BEGIN SELECT 'x; END EVERY 1s;");
		Assertions.assertEquals("single quote", result.getUnterminated());
		Assertions.assertFalse(result.isRepeatBlockOpen());
	}
}
