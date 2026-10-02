package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandAll;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandAll {

	private DatabaseConnection db;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice'), (2, 'Bob')");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void displaysEveryRowOfATable() throws BroadSQLException {
		CommandAll cmd = CommandTestSupport.create(CommandAll.class, db, console);

		cmd.execute("ALL CUSTOMER");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Alice"), "expected Alice in output, got:\n" + output);
		Assertions.assertTrue(output.contains("Bob"), "expected Bob in output, got:\n" + output);
	}

	@Test
	void rejectsAMissingTableName() {
		CommandAll cmd = CommandTestSupport.create(CommandAll.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("ALL"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_GAL_01),
				"expected the missing-table-name error, got:\n" + console.getOutput());
	}
}
