package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.commands.extensions.CommandScriptEngines;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;

/** {@link CommandScriptEngines} needs no database - it only inspects the running JVM. */
class TestCommandEngines {

	@Test
	void listsScriptEnginesWithoutThrowing() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandScriptEngines cmd = CommandTestSupport.create(CommandScriptEngines.class, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute(""));

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Supported Script Engines:"),
				"expected the script engines header, got:\n" + output);
	}
}
