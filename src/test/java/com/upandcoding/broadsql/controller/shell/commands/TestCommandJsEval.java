package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.scripting.CommandJsEval;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandJsEval {

	private DatabaseConnection db;

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void requiresCode() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandJsEval cmd = CommandTestSupport.create(CommandJsEval.class, console);

		cmd.execute("JS EVAL");

		Assertions.assertTrue(console.getOutput().contains("You must provide JavaScript code to evaluate"),
				"expected the missing-code message, got:\n" + console.getOutput());
	}

	@Test
	void evalsInlineCodeContainingSpaces() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandJsEval cmd = CommandTestSupport.create(CommandJsEval.class, console);

		cmd.execute("JS EVAL var x = 2 + 2; println('result is ' + x);");

		Assertions.assertTrue(console.getOutput().contains("result is 4"), "expected the multi-token code to run as one script, got:\n" + console.getOutput());
	}

	@Test
	void aScriptErrorDoesNotCrashAndBecomesABroadSQLException() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandJsEval cmd = CommandTestSupport.create(CommandJsEval.class, console);

		Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("JS EVAL throw 'boom';"));
	}

	@Test
	void dbAliasesTheActiveConnection() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE T (ID INT)", "INSERT INTO T VALUES (9)");
		CommandJsEval cmd = CommandTestSupport.create(CommandJsEval.class, db, console, TestDatabaseConnections.defaultConsoleSettings());

		cmd.execute("JS EVAL var rows = db.execute('SELECT ID FROM T'); println(rows.get(0)['ID']);");

		Assertions.assertTrue(console.getOutput().contains("9"), "expected the query to run against the active connection, got:\n" + console.getOutput());
	}
}
