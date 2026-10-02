package com.upandcoding.broadsql.dao.load;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.load.LoadStatement.ExecutionMode;

class LoadCommandParserTest {

	@Test
	void parsesTheBareTwoArgumentForm() throws BroadSQLException {
		LoadStatement statement = LoadCommandParser.parse(new String[] { "CUSTOMER", "customer.csv" }, "LOAD");

		Assertions.assertEquals("CUSTOMER", statement.getTableName());
		Assertions.assertEquals("customer.csv", statement.getFileName());
		Assertions.assertEquals(ExecutionMode.CONFIRM, statement.getExecutionMode());
	}

	@Test
	void parsesPreviewAndExecuteTrailingKeywords() throws BroadSQLException {
		LoadStatement preview = LoadCommandParser.parse(new String[] { "CUSTOMER", "customer.csv", "PREVIEW" }, "LOAD");
		LoadStatement execute = LoadCommandParser.parse(new String[] { "CUSTOMER", "customer.csv", "EXECUTE" }, "LOAD");

		Assertions.assertEquals(ExecutionMode.PREVIEW, preview.getExecutionMode());
		Assertions.assertEquals(ExecutionMode.EXECUTE, execute.getExecutionMode());
	}

	@Test
	void acceptsTheLegacyCreateModeKeywordForCompatibility() throws BroadSQLException {
		LoadStatement statement = LoadCommandParser.parse(new String[] { "CREATE", "CUSTOMER", "customer.csv" }, "LOAD");

		Assertions.assertEquals("CUSTOMER", statement.getTableName());
		Assertions.assertEquals("customer.csv", statement.getFileName());
		Assertions.assertEquals(ExecutionMode.CONFIRM, statement.getExecutionMode());
	}

	@Test
	void acceptsLegacyCreateWithATrailingKeywordToo() throws BroadSQLException {
		LoadStatement statement = LoadCommandParser.parse(new String[] { "CREATE", "CUSTOMER", "customer.csv", "EXECUTE" }, "LOAD");

		Assertions.assertEquals(ExecutionMode.EXECUTE, statement.getExecutionMode());
	}

	@Test
	void rejectsUpdateModeWithAClearMigrationMessage() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> LoadCommandParser.parse(new String[] { "UPDATE", "CUSTOMER", "customer.csv" }, "LOAD"));

		Assertions.assertTrue(ex.getMessage().contains("no longer supported"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsUpdateModeFromBatchloadToo() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> LoadCommandParser.parse(new String[] { "UPDATE", "CUSTOMER", "customer.csv" }, "BATCHLOAD"));

		Assertions.assertTrue(ex.getMessage().startsWith("BATCHLOAD UPDATE"), "got: " + ex.getMessage());
	}

	@Test
	void rejectsTooFewArguments() {
		Assertions.assertThrows(BroadSQLException.class, () -> LoadCommandParser.parse(new String[] { "CUSTOMER" }, "LOAD"));
		Assertions.assertThrows(BroadSQLException.class, () -> LoadCommandParser.parse(new String[0], "LOAD"));
		Assertions.assertThrows(BroadSQLException.class, () -> LoadCommandParser.parse(null, "LOAD"));
	}

	@Test
	void rejectsAnUnknownTrailingKeyword() {
		Assertions.assertThrows(BroadSQLException.class,
				() -> LoadCommandParser.parse(new String[] { "CUSTOMER", "customer.csv", "BOGUS" }, "LOAD"));
	}

	@Test
	void rejectsTooManyArguments() {
		Assertions.assertThrows(BroadSQLException.class,
				() -> LoadCommandParser.parse(new String[] { "CREATE", "CUSTOMER", "customer.csv", "EXECUTE", "EXTRA" }, "LOAD"));
	}
}
