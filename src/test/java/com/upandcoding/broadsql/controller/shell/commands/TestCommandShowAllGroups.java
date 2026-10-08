package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowAllGroups;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.DatabaseGroupDefinition;

/**
 * SPRINT XT02B acceptance correction: {@code SHOW ALL GROUPS} ({@code SHALGR}) had both a missing
 * leading table border pipe and prompted every table row (via {@code console.println}'s default
 * {@code displayPrompt=true}) - see {@link TestCommandShowAllConnections#assertPromptFreeBorderedTable}.
 */
class TestCommandShowAllGroups {

	@Test
	void listsEveryGroupWithNoArgument() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveGroup(new DatabaseGroupDefinition("DESK", "Desk", null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveGroup(new DatabaseGroupDefinition("SALES", "Sales", null, DatabaseDefinition.STATUS_ACTIVE));

		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowAllGroups cmd = CommandTestSupport.create(CommandShowAllGroups.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("SHOW ALL GROUPS");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("DESK"), "expected DESK in output, got:\n" + output);
		Assertions.assertTrue(output.contains("SALES"), "expected SALES in output, got:\n" + output);
		Assertions.assertTrue(output.contains("2 database group(s) found"), "expected a count of 2, got:\n" + output);
		TestCommandShowAllConnections.assertPromptFreeBorderedTable(console);
	}

	@Test
	void theShalgrSynonymWorks() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveGroup(new DatabaseGroupDefinition("DESK", "Desk", null, DatabaseDefinition.STATUS_ACTIVE));

		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowAllGroups cmd = CommandTestSupport.create(CommandShowAllGroups.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("SHALGR");

		Assertions.assertTrue(console.getOutput().contains("DESK"), "got:\n" + console.getOutput());
	}
}
