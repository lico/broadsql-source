package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExpand;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/** End-to-end {@code EXPAND;} tests against a real H2 in-memory database - see also {@code TestSqlWildcardExpander}. */
class TestCommandExpand {

	private DatabaseConnection db;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50), STATUS VARCHAR(10))",
				"CREATE TABLE ORDERS (ORDER_ID INT PRIMARY KEY, CUSTOMER_ID INT)");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private CommandExpand command() {
		return CommandTestSupport.create(CommandExpand.class, db, console);
	}

	@Test
	void reportsNoQueryInMemoryByDefault() throws BroadSQLException {
		CommandExpand cmd = command();
		cmd.execute("EXPAND");
		Assertions.assertTrue(console.getOutput().contains("No query in memory"), console.getOutput());
		Assertions.assertNull(cmd.getLastSQLQuery());
	}

	@Test
	void expandsANakedStarOverASingleTable() throws BroadSQLException {
		CommandExpand cmd = command();
		cmd.setLastSQLQuery("SELECT * FROM CUSTOMER");
		cmd.execute("EXPAND");
		String expanded = cmd.getLastSQLQuery();
		Assertions.assertTrue(expanded.contains("ID"));
		Assertions.assertTrue(expanded.contains("NAME"));
		Assertions.assertTrue(expanded.contains("STATUS"));
		Assertions.assertTrue(expanded.contains("FROM CUSTOMER"));
		Assertions.assertFalse(expanded.contains("*"), "no wildcard should remain, got:\n" + expanded);
		Assertions.assertTrue(console.getOutput().contains(expanded), "the expanded SQL must be printed immediately");
	}

	@Test
	void expandsAnAliasQualifiedStarAcrossAJoin() throws BroadSQLException {
		CommandExpand cmd = command();
		cmd.setLastSQLQuery("SELECT c.*, o.ORDER_ID FROM CUSTOMER c JOIN ORDERS o ON o.CUSTOMER_ID = c.ID");
		cmd.execute("EXPAND");
		String expanded = cmd.getLastSQLQuery();
		Assertions.assertTrue(expanded.contains("c.ID"), expanded);
		Assertions.assertTrue(expanded.contains("c.NAME"), expanded);
		Assertions.assertTrue(expanded.contains("c.STATUS"), expanded);
		Assertions.assertTrue(expanded.contains("o.ORDER_ID"), expanded);
	}

	@Test
	void rejectsAMissingTableAndPreservesThePriorQueryExactly() throws BroadSQLException {
		CommandExpand cmd = command();
		String original = "SELECT * FROM NOSUCHTABLE";
		cmd.setLastSQLQuery(original);
		cmd.execute("EXPAND");
		Assertions.assertEquals(original, cmd.getLastSQLQuery(), "failed expansion must not corrupt the current SQL");
		Assertions.assertFalse(console.getOutput().contains("SELECT ID"));
	}

	@Test
	void rejectsCountStarAndPreservesThePriorQueryExactly() throws BroadSQLException {
		CommandExpand cmd = command();
		String original = "SELECT COUNT(*) FROM CUSTOMER";
		cmd.setLastSQLQuery(original);
		cmd.execute("EXPAND");
		Assertions.assertEquals(original, cmd.getLastSQLQuery());
	}

	@Test
	void showQueryDisplaysTheExpandedSqlAfterASuccessfulExpansion() throws BroadSQLException {
		CommandExpand expand = command();
		expand.setLastSQLQuery("SELECT * FROM CUSTOMER");
		expand.execute("EXPAND");
		String expanded = expand.getLastSQLQuery();

		com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowQuery showQuery =
				CommandTestSupport.create(com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowQuery.class, console);
		showQuery.setLastSQLQuery(expanded);
		showQuery.execute("SHOW QUERY");
		Assertions.assertTrue(console.getOutput().contains(com.upandcoding.broadsql.controller.shell.commands.CommandUtils.toSingleLine(expanded)));
	}

	@Test
	void interpreterRerunsTheExpandedSqlNotTheExpandCommandItself() throws BroadSQLException {
		// executeCommand() itself never seeds lastSQLQuery from a plain SELECT - that bookkeeping lives
		// in CommandInterpreter.run()'s console loop (see its "Save current query as last executed
		// query" comment), so this test seeds the interpreter's current SQL directly, exactly like
		// TestCommandInterpreterCrossEnvironmentRerun does for the same reason.
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		CommandInterpreter interpreter = CommandTestSupport.createCommandInterpreter(settings, console, db);
		interpreter.getCommands().put("EXPAND", CommandTestSupport.create(CommandExpand.class, db, console, settings));
		interpreter.lastSQLQuery = "SELECT * FROM CUSTOMER";

		interpreter.setQuery("EXPAND");
		interpreter.executeCommand();

		Assertions.assertEquals("EXPAND", interpreter.getQuery());
		Assertions.assertNotEquals("EXPAND", interpreter.lastSQLQuery);
		Assertions.assertTrue(interpreter.lastSQLQuery.contains("FROM CUSTOMER"));
		Assertions.assertFalse(interpreter.lastSQLQuery.contains("*"));
	}
}
