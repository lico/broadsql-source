package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowQuery;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExpand;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandFormat;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandFormat {

	@Test
	void reportsNoQueryInMemoryByDefault() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandFormat cmd = CommandTestSupport.create(CommandFormat.class, console);
		cmd.execute("FORMAT");
		Assertions.assertTrue(console.getOutput().contains("No query in memory"), console.getOutput());
		Assertions.assertNull(cmd.getLastSQLQuery());
	}

	@Test
	void formattingSuccessUpdatesCurrentSqlAndPrintsIt() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandFormat cmd = CommandTestSupport.create(CommandFormat.class, console);
		cmd.setLastSQLQuery("select id, name from customer where status = 'ACTIVE'");

		cmd.execute("FORMAT");

		String formatted = cmd.getLastSQLQuery();
		Assertions.assertNotEquals("select id, name from customer where status = 'ACTIVE'", formatted);
		Assertions.assertTrue(formatted.contains("id"));
		Assertions.assertTrue(formatted.contains("'ACTIVE'"));
		Assertions.assertTrue(console.getOutput().contains(formatted));
	}

	@Test
	void formattingFailurePreservesThePriorQueryExactly() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandFormat cmd = CommandTestSupport.create(CommandFormat.class, console);
		String original = "SELECT * FROM CUSTOMER WHERE NAME = 'unterminated";
		cmd.setLastSQLQuery(original);

		cmd.execute("FORMAT");

		Assertions.assertEquals(original, cmd.getLastSQLQuery());
	}

	@Test
	void showQueryDisplaysTheFormattedSqlAfterSuccess() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandFormat format = CommandTestSupport.create(CommandFormat.class, console);
		format.setLastSQLQuery("select id from customer");
		format.execute("FORMAT");
		String formatted = format.getLastSQLQuery();

		CommandShowQuery showQuery = CommandTestSupport.create(CommandShowQuery.class, console);
		showQuery.setLastSQLQuery(formatted);
		showQuery.execute("SHOW QUERY");

		Assertions.assertTrue(console.getOutput().contains(CommandUtils.toSingleLine(formatted)));
	}

	@Test
	void interpreterRerunsTheFormattedSqlNotTheFormatCommandItself() throws BroadSQLException {
		com.upandcoding.broadsql.controller.shell.ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = TestDatabaseConnections.connectInMemory("CREATE TABLE T (A INT)");
		try {
			CommandInterpreter interpreter = CommandTestSupport.createCommandInterpreter(settings, console, db);
			interpreter.getCommands().put("FORMAT", CommandTestSupport.create(CommandFormat.class, db, console, settings));
			interpreter.lastSQLQuery = "select a from t";

			interpreter.setQuery("FORMAT");
			interpreter.executeCommand();

			Assertions.assertEquals("FORMAT", interpreter.getQuery());
			Assertions.assertNotEquals("FORMAT", interpreter.lastSQLQuery);
			Assertions.assertTrue(interpreter.lastSQLQuery.toLowerCase().contains("select"));
		} finally {
			TestDatabaseConnections.close(db);
		}
	}

	@Test
	void expandThenFormatComposeCorrectly() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = TestDatabaseConnections.connectInMemory("CREATE TABLE CUSTOMER (ID INT, NAME VARCHAR(50))");
		try {
			CommandExpand expand = CommandTestSupport.create(CommandExpand.class, db, console);
			expand.setLastSQLQuery("SELECT * FROM CUSTOMER");
			expand.execute("EXPAND");
			String expanded = expand.getLastSQLQuery();
			Assertions.assertFalse(expanded.contains("*"));

			CommandFormat format = CommandTestSupport.create(CommandFormat.class, console);
			format.setLastSQLQuery(expanded);
			format.execute("FORMAT");
			String formatted = format.getLastSQLQuery();

			Assertions.assertTrue(formatted.contains("ID"));
			Assertions.assertTrue(formatted.contains("NAME"));
			Assertions.assertTrue(formatted.contains("FROM CUSTOMER") || formatted.contains("FROM\nCUSTOMER"));
		} finally {
			TestDatabaseConnections.close(db);
		}
	}
}
