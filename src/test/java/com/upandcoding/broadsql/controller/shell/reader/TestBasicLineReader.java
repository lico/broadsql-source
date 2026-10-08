package com.upandcoding.broadsql.controller.shell.reader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * SPRINT XT02B, section 1.1 regression: {@code System.console()} is legitimately {@code null} whenever
 * there is no real attached terminal - this project's own environment notes, and every headless
 * {@code mvn test} run, including this one. The pre-sprint inline call site
 * ({@code ShellConsole#readPassword()}) always null-checked {@code System.console()} before calling
 * {@code readPassword} on it; moving that call behind {@link BasicLineReader} dropped the guard in a
 * first pass and broke {@code TestCommandSetMasterPassword}/{@code TestCommandSetConnectionPassword},
 * which both rely on a {@code null} console producing a {@code null} password (not a
 * {@link NullPointerException}) so the command can go on to fail for its own, more specific reason.
 */
class TestBasicLineReader {

	@Test
	void readPasswordReturnsNullRatherThanThrowingWhenThereIsNoAttachedConsole() {
		BasicLineReader reader = new BasicLineReader(null);

		Assertions.assertNull(reader.readPassword("[Enter password]"));
	}
}
