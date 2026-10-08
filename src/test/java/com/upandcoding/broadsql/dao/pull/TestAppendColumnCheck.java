package com.upandcoding.broadsql.dao.pull;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/** SPRINT 1005C (#202): the plain APPEND rule, same count, same names, same order, and nothing else. */
class TestAppendColumnCheck {

	@Test
	void theSameColumnsInTheSameOrderPass() {
		Assertions.assertDoesNotThrow(() -> AppendColumnCheck.check(List.of("ID", "CUSTOMER", "AMOUNT"), List.of("ID", "CUSTOMER", "AMOUNT")));
		Assertions.assertDoesNotThrow(() -> AppendColumnCheck.check(List.of("id", "Customer"), List.of("ID", "CUSTOMER")), "case-insensitive");
		Assertions.assertDoesNotThrow(() -> AppendColumnCheck.check(List.of(), List.of()));
	}

	@Test
	void aDifferentCountNamesBothCounts() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> AppendColumnCheck.check(List.of("A", "B", "C", "D", "E"), List.of("A", "B", "C", "D")));
		Assertions.assertTrue(ex.getMessage().startsWith("APPEND refused: destination has 4 columns but query returns 5."), ex.getMessage());
	}

	@Test
	void aDifferentNameNamesTheColumnAndBothNames() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> AppendColumnCheck.check(List.of("ID", "AMOUNT", "CUSTOMER_NAME"), List.of("ID", "AMOUNT", "CUSTOMER")));
		Assertions.assertTrue(ex.getMessage().startsWith("APPEND refused: column 3 differs.\nQuery: CUSTOMER_NAME\nDestination: CUSTOMER"), ex.getMessage());
	}

	@Test
	void theSameNamesInAnotherOrderAreRefused() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> AppendColumnCheck.check(List.of("B", "A"), List.of("A", "B")));
		Assertions.assertTrue(ex.getMessage().contains("column 1 differs"), ex.getMessage());
	}
}
