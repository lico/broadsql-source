package com.upandcoding.broadsql.controller.shell.sql;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestSqlTokenizer {

	@Test
	void splitsBasicSelectIntoExpectedTokenTypes() throws SqlLexException {
		List<SqlToken> tokens = SqlTokenizer.tokenize("SELECT * FROM CUSTOMER");
		List<SqlToken> significant = tokens.stream().filter(t -> !t.isInsignificant()).toList();
		Assertions.assertEquals(4, significant.size());
		Assertions.assertTrue(significant.get(0).is("SELECT"));
		Assertions.assertTrue(significant.get(1).is("*"));
		Assertions.assertTrue(significant.get(2).is("FROM"));
		Assertions.assertTrue(significant.get(3).is("CUSTOMER"));
	}

	@Test
	void doesNotSplitAStarInsideAStringLiteral() throws SqlLexException {
		List<SqlToken> tokens = SqlTokenizer.tokenize("SELECT 'a * b' FROM DUAL");
		List<SqlToken> literals = tokens.stream().filter(t -> t.isType(SqlTokenType.STRING_LITERAL)).toList();
		Assertions.assertEquals(1, literals.size());
		Assertions.assertEquals("'a * b'", literals.get(0).getText());
	}

	@Test
	void handlesDoubledSingleQuoteEscapeInsideAStringLiteral() throws SqlLexException {
		List<SqlToken> tokens = SqlTokenizer.tokenize("SELECT 'it''s' FROM DUAL");
		List<SqlToken> literals = tokens.stream().filter(t -> t.isType(SqlTokenType.STRING_LITERAL)).toList();
		Assertions.assertEquals(1, literals.size());
		Assertions.assertEquals("'it''s'", literals.get(0).getText());
	}

	@Test
	void doesNotSplitAStarInsideALineComment() throws SqlLexException {
		List<SqlToken> tokens = SqlTokenizer.tokenize("SELECT 1 -- a * b\nFROM DUAL");
		List<SqlToken> comments = tokens.stream().filter(t -> t.isType(SqlTokenType.LINE_COMMENT)).toList();
		Assertions.assertEquals(1, comments.size());
		Assertions.assertEquals("-- a * b", comments.get(0).getText());
	}

	@Test
	void doesNotSplitAStarInsideABlockCommentOrHint() throws SqlLexException {
		List<SqlToken> tokens = SqlTokenizer.tokenize("SELECT /*+ INDEX(CUSTOMER IDX) */ * FROM CUSTOMER");
		List<SqlToken> comments = tokens.stream().filter(t -> t.isType(SqlTokenType.BLOCK_COMMENT)).toList();
		Assertions.assertEquals(1, comments.size());
		Assertions.assertEquals("/*+ INDEX(CUSTOMER IDX) */", comments.get(0).getText());
	}

	@Test
	void recognizesQuotedIdentifiersWithDoubledQuoteEscape() throws SqlLexException {
		List<SqlToken> tokens = SqlTokenizer.tokenize("SELECT \"My\"\"Col\" FROM T");
		List<SqlToken> idents = tokens.stream().filter(t -> t.isType(SqlTokenType.QUOTED_IDENTIFIER)).toList();
		Assertions.assertEquals(1, idents.size());
		Assertions.assertEquals("\"My\"\"Col\"", idents.get(0).getText());
	}

	@Test
	void keepsADecimalNumberAsOneTokenNotSplitOnTheDot() throws SqlLexException {
		List<SqlToken> tokens = SqlTokenizer.tokenize("SELECT 1 FROM T WHERE AMOUNT > 12.50");
		List<SqlToken> numbers = tokens.stream().filter(t -> t.isType(SqlTokenType.NUMBER)).toList();
		Assertions.assertTrue(numbers.stream().anyMatch(t -> t.getText().equals("12.50")));
	}

	@Test
	void throwsOnUnterminatedStringLiteral() {
		Assertions.assertThrows(SqlLexException.class, () -> SqlTokenizer.tokenize("SELECT 'unterminated FROM T"));
	}

	@Test
	void throwsOnUnterminatedBlockComment() {
		Assertions.assertThrows(SqlLexException.class, () -> SqlTokenizer.tokenize("SELECT 1 /* unterminated"));
	}

	@Test
	void recognizesTwoCharacterOperatorsAsOneToken() throws SqlLexException {
		List<SqlToken> tokens = SqlTokenizer.tokenize("SELECT 1 FROM T WHERE A <= B AND C <> D");
		List<SqlToken> ops = tokens.stream().filter(t -> t.isType(SqlTokenType.PUNCTUATION) && t.getText().length() == 2).toList();
		Assertions.assertTrue(ops.stream().anyMatch(t -> t.is("<=")));
		Assertions.assertTrue(ops.stream().anyMatch(t -> t.is("<>")));
	}
}
