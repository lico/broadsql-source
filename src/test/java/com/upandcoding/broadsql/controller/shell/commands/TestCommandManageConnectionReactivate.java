package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageConnectionReactivate;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Covers {@code REACTIVATE CONNECTION <id>}'s non-interactive branches - the confirmation prompt
 * itself ({@code console.inputField(..., isYesNo=true, ...)}) reads from {@code System.console()}
 * through the real {@link com.upandcoding.broadsql.controller.shell.output.ShellConsole#readLine}, which
 * {@link CapturingShellConsole} does not stub out (see CLAUDE.md, "No interactive terminal available
 * in this dev environment") - consistent with the rest of the {@code core.config} package (ADD/EDIT/DEL
 * CONNECTION), which has no interactive-prompt test coverage either. What's covered here is exactly
 * what was explicitly asked for: a clear, distinct message for "no such ID at all" vs. "already active",
 * both resolved before the command ever reaches the confirmation prompt.
 */
class TestCommandManageConnectionReactivate {

	private CapturingShellConsole console;
	private DatabaseDefinitionsVault vault;

	@BeforeEach
	void setUp() throws BroadSQLException {
		vault = TestDatabaseConnections.newFileBackedVault();
		console = new CapturingShellConsole();
	}

	private CommandManageConnectionReactivate newCommand() {
		CommandManageConnectionReactivate cmd = CommandTestSupport.create(CommandManageConnectionReactivate.class, console);
		cmd.setDatabaseConnectionsVault(vault);
		return cmd;
	}

	@Test
	void rejectsBlankId() throws BroadSQLException {
		CommandManageConnectionReactivate cmd = newCommand();

		cmd.execute("REACTIVATE CONNECTION");

		Assertions.assertTrue(console.getOutput().contains(BroadSQLErrorMessages.ERR_CONN_01), "got:\n" + console.getOutput());
	}

	@Test
	void rejectsAnIdThatDoesNotExistAtAll() throws BroadSQLException {
		CommandManageConnectionReactivate cmd = newCommand();

		cmd.execute("REACTIVATE CONNECTION GHOST");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains(BroadSQLErrorMessages.ERR_CONN_04), "expected the 'cannot find this ID' message, got:\n" + output);
		Assertions.assertTrue(output.contains("GHOST"), "the error should name the ID that was not found, got:\n" + output);
	}

	@Test
	void reportsAnAlreadyActiveConnectionWithoutPromptingAndDoesNotThrow() throws BroadSQLException {
		DatabaseDefinition active = new DatabaseDefinition("MYDB01", "H2", "org.h2.Driver", "jdbc:h2:mem:mydb01", "sa", "sa", "MyDB");
		active.setEnvironment("LOCAL");
		vault.saveDatabaseDefinition(active);
		vault.load(); // saveDatabaseDefinition() does not reload the vault itself
		CommandManageConnectionReactivate cmd = newCommand();

		Assertions.assertDoesNotThrow(() -> cmd.execute("REACTIVATE CONNECTION MYDB01"));

		Assertions.assertTrue(console.getOutput().contains("is already active"), "got:\n" + console.getOutput());
	}

	/**
	 * The "inactive, not missing" distinction itself (the whole point of {@link BroadSQLErrorMessages
	 * #ERR_CONN_04} only firing for a truly unknown ID) is covered at the DAO level in
	 * {@code TestDatabaseDefinitionsVaultReactivation#isInactiveConnectionDistinguishesInactiveFromUnknown}
	 * rather than here: reaching that branch in the command itself means proceeding to the confirmation
	 * prompt, which reads through {@code System.console()} - {@code null} in this environment (see
	 * CLAUDE.md) - and would NPE, exactly like the rest of {@code core.config}'s interactive prompts
	 * (ADD/EDIT/DEL CONNECTION) have no test coverage for the same reason.
	 */
}
