package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandConfig;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.sun.jna.Platform;

/**
 * {@code CONFIG} opens a real Swing GUI ({@code JSettingsFrame}) when running on Windows and
 * connected to {@code $CDF} - that path is deliberately not exercised here, a test must never pop a
 * real window (see {@code docs/TESTS_STRATEGY.md}, "Out of scope"). Only the guard clause is
 * covered: refusing to run outside of the {@code $CDF} connection.
 */
class TestCommandConfig {

	@Test
	void refusesToRunWhenNotConnectedToTheCdf() {
		Assumptions.assumeTrue(Platform.isWindows(), "CommandConfig's non-Windows branch is not covered here");

		CapturingShellConsole console = new CapturingShellConsole();
		CommandConfig cmd = CommandTestSupport.create(CommandConfig.class, console);
		cmd.setPlatform("WORLD");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute(""));

		Assertions.assertEquals("Settings can only modified when connected to the CDF database", ex.getLocalizedMessage());
	}
}
