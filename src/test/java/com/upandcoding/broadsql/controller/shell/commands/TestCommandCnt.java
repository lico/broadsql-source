package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandCnt;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandCnt {

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
	void countsTheRowsOfATable() throws BroadSQLException {
		CommandCnt cmd = CommandTestSupport.create(CommandCnt.class, db, console);

		cmd.execute("CNT CUSTOMER");

		Assertions.assertTrue(console.getOutput().contains("2"), "expected the count 2 in output, got:\n" + console.getOutput());
	}

	@Test
	void rejectsAMissingTableName() {
		CommandCnt cmd = CommandTestSupport.create(CommandCnt.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("CNT"));

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_GAL_01),
				"expected the missing-table-name error, got:\n" + console.getOutput());
	}
}
