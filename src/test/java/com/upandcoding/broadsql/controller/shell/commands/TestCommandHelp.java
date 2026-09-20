package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.help.CommandHelp;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * {@code HELP} (no argument) reaches through
 * {@code getConsoleCommandInterpreter().getCommands().getConsoleCommandLoader()} to enumerate
 * commands - the one command that needs the {@code CommandInterpreter}/{@code CommandList}/
 * {@code CommandLoader} chain wired, see {@link CommandTestSupport#createCommandInterpreter}.
 *
 * <p>{@code CommandLoader.loadAvailableCommands()} scans a literal {@code lib/broadsql.jar} file
 * path (the packaged release layout), which does not exist under {@code mvn test} - this is handled
 * as a real, already-existing robustness feature (a missing/unreadable jar is logged and skipped,
 * not fatal - see {@code docs/P0-commandes.md}, "A. Chargement robuste par JAR"), so {@code HELP}
 * still completes with an empty command list rather than throwing.
 */
class TestCommandHelp {

	@Test
	void printsHelpWithoutThrowingEvenWithNoPackagedJarOnDisk() {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		CapturingShellConsole console = new CapturingShellConsole();
		CommandHelp cmd = CommandTestSupport.create(CommandHelp.class, null, console, settings);
		cmd.setConsoleCommandInterpreter(CommandTestSupport.createCommandInterpreter(settings, console));

		Assertions.assertDoesNotThrow(() -> cmd.execute(""));

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Specific help can be obtained by typing HELP"),
				"expected the help header, got:\n" + output);
		Assertions.assertTrue(output.contains("MACRO COMMANDS"), "expected the macro commands footer, got:\n" + output);
	}
}
