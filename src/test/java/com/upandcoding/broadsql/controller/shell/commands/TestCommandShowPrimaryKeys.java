package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowPrimaryKeys;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandShowPrimaryKeys {

	private DatabaseConnection db;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void listsThePrimaryKeyColumnsOfATable() throws BroadSQLException {
		CommandShowPrimaryKeys cmd = CommandTestSupport.create(CommandShowPrimaryKeys.class, db, console);

		cmd.execute("SHOW PK CUSTOMER");

		Assertions.assertTrue(console.getOutput().contains("ID"), "expected column ID in output, got:\n" + console.getOutput());
	}

	@Test
	void listsOnlyTheCurrentSchemaWhenNoSchemaIsGiven() throws BroadSQLException {
		// Same root cause as docs/TODO.md #12 for DESCR: an unqualified table name must not pick up a
		// same-named table's primary key from another schema.
		db.executeUpdateQuery("CREATE SCHEMA OTHERSCHEMA");
		db.executeUpdateQuery("CREATE TABLE OTHERSCHEMA.CUSTOMER (OTHERID INT PRIMARY KEY)");

		CommandShowPrimaryKeys cmd = CommandTestSupport.create(CommandShowPrimaryKeys.class, db, console);

		cmd.execute("SHOW PK CUSTOMER");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("ID"), "expected the current schema's column ID in output, got:\n" + output);
		Assertions.assertFalse(output.contains("OTHERID"),
				"did not expect the other schema's column OTHERID in output, got:\n" + output);
	}

	@Test
	void reportsAnErrorForATableThatDoesNotExist() {
		CommandShowPrimaryKeys cmd = CommandTestSupport.create(CommandShowPrimaryKeys.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("SHOW PK DOESNOTEXIST"));

		Assertions.assertTrue(console.getOutput().contains("does not exist"),
				"expected the table-not-found error, got:\n" + console.getOutput());
	}

	@Test
	void rejectsAMissingTableName() {
		CommandShowPrimaryKeys cmd = CommandTestSupport.create(CommandShowPrimaryKeys.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("SHOW PK"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_GAL_01),
				"expected the missing-table-name error, got:\n" + console.getOutput());
	}
}
