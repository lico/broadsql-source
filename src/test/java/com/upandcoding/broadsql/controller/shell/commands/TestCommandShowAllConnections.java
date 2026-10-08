package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowAllConnections;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

class TestCommandShowAllConnections {

	private DatabaseDefinitionsVault newVaultWithTwoTypes() throws BroadSQLException {
		DatabaseDefinition h2 = new DatabaseDefinition("H2DB");
		h2.setDbDriver("org.h2.Driver");
		h2.setDbType("H2");
		h2.setDbName("H2 database");

		DatabaseDefinition pg = new DatabaseDefinition("PGDB");
		pg.setDbDriver("org.postgresql.Driver");
		pg.setDbType("PostgreSQL");
		pg.setDbName("Postgres database");

		return TestDatabaseConnections.newVault(h2, pg);
	}

	@Test
	void listsEveryConnectionWithNoArgument() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowAllConnections cmd = CommandTestSupport.create(CommandShowAllConnections.class, console);
		cmd.setDatabaseConnectionsVault(newVaultWithTwoTypes());

		cmd.execute("SHOW ALL CONNECTIONS");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("H2DB"), "expected H2DB in output, got:\n" + output);
		Assertions.assertTrue(output.contains("PGDB"), "expected PGDB in output, got:\n" + output);
		Assertions.assertTrue(output.contains("2 database connections found"),
				"expected a count of 2, got:\n" + output);
		assertPromptFreeBorderedTable(console);
	}

	// SPRINT XT02B acceptance correction: every table row/border/summary line must start with "|" and
	// must never contain the shell prompt (previously: no leading "|", and every line prefixed with
	// the prompt via console.println's default displayPrompt=true).
	static void assertPromptFreeBorderedTable(CapturingShellConsole console) {
		String output = console.getOutput();
		for (String line : output.split("\n", -1)) {
			if (line.isBlank()) {
				continue;
			}
			Assertions.assertFalse(line.contains(console.getPrompt()),
					"output line must not contain the shell prompt '" + console.getPrompt() + "': '" + line + "'\nfull output:\n" + output);
			if (line.contains("|")) {
				Assertions.assertTrue(line.startsWith("|"), "table line must start with '|': '" + line + "'\nfull output:\n" + output);
			}
		}
	}

	@Test
	void filtersByExactDatabaseType() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowAllConnections cmd = CommandTestSupport.create(CommandShowAllConnections.class, console);
		cmd.setDatabaseConnectionsVault(newVaultWithTwoTypes());

		cmd.execute("SHOW ALL CONNECTIONS H2");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("H2DB"), "expected H2DB in output, got:\n" + output);
		Assertions.assertFalse(output.contains("PGDB"), "did not expect PGDB in output, got:\n" + output);
		Assertions.assertTrue(output.contains("1 database connections found"),
				"expected a count of 1, got:\n" + output);
		assertPromptFreeBorderedTable(console);
	}
}
