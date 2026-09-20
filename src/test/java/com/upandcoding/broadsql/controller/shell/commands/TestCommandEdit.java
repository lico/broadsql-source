package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.extensions.CommandEdit;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;

/**
 * {@code EDIT} with a real file name shells out to {@code notepad.exe} and blocks reading its
 * (GUI, non-existent) stdout until the spawned window is closed; the bare, no-argument form (edit
 * the last SQL query, see {@code docs/TODO.md} item 17) launches Notepad the same way once there is
 * a query in memory. Both would hang the test runner waiting on a real window, so only the
 * branches reachable without touching Notepad are covered here (see {@code docs/TESTS_STRATEGY.md},
 * "Out of scope"): no query in memory yet, and (on this Windows dev machine) the same result when
 * the stored query is blank.
 */
class TestCommandEdit {

	@Test
	void reportsNoQueryInMemoryWithNoFileNameAndNoStoredQuery() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandEdit cmd = CommandTestSupport.create(CommandEdit.class, console);

		cmd.execute("EDIT");

		Assertions.assertTrue(console.getOutput().contains("No query in memory"),
				"expected the no-query message, got:\n" + console.getOutput());
	}

	@Test
	void reportsNoQueryInMemoryWithNoFileNameAndABlankStoredQuery() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandEdit cmd = CommandTestSupport.create(CommandEdit.class, console);
		cmd.setLastSQLQuery("   ");

		cmd.execute("ED");

		Assertions.assertTrue(console.getOutput().contains("No query in memory"),
				"expected the no-query message, got:\n" + console.getOutput());
	}
}
