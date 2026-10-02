package com.upandcoding.broadsql.controller.shell.output;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

/**
 * A {@link ShellConsole} that captures everything written to it in memory instead of writing to
 * {@code System.out}, so command tests can assert on the exact text a command printed. Overwrites
 * {@link ShellConsole#writeConsole} right after construction (before anything has been printed) with
 * a buffer using the same Cp850 encoding as the real console, so captured text matches what a real
 * terminal would receive.
 */
public class CapturingShellConsole extends ShellConsole {

	private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

	public CapturingShellConsole() {
		super();
		try {
			this.writeConsole = new PrintStream(buffer, true, "Cp850");
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Everything written so far via {@code print}/{@code println}/{@code write}/{@code writeln}, in
	 * order, prompts included.
	 */
	public String getOutput() {
		try {
			return buffer.toString("Cp850");
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	public void clear() {
		buffer.reset();
	}
}
