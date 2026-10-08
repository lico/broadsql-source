package com.upandcoding.broadsql.controller.shell.completion;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.sql.SqlTokenType;

/** SPRINT 0917-02, section 41 - unit tests for {@link SqlCompletionLexer}'s lexical/cursor analysis. */
class TestSqlCompletionLexer {

	private CompletionContext analyze(String textWithCursorMark) {
		int cursor = textWithCursorMark.indexOf('|');
		Assertions.assertTrue(cursor >= 0, "test input must mark the cursor with |");
		String text = textWithCursorMark.substring(0, cursor) + textWithCursorMark.substring(cursor + 1);
		return SqlCompletionLexer.analyze(text, cursor, true);
	}

	@Test
	void wordAtCursorAtEndOfBuffer() {
		CompletionContext ctx = analyze("SELECT * FROM cust|");
		Assertions.assertEquals("cust", ctx.getWord());
		Assertions.assertFalse(ctx.isInsideStringOrComment());
	}

	@Test
	void cursorInMiddleOfLineCompletesTheWordThereNotAtEnd() {
		CompletionContext ctx = analyze("SELECT * FR| customer");
		Assertions.assertEquals("FR", ctx.getWord());
		Assertions.assertEquals("SELECT", ctx.getFirstWordOfStatement());
	}

	@Test
	void insideSingleQuotedStringNoCompletion() {
		CompletionContext ctx = analyze("SELECT 'sel|'");
		Assertions.assertTrue(ctx.isInsideStringOrComment());
	}

	@Test
	void insideUnterminatedStringWhileTypingStillDetected() {
		CompletionContext ctx = analyze("SELECT 'sel|");
		Assertions.assertTrue(ctx.isInsideStringOrComment());
	}

	@Test
	void rightAfterClosedStringIsNotInsideIt() {
		CompletionContext ctx = analyze("SELECT 'abc' |");
		Assertions.assertFalse(ctx.isInsideStringOrComment());
	}

	@Test
	void insideQuotedIdentifierNoCompletion() {
		CompletionContext ctx = analyze("SELECT * FROM \"Customer ord|er\"");
		Assertions.assertTrue(ctx.isInsideStringOrComment());
	}

	@Test
	void insideLineCommentNoCompletion() {
		CompletionContext ctx = analyze("-- select som|e");
		Assertions.assertTrue(ctx.isInsideStringOrComment());
	}

	@Test
	void insideBlockCommentNoCompletion() {
		CompletionContext ctx = analyze("/* select som|e */");
		Assertions.assertTrue(ctx.isInsideStringOrComment());
	}

	@Test
	void insideUnterminatedBlockCommentStillDetected() {
		CompletionContext ctx = analyze("/* select som|e");
		Assertions.assertTrue(ctx.isInsideStringOrComment());
	}

	@Test
	void schemaDotTableQualifierIsRecognized() {
		CompletionContext ctx = analyze("SELECT * FROM sales.|");
		Assertions.assertEquals("sales", ctx.getQualifier());
		Assertions.assertEquals("", ctx.getWord());
	}

	@Test
	void aliasDotColumnQualifierIsRecognized() {
		CompletionContext ctx = analyze("SELECT c.customer_na| FROM customer c");
		Assertions.assertEquals("c", ctx.getQualifier());
		Assertions.assertEquals("customer_na", ctx.getWord());
	}

	@Test
	void noQualifierWhenThereIsNoDot() {
		CompletionContext ctx = analyze("SELECT cust|");
		Assertions.assertNull(ctx.getQualifier());
	}

	@Test
	void multipleStatementsOnlyTheCurrentOneIsConsidered() {
		CompletionContext ctx = analyze("SELECT * FROM customer; SELECT * FR|");
		Assertions.assertEquals("SELECT", ctx.getFirstWordOfStatement());
		Assertions.assertEquals(1, ctx.getStatementTokensBeforeCursor().stream()
				.filter(t -> t.isType(SqlTokenType.WORD)).count());
	}

	@Test
	void emptyWordAtTrailingSpaceAfterKeyword() {
		CompletionContext ctx = analyze("SELECT * FROM |");
		Assertions.assertEquals("", ctx.getWord());
		Assertions.assertEquals("SELECT", ctx.getFirstWordOfStatement());
	}

	@Test
	void connectedFlagIsPropagatedAsGiven() {
		CompletionContext connected = SqlCompletionLexer.analyze("SEL", 3, true);
		CompletionContext disconnected = SqlCompletionLexer.analyze("SEL", 3, false);
		Assertions.assertTrue(connected.isConnected());
		Assertions.assertFalse(disconnected.isConnected());
	}
}
