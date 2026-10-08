package com.upandcoding.broadsql.controller.shell.output;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

/**
 * A {@link ShellConsole} that captures everything written to it in memory instead of writing to
 * {@code System.out} - the main-source counterpart of the test-only {@code CapturingShellConsole}
 * (same technique, same Cp850 encoding as the real console). Used by the Script Library's
 * {@code ScriptRunCoordinator} (SPRINT 0917-01) to capture {@code Run}'s output for display inside the
 * GUI - see that class's own javadoc for why execution output must be captured rather than written to
 * whatever the real interactive terminal happens to be.
 *
 * <p>SPRINT 3009A: the captured text is shown in a Swing text component, not a terminal, so it is plain text
 * ({@link #rendersTerminalStyles()} is {@code false}: no ANSI color sequences, no styled prompt), and a capture
 * that stands in for the application's console ({@link #standingInFor}) keeps that console's log file writer,
 * prompt and log switch, so logging during an Editor Run works as during {@code LIB RUN}.
 */
public class CapturingConsole extends ShellConsole {

	private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
	private int errorCount;

	public CapturingConsole() {
		super();
		try {
			this.writeConsole = new PrintStream(buffer, true, "Cp850");
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	/** A capture that replaces {@code original} for a while: see {@link ShellConsole#adoptSessionOf}. {@code original} may be {@code null} (nothing to adopt). */
	public static CapturingConsole standingInFor(ShellConsole original) {
		CapturingConsole capture = new CapturingConsole();
		if (original != null) {
			capture.adoptSessionOf(original);
		}
		return capture;
	}

	@Override
	protected boolean rendersTerminalStyles() {
		return false;
	}

	@Override
	protected void noteMessage(com.upandcoding.broadsql.controller.shell.style.StyleRole role) {
		if (role == com.upandcoding.broadsql.controller.shell.style.StyleRole.ERROR) {
			errorCount++;
		}
	}

	/** How many error messages were written (a statement of a Script that failed is reported, not thrown). */
	public int getErrorCount() {
		return errorCount;
	}

	/** Everything written so far via {@code print}/{@code println}/{@code write}/{@code writeln}, in order, prompts included. */
	public String getOutput() {
		try {
			return buffer.toString("Cp850");
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}
}
