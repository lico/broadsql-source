package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowAllEnvironments;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;

/**
 * SPRINT XT02B acceptance correction: {@code SHOW ALL ENVIRONMENTS} ({@code SHALENV}) had both a
 * missing leading table border pipe and prompted every table row (via {@code console.println}'s
 * default {@code displayPrompt=true}) - see {@link TestCommandShowAllConnections#assertPromptFreeBorderedTable}.
 */
class TestCommandShowAllEnvironments {

	@Test
	void listsEveryEnvironmentWithNoArgument() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveEnvironment(new EnvironmentDefinition("DEV", "Development", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("PROD", "Production", true, null, DatabaseDefinition.STATUS_ACTIVE));

		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowAllEnvironments cmd = CommandTestSupport.create(CommandShowAllEnvironments.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("SHOW ALL ENVIRONMENTS");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("DEV"), "expected DEV in output, got:\n" + output);
		Assertions.assertTrue(output.contains("PROD"), "expected PROD in output, got:\n" + output);
		// newFileBackedVault() always seeds the permanent default LOCAL environment
		// (DatabaseDefinitionsVault.LOCAL_ENVIRONMENT_ID) in addition to DEV/PROD saved above.
		Assertions.assertTrue(output.contains("LOCAL"), "expected the seeded LOCAL environment, got:\n" + output);
		Assertions.assertTrue(output.contains("3 environment(s) found"), "expected a count of 3, got:\n" + output);
		TestCommandShowAllConnections.assertPromptFreeBorderedTable(console);
	}

	@Test
	void theShalenvSynonymWorks() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveEnvironment(new EnvironmentDefinition("DEV", "Development", false, null, DatabaseDefinition.STATUS_ACTIVE));

		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowAllEnvironments cmd = CommandTestSupport.create(CommandShowAllEnvironments.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("SHALENV");

		Assertions.assertTrue(console.getOutput().contains("DEV"), "got:\n" + console.getOutput());
	}
}
