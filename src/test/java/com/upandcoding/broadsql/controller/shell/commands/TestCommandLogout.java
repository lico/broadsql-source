package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandLogout;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;

/**
 * {@code LOGOUT} catches every exception from {@code Session.authenticate()} broadly
 * ({@code catch (Exception ce) { ce.printStackTrace(); }}) and never rethrows - so it cannot throw
 * even with no session wired at all, which is exactly what this test locks in. This also means a
 * genuine re-authentication failure is silently swallowed rather than shown to the user; a real
 * console/session-backed re-authentication flow is out of scope for this pass (needs a real console,
 * see {@code docs/TESTS_STRATEGY.md}).
 */
class TestCommandLogout {

	@Test
	void neverThrowsEvenWithNoSessionWired() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLogout cmd = CommandTestSupport.create(CommandLogout.class, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("LOGOUT"));
	}
}
