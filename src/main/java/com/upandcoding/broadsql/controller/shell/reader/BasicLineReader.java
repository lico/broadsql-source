package com.upandcoding.broadsql.controller.shell.reader;

import java.io.Console;

/**
 * The original, unchanged {@code java.io.Console}-based line reader - SPRINT XT02A (URL-Native API
 * Execution), section 15's "JLine OFF behavior": every command must work exactly as before through
 * this class, since it is what an explicit {@code activatejline=OFF} (JLine is the default since SPRINT 2209C) and every JLine
 * initialization failure fall back to. Byte-for-byte the same two calls
 * {@code ShellConsole#readLine(boolean)} always made directly on {@link Console} before this sprint -
 * this class only moves them behind {@link ConsoleLineReader} so {@code ShellConsole} can hold either
 * implementation interchangeably.
 */
public final class BasicLineReader implements ConsoleLineReader {

	private final Console console;

	public BasicLineReader(Console console) {
		this.console = console;
	}

	@Override
	public String readLine(String prompt) {
		return (prompt == null || prompt.isEmpty()) ? console.readLine() : console.readLine(prompt);
	}

	/**
	 * Byte-for-byte the same {@code Console.readPassword("[%s]", "Enter password")} call this project
	 * always made directly - only its call site moved, behind this interface. Preserves the previous
	 * {@code System.console() != null} guard the old inline call site had: {@code console} is
	 * legitimately {@code null} whenever there is no real attached terminal (this project's own
	 * environment notes - no interactive terminal in this dev environment - and every headless test
	 * run), and every caller of {@code ShellConsole#readPassword()} already tolerates a {@code null}
	 * result exactly as before.
	 */
	@Override
	public char[] readPassword(String prompt) {
		return console == null ? null : console.readPassword("[%s]", "Enter password");
	}
}
