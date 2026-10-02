package com.upandcoding.broadsql.controller.shell.scripts;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * SPRINT XT02B, section 15 - the shared, quote/comment-aware statement splitter behind both
 * {@link ScriptExecutor} (script files) and {@code CommandInterpreter}'s interactive multi-statement
 * support.
 */
class TestStatementSplitter {

	@Test
	void splitsTwoSimpleStatements() {
		List<String> result = StatementSplitter.split("SELECT 1; SELECT 2;");
		Assertions.assertEquals(List.of("SELECT 1", "SELECT 2"), result);
	}

	@Test
	void aSingleStatementWithNoTrailingSemicolonIsStillReturned() {
		List<String> result = StatementSplitter.split("SELECT 1");
		Assertions.assertEquals(List.of("SELECT 1"), result);
	}

	@Test
	void aSemicolonInsideASingleQuotedStringIsNotAStatementBoundary() {
		List<String> result = StatementSplitter.split("SELECT 'a;b';");
		Assertions.assertEquals(List.of("SELECT 'a;b'"), result);
	}

	@Test
	void aSemicolonInsideADoubleQuotedIdentifierIsNotAStatementBoundary() {
		List<String> result = StatementSplitter.split("SELECT \"a;b\" FROM t;");
		Assertions.assertEquals(List.of("SELECT \"a;b\" FROM t"), result);
	}

	@Test
	void aDoubledSingleQuoteInsideAStringIsAnEscapedQuoteNotTheEndOfTheString() {
		List<String> result = StatementSplitter.split("SELECT 'it''s; here';");
		Assertions.assertEquals(List.of("SELECT 'it''s; here'"), result);
	}

	@Test
	void aLineCommentMarkerInsideAQuotedStringIsLiteralNotAComment() {
		List<String> result = StatementSplitter.split("SELECT 'abc--def';");
		Assertions.assertEquals(List.of("SELECT 'abc--def'"), result);
	}

	@Test
	void aRealLineCommentIsStrippedAndDoesNotHideASemicolonOnTheNextLine() {
		List<String> result = StatementSplitter.split("SELECT 1; -- a comment about the next line\nSELECT 2;");
		Assertions.assertEquals(List.of("SELECT 1", "SELECT 2"), result);
	}

	@Test
	void aBlockCommentIsStrippedIncludingMultiLine() {
		List<String> result = StatementSplitter.split("SELECT 1 /* multi\nline\ncomment */ ; SELECT 2;");
		Assertions.assertEquals(List.of("SELECT 1", "SELECT 2"), result);
	}

	@Test
	void aBlockCommentMarkerInsideAQuotedStringIsLiteral() {
		List<String> result = StatementSplitter.split("SELECT 'a/*b*/c';");
		Assertions.assertEquals(List.of("SELECT 'a/*b*/c'"), result);
	}

	@Test
	void repeatedSemicolonsProduceNoEmptyStatements() {
		List<String> result = StatementSplitter.split("SELECT 1;;; SELECT 2;");
		Assertions.assertEquals(List.of("SELECT 1", "SELECT 2"), result);
	}

	@Test
	void blankOrCommentOnlyInputProducesNoStatements() {
		Assertions.assertTrue(StatementSplitter.split("").isEmpty());
		Assertions.assertTrue(StatementSplitter.split("   ").isEmpty());
		Assertions.assertTrue(StatementSplitter.split("-- just a comment").isEmpty());
		Assertions.assertTrue(StatementSplitter.split(";;;").isEmpty());
	}

	@Test
	void nullInputProducesAnEmptyListNotAnException() {
		Assertions.assertTrue(StatementSplitter.split(null).isEmpty());
	}

	@Test
	void multiLineSingleStatementSpanningSeveralLinesStaysOneStatement() {
		List<String> result = StatementSplitter.split("SELECT *\nFROM t\nWHERE x = 1;");
		Assertions.assertEquals(1, result.size());
		Assertions.assertTrue(result.get(0).contains("FROM t"));
	}

	// ---- SPRINT 1909S: the statement model shared by the executor, formatter and validator ----

	@Test
	void anIndentedLineCommentDoesNotSwallowTheFollowingStatements() {
		// The historical script loader flattened newlines before splitting, so this lost everything after it
		List<String> result = StatementSplitter.split("SELECT 1;\n   -- indented note\nSELECT 2;\n");
		Assertions.assertEquals(List.of("SELECT 1", "SELECT 2"), result);
	}

