package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowQuery;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;

/** {@link CommandShowQuery} needs no database - it only echoes the command's own in-memory field. */
class TestCommandShowQuery {

	@Test
	void reportsNoQueryInMemoryByDefault() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowQuery cmd = CommandTestSupport.create(CommandShowQuery.class, console);

		cmd.execute("SHOW QUERY");

		Assertions.assertTrue(console.getOutput().contains("No query in memory"),
				"expected the no-query message, got:\n" + console.getOutput());
	}

	@Test
	void reportsNoQueryInMemoryWhenLastQueryIsBlank() throws BroadSQLException {
		// Merged from the old CommandInterpreter "//" branch's more defensive check when the two were
		// unified into one implementation - see docs/TODO.md item 10.
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowQuery cmd = CommandTestSupport.create(CommandShowQuery.class, console);
		cmd.setLastSQLQuery("   ");

		cmd.execute("SHOW QUERY");

		Assertions.assertTrue(console.getOutput().contains("No query in memory"),
				"expected the no-query message, got:\n" + console.getOutput());
	}

	@Test
	void echoesTheLastStoredQuery() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowQuery cmd = CommandTestSupport.create(CommandShowQuery.class, console);
		cmd.setLastSQLQuery("SELECT * FROM CUSTOMER");

		cmd.execute("SHOW QUERY");

		Assertions.assertTrue(console.getOutput().contains("SELECT * FROM CUSTOMER"),
				"expected the last query in output, got:\n" + console.getOutput());
	}

	@Test
	void collapsesAMultiLineStoredQueryOntoASingleLine() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowQuery cmd = CommandTestSupport.create(CommandShowQuery.class, console);
		cmd.setLastSQLQuery("SELECT *\nFROM CUSTOMER\n  WHERE ID = 1");

		cmd.execute("SHOW QUERY");

		Assertions.assertTrue(console.getOutput().contains("SELECT * FROM CUSTOMER WHERE ID = 1"),
				"expected the query collapsed onto a single line, got:\n" + console.getOutput());
	}
}
