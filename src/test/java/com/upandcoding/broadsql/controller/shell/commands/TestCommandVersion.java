package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.help.CommandVersion;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.controller.shell.output.ConsoleUtils;

/** {@link CommandVersion} needs no database - it only echoes {@link ConsoleUtils#getTitleAndVersion()}. */
class TestCommandVersion {

	@Test
	void displaysTheTitleAndVersion() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandVersion cmd = CommandTestSupport.create(CommandVersion.class, console);

		cmd.execute("VERSION");

		Assertions.assertTrue(console.getOutput().contains(ConsoleUtils.getTitleAndVersion()),
				"expected the title and version, got:\n" + console.getOutput());
	}
}
