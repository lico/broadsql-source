package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.set.CommandSetTraceMode;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;

/**
 * {@link CommandSetTraceMode} needs no database - it only flips a local/console log-to-file flag.
 * Each test starts from a fresh console with {@code printToLogFile} still {@code false} and ends
 * before it would ever become {@code true} at print time - {@code ShellConsole.print()} only reaches
 * into {@code consoleLogger} (which actually writes a file, and is not wired here) when its own
 * {@code printToLogFile} is already {@code true} *before* the print call, which never happens in
 * either test below (only the on-branch's trailing {@code console.setPrintToLogFile(true)} would ever
 * set it, and nothing prints afterward in that same test).
 */
class TestCommandSetTraceMode {

	@Test
	void turnsTraceModeOn() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandSetTraceMode cmd = CommandTestSupport.create(CommandSetTraceMode.class, console);

		cmd.execute("SET TRACE");

		Assertions.assertTrue(cmd.isPrintToLogFile());
		Assertions.assertTrue(console.isPrintToLogFile());
		Assertions.assertTrue(console.getOutput().contains("Toggle trace mode ON"),
				"expected the trace-on message, got:\n" + console.getOutput());
	}

	@Test
	void turnsTraceModeOff() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandSetTraceMode cmd = CommandTestSupport.create(CommandSetTraceMode.class, console);
		cmd.setPrintToLogFile(true);

		cmd.execute("SET TRACE");

		Assertions.assertFalse(cmd.isPrintToLogFile());
		Assertions.assertFalse(console.isPrintToLogFile());
		Assertions.assertTrue(console.getOutput().contains("Toggle trace mode OFF"),
				"expected the trace-off message, got:\n" + console.getOutput());
	}
}
