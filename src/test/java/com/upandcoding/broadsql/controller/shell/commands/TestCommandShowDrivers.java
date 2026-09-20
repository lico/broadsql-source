package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowDrivers;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;

/**
 * {@link CommandShowDrivers} scans {@code drivers} and {@code lib} folders relative to the current
 * working directory - which is the repository root under Maven Surefire, and has neither folder, so
 * "0 drivers found" is the real, deterministic outcome here (not a stand-in for "not tested").
 */
class TestCommandShowDrivers {

	@Test
	void reportsNoDriversFoundOutsideAPackagedInstall() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowDrivers cmd = CommandTestSupport.create(CommandShowDrivers.class, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute(""));

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("0 drivers found"), "expected 0 drivers found, got:\n" + output);
	}
}
