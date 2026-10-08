package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.commands.core.set.CommandSetListMode;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;

/** {@link CommandSetListMode} needs no database - it only flips the command's own {@code listMode} flag. */
class TestCommandSetListMode {

	@Test
	void turnsListModeOn() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandSetListMode cmd = CommandTestSupport.create(CommandSetListMode.class, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("SET LIST ON"));

		Assertions.assertTrue(cmd.isListMode());
		Assertions.assertTrue(console.getOutput().contains("List mode ON"),
				"expected the list-mode-on message, got:\n" + console.getOutput());
	}

	@Test
	void turnsListModeOff() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandSetListMode cmd = CommandTestSupport.create(CommandSetListMode.class, console);
		cmd.setListMode(true);

		Assertions.assertDoesNotThrow(() -> cmd.execute("SET LIST OFF"));

		Assertions.assertFalse(cmd.isListMode());
		Assertions.assertTrue(console.getOutput().contains("List mode OFF"),
				"expected the list-mode-off message, got:\n" + console.getOutput());
	}

	@Test
	void rejectsAnInvalidMode() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandSetListMode cmd = CommandTestSupport.create(CommandSetListMode.class, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("SET LIST MAYBE"));

		Assertions.assertTrue(console.getOutput().contains("You must specify a valid list mode"),
				"expected the invalid-mode error, got:\n" + console.getOutput());
	}
}