	@Test
	void aTrailingLineCommentEndsAtTheEndOfItsLine() {
		List<String> result = StatementSplitter.split("SELECT 1; -- trailing\nSELECT 2; -- another\nSELECT 3;");
		Assertions.assertEquals(List.of("SELECT 1", "SELECT 2", "SELECT 3"), result);
	}

	@Test
	void crlfLineEndingsBehaveLikeLf() {
		List<String> result = StatementSplitter.split("SELECT 1; -- c\r\nSELECT 2;\r\n\r\nSELECT 3;\r\n");
		Assertions.assertEquals(List.of("SELECT 1", "SELECT 2", "SELECT 3"), result);
	}

	@Test
	void blankLinesBetweenStatementsAreHarmless() {
		Assertions.assertEquals(List.of("SELECT 1", "SELECT 2"), StatementSplitter.split("\n\nSELECT 1;\n\n\n\nSELECT 2;\n\n"));
	}

	@Test
	void aBlockCommentMarkerInsideAStringOnALineIsLiteral() {
		Assertions.assertEquals(List.of("SELECT 'a/*b'", "SELECT 2"), StatementSplitter.split("SELECT 'a/*b';\nSELECT 2;"));
	}

	@Test
	void aLineBreakInsideAStringLiteralIsKeptButOutsideItBecomesASpace() {
		Assertions.assertEquals(List.of("SELECT 'a\nb' FROM T"), StatementSplitter.split("SELECT 'a\nb'\nFROM T;"));
	}

	@Test
	void aBroadSqlCommandSpreadOverLinesReachesTheInterpreterAsOneLine() {
		Assertions.assertEquals(List.of("LIB RUN a.bsql  x"), StatementSplitter.split("LIB RUN a.bsql\n x;"));
	}

	@Test
	void mixedSqlAndBroadSqlCommandsSplitInOrder() {
		Assertions.assertEquals(List.of("CONNECT PROD", "SELECT * FROM CUSTOMER", "EXPORT RESULT customers.xlsx"),
				StatementSplitter.split("CONNECT PROD;\n\nSELECT * FROM CUSTOMER;\n-- done\nEXPORT RESULT customers.xlsx;"));
	}

	@Test
	void spansLocateEachStatementInTheOriginalText() {
		String text = "SELECT 1; -- c\n  SELECT 2 ;;\n-- end";
		StatementSplitter.Result result = StatementSplitter.splitSpans(text);
		Assertions.assertTrue(result.isTerminated());
		Assertions.assertEquals(2, result.getSpans().size());
		StatementSplitter.Span second = result.getSpans().get(1);
		Assertions.assertEquals("SELECT 2", second.getText());
		Assertions.assertTrue(text.substring(second.getStart(), second.getEnd()).contains("SELECT 2"));
		Assertions.assertEquals(';', text.charAt(second.getEnd()));
	}

	@Test
	void anUnterminatedQuoteOrBlockCommentIsReportedWithItsPosition() {
		StatementSplitter.Result quote = StatementSplitter.splitSpans("SELECT 1;\nSELECT 'oops;\nSELECT 3;");
		Assertions.assertFalse(quote.isTerminated());
		Assertions.assertEquals("single quote", quote.getUnterminated());
		Assertions.assertEquals(2, quote.getUnterminatedLine());
		Assertions.assertEquals(8, quote.getUnterminatedColumn());
		Assertions.assertEquals("double quote", StatementSplitter.splitSpans("SELECT \"x").getUnterminated());
		StatementSplitter.Result block = StatementSplitter.splitSpans("SELECT 1; /* never closed\nSELECT 2;");
		Assertions.assertEquals("block comment", block.getUnterminated());
		Assertions.assertEquals(1, block.getUnterminatedLine());
	}

	@Test
	void aLineCommentAtTheEndOfTheInputIsNotUnterminated() {
		Assertions.assertTrue(StatementSplitter.splitSpans("SELECT 1; -- no newline at end").isTerminated());
	}

	@Test
	void backslashSemicolonIsNotSpecialInFiles() {
		// only the interactive read loop honours \; - the shared splitter treats it like any other text (documented limitation)
		Assertions.assertEquals(List.of("SELECT 1\\", "SELECT 2"), StatementSplitter.split("SELECT 1\\; SELECT 2;"));
	}

	@Test
	void commandKeywordsSplitJustLikeSql() {
		// This class has no notion of "SQL" specifically - it splits BroadSQL commands the same way.
		List<String> result = StatementSplitter.split("SHOW ALL APIS; VAR ID=123;");
		Assertions.assertEquals(List.of("SHOW ALL APIS", "VAR ID=123"), result);
	}
}
