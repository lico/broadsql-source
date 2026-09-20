package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.misc.CommandPrint;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;

/** {@link CommandPrint} needs no database at all - a pure console command. */
class TestCommandPrint {

	@Test
	void printsUnquotedTextRejoinedWithCommas() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandPrint cmd = CommandTestSupport.create(CommandPrint.class, console);

		cmd.execute("PRINT Hello World");

		Assertions.assertEquals(ConsoleSettings.DEFAULT_PROMPT + "Hello, World\n", console.getOutput());
	}

	@Test
	void printsQuotedTextVerbatim() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandPrint cmd = CommandTestSupport.create(CommandPrint.class, console);

		cmd.execute("PRINT \"Hello World\"");

		Assertions.assertEquals(ConsoleSettings.DEFAULT_PROMPT + "Hello World\n", console.getOutput());
	}

	@Test
	void printsABlankLineWithNoArgument() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandPrint cmd = CommandTestSupport.create(CommandPrint.class, console);

		cmd.execute("PRINT");

		Assertions.assertEquals(ConsoleSettings.DEFAULT_PROMPT + "\n", console.getOutput());
	}
}
