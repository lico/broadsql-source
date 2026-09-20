package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowGroup;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Covers {@code SHOW GROUP}, which took over the primary keyword from {@code CommandShowEnvironments}
 * (docs/CONNECTION_MODEL.md §10): fixes the given/current connection's Database Group and lists every
 * connection sharing it, varying by Environment. {@code SHOW ENVIRONMENTS} (this class's former
 * keyword) is kept working as a backward-compatible alias on the same command - see
 * {@link #theOldShowEnvironmentsKeywordStillWorksAsAnAlias}.
 */
class TestCommandShowGroup {

	private DatabaseDefinition connection(String id, String group, String environment) {
		DatabaseDefinition def = new DatabaseDefinition(id);
		def.setDbDriver("org.h2.Driver");
		def.setDbType("H2");
		def.setDbName(id + " database");
		def.setUrl("jdbc:h2:mem:" + id);
		def.setDatabaseGroup(group);
		def.setEnvironment(environment);
		return def;
	}

	@Test
	void listsConnectionsSharingTheSameGroup() throws BroadSQLException {
		DatabaseDefinition desk1 = connection("DESK1", "DESK", "DEV");
		DatabaseDefinition desk2 = connection("DESK2", "DESK", "QA");
		DatabaseDefinition sales1 = connection("SALES1", "SALES", "DEV");
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault(desk1, desk2, sales1);
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowGroup cmd = CommandTestSupport.create(CommandShowGroup.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("SHOW GROUP DESK1");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("DESK1"), "expected DESK1 in output, got:\n" + output);
		Assertions.assertTrue(output.contains("DESK2"), "expected DESK2 in output, got:\n" + output);
		Assertions.assertFalse(output.contains("SALES1"), "did not expect SALES1 in output, got:\n" + output);
		Assertions.assertTrue(output.contains("2 database connections found"), "expected a count of 2, got:\n" + output);
	}

	@Test
	void theOldShowEnvironmentsKeywordStillWorksAsAnAlias() throws BroadSQLException {
		DatabaseDefinition desk1 = connection("DESK1", "DESK", "DEV");
		DatabaseDefinition desk2 = connection("DESK2", "DESK", "QA");
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault(desk1, desk2);
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowGroup cmd = CommandTestSupport.create(CommandShowGroup.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("SHOW ENVIRONMENTS DESK1");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("DESK1"), "expected DESK1 in output, got:\n" + output);
		Assertions.assertTrue(output.contains("DESK2"), "expected DESK2 in output, got:\n" + output);
	}

	@Test
	void filtersByEnvironmentNameWhenGiven() throws BroadSQLException {
		DatabaseDefinition desk1 = connection("DESK1", "DESK", "DEV");
		DatabaseDefinition desk2 = connection("DESK2", "DESK", "QA");
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault(desk1, desk2);
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowGroup cmd = CommandTestSupport.create(CommandShowGroup.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("SHOW GROUP DESK1 QA");

		String output = console.getOutput();
		Assertions.assertFalse(output.contains("DESK1"), "did not expect DESK1 (DEV, filtered out) in output, got:\n" + output);
		Assertions.assertTrue(output.contains("DESK2"), "expected DESK2 (QA) in output, got:\n" + output);
		Assertions.assertTrue(output.contains("1 database connections found"), "expected a count of 1, got:\n" + output);
	}

	@Test
	void warnsWhenTheConnectionHasNoGroup() throws BroadSQLException {
		DatabaseDefinition noGroup = connection("NOINST", null, null);
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault(noGroup);
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowGroup cmd = CommandTestSupport.create(CommandShowGroup.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("SHOW GROUP NOINST");

		Assertions.assertTrue(console.getOutput().contains("has no Database Group defined"),
				"expected the no-group warning, got:\n" + console.getOutput());
	}

	/**
	 * Regression test for the column-misalignment bug the user found (docs/TECHNICAL_CHANGE.md,
	 * 2026-09-05, "SHOW ENVIRONMENTS: environment column width fixed"): the "ENVIRONMENT" header
	 * (11 characters) was wider than the column's fixed pad width (10), so
	 * {@code StringUtils.rightPad} left it unpadded while every separator line and data row (all
	 * <= 10 characters) were padded to exactly 10 - one character narrower than the header. Asserts
	 * every printed line's first column (up to the first {@code |}) is exactly the same width.
	 */
	@Test
	void environmentColumnIsTheSameWidthOnEveryLine() throws BroadSQLException {
		DatabaseDefinition desk1 = connection("DESK1", "DESK", "DEV");
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault(desk1);
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowGroup cmd = CommandTestSupport.create(CommandShowGroup.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("SHOW GROUP DESK1");

		String[] lines = console.getOutput().split("\\R");
		int headerWidth = -1;
		for (String line : lines) {
			int sep = line.indexOf('|');
			if (sep < 0) {
				continue;
			}
			if (headerWidth == -1) {
				headerWidth = sep;
			} else {
				Assertions.assertEquals(headerWidth, sep, "every table line's Environment column must be the same width, got:\n" + console.getOutput());
			}
		}
		Assertions.assertTrue(headerWidth > 0, "expected at least one table line with a separator, got:\n" + console.getOutput());
	}

	@Test
	void warnsForAConnectionThatDoesNotExist() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault();
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowGroup cmd = CommandTestSupport.create(CommandShowGroup.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("SHOW GROUP DOESNOTEXIST");

		Assertions.assertTrue(console.getOutput().contains("does not exist"),
				"expected the unknown-connection warning, got:\n" + console.getOutput());
	}
}
