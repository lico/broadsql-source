package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

class TestSqlListLiteral {

	@Test
	void rendersASimpleList() throws BroadSQLException {
		Assertions.assertEquals("('A','B','C')", SqlListLiteral.render(List.of("A", "B", "C"), "<@x>"));
	}

	@Test
	void alwaysQuotesEvenNumericLookingValues() throws BroadSQLException {
		// Deliberate: <@file>'s original behavior (always a string literal) is preserved for every
		// source - see class Javadoc for why leading-zero/business-identifier values make numeric
		// auto-detection unsafe.
		Assertions.assertEquals("('123','00456')", SqlListLiteral.render(List.of("123", "00456"), "<@x>"));
	}

	@Test
	void escapesEmbeddedSingleQuotesByDoubling() throws BroadSQLException {
		Assertions.assertEquals("('O''BRIEN')", SqlListLiteral.render(List.of("O'BRIEN"), "<@x>"));
	}

	@Test
	void preservesOrderAndDuplicates() throws BroadSQLException {
		Assertions.assertEquals("('B','A','A')", SqlListLiteral.render(List.of("B", "A", "A"), "<@x>"));
	}

	@Test
	void rejectsAnEmptyListWithAClearErrorInsteadOfGeneratingInParens() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> SqlListLiteral.render(Collections.emptyList(), "<@clipboard>"));
		Assertions.assertTrue(ex.getMessage().contains("<@clipboard>"), "expected the source description in the error, got: " + ex.getMessage());
		Assertions.assertTrue(ex.getMessage().toLowerCase().contains("no usable values"), "got: " + ex.getMessage());
	}
}
