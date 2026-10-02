package com.upandcoding.broadsql.controller.shell.output;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.reader.ConsoleLineReader;

/**
 * SPRINT XT02A (URL-Native API Execution) corrective pass, section 12.2 - {@link ShellConsole#readLine}
 * must delegate to whichever {@link ConsoleLineReader} is installed, and command execution semantics
 * (what {@code readLine} returns for a given typed line) must be identical regardless of which
 * implementation produced it - this test proves the delegation itself, independently of whether the
 * installed reader is {@code BasicLineReader} or a JLine-backed one.
 */
class TestShellConsoleLineReader {

	private static final class RecordingLineReader implements ConsoleLineReader {
		String lastPrompt;
		String nextLine;
		String lastPasswordPrompt;
		char[] nextPassword;

		@Override
		public String readLine(String prompt) {
			lastPrompt = prompt;
			return nextLine;
		}

		@Override
		public char[] readPassword(String prompt) {
			lastPasswordPrompt = prompt;
			return nextPassword;
		}
	}

	@Test
	void readLineDelegatesToTheInstalledReaderWithThePromptWhenRequested() {
		ShellConsole console = new ShellConsole();
		RecordingLineReader fake = new RecordingLineReader();
		fake.nextLine = "RUN /api/customer/123";
		console.setLineReader(fake);
		console.setPrompt("BroadSQL> ");

		String result = console.readLine(true);

		Assertions.assertEquals("RUN /api/customer/123", result, "readLine must return exactly what the installed reader returns - execution semantics must not depend on which reader is active");
		Assertions.assertEquals("BroadSQL> ", fake.lastPrompt);
	}

	@Test
	void readLineDelegatesWithNoPromptWhenNotRequested() {
		ShellConsole console = new ShellConsole();
		RecordingLineReader fake = new RecordingLineReader();
		fake.nextLine = "VAR ID=123";
		console.setLineReader(fake);
		console.setPrompt("BroadSQL> ");

		String result = console.readLine(false);

		Assertions.assertEquals("VAR ID=123", result);
		Assertions.assertEquals("", fake.lastPrompt, "displayPrompt=false must pass an empty prompt, exactly like the pre-XT02A Console#readLine() no-arg call");
	}

	@Test
	void installingNullResetsToTheDefaultBasicLineReaderRatherThanNpeLater() {
		ShellConsole console = new ShellConsole();
		Assertions.assertDoesNotThrow(() -> console.setLineReader(null));
	}

	/** SPRINT XT02B, section 1.1: readPassword() now delegates to the installed reader too, not straight through System.console(). */
	@Test
	void readPasswordDelegatesToTheInstalledReader() {
		ShellConsole console = new ShellConsole();
		RecordingLineReader fake = new RecordingLineReader();
		fake.nextPassword = "s3cr3t".toCharArray();
		console.setLineReader(fake);

		String result = console.readPassword();

		Assertions.assertEquals("s3cr3t", result);
		Assertions.assertNotNull(fake.lastPasswordPrompt);
	}

	@Test
	void readPasswordReturnsNullWhenTheInstalledReaderReturnsNull() {
		ShellConsole console = new ShellConsole();
		RecordingLineReader fake = new RecordingLineReader();
		fake.nextPassword = null;
		console.setLineReader(fake);

		Assertions.assertNull(console.readPassword());
	}

	@Test
	void closeDelegatesToTheInstalledReader() {
		ShellConsole console = new ShellConsole();
		java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
		ConsoleLineReader fake = new ConsoleLineReader() {
			@Override
			public String readLine(String prompt) {
				return null;
			}

			@Override
			public char[] readPassword(String prompt) {
				return null;
			}

			@Override
			public void close() {
				closed.set(true);
			}
		};
		console.setLineReader(fake);

		console.close();

		Assertions.assertTrue(closed.get());
	}
}
