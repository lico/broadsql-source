package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.misc.CommandRuntime;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;

/** {@link CommandRuntime} needs no database - it only inspects the JVM or runs an OS command. */
class TestCommandRuntime {

	@Test
	void displaysMemoryWithNoArgument() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandRuntime cmd = CommandTestSupport.create(CommandRuntime.class, console);

		cmd.execute("RUNTIME");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Max memory:"), "expected max memory in output, got:\n" + output);
		Assertions.assertTrue(output.contains("Free memory:"), "expected free memory in output, got:\n" + output);
	}

	@Test
	void runsTheGarbageCollectorAndDisplaysMemory() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandRuntime cmd = CommandTestSupport.create(CommandRuntime.class, console);

		cmd.execute("RUNTIME GC");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Executing the garbage collector"),
				"expected the GC message, got:\n" + output);
		Assertions.assertTrue(output.contains("Max memory:"), "expected max memory in output, got:\n" + output);
	}

	@Test
	void rejectsAnEmptyCmdArgument() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandRuntime cmd = CommandTestSupport.create(CommandRuntime.class, console);

		cmd.execute("RUNTIME CMD");

		Assertions.assertTrue(console.getOutput().contains("You must enter a valid command"),
				"expected the missing-command error, got:\n" + console.getOutput());
	}

	@Test
	void runsAnOsCommandAndPrintsItsOutput() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandRuntime cmd = CommandTestSupport.create(CommandRuntime.class, console);

		cmd.execute("RUNTIME CMD \"echo hello\"");

		Assertions.assertTrue(console.getOutput().contains("hello"),
				"expected the command's output, got:\n" + console.getOutput());
	}
}
