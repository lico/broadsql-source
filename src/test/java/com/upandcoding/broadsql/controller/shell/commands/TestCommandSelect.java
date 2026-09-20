package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandDefault;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * {@link CommandDefault} is the fallback command that forwards whatever doesn't match a keyword
 * straight to the connected database - BroadSQL's actual "run this SQL" path.
 */
class TestCommandSelect {

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
	void runsASelectAndPrintsTheResult() throws BroadSQLException {
		CommandDefault cmd = CommandTestSupport.create(CommandDefault.class, db, console);

		cmd.execute("SELECT COUNT(*) FROM CUSTOMER");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("2"), "expected the count 2 in output, got:\n" + output);
	}

	@Test
	void runsAnInsertAndCommitsIt() throws BroadSQLException {
		CommandDefault cmd = CommandTestSupport.create(CommandDefault.class, db, console);

		cmd.execute("INSERT INTO CUSTOMER VALUES (3, 'Charlie')");
		console.clear();
		cmd.execute("SELECT COUNT(*) FROM CUSTOMER");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("3"), "expected the count 3 after the insert, got:\n" + output);
	}

	@Test
	void reportsAnErrorForInvalidSqlWithoutThrowing() {
		CommandDefault cmd = CommandTestSupport.create(CommandDefault.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("not valid sql at all"));

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("ERROR"), "expected an error message, got:\n" + output);
	}
}
