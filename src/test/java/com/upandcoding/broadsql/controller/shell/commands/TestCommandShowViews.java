package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowViews;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandShowViews {

	private DatabaseConnection db;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"CREATE VIEW CUSTOMER_VIEW AS SELECT * FROM CUSTOMER");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void listsViewsButNotTables() throws BroadSQLException {
		CommandShowViews cmd = CommandTestSupport.create(CommandShowViews.class, db, console);

		cmd.execute("SHOW VIEWS");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("CUSTOMER_VIEW"), "expected CUSTOMER_VIEW in output, got:\n" + output);
		Assertions.assertTrue(output.contains("1 rows fetched."), "expected exactly one view (not the base table), got:\n" + output);
	}
}
