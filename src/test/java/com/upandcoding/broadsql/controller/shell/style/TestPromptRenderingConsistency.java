package com.upandcoding.broadsql.controller.shell.style;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.controller.shell.reader.ConsoleLineReader;

/**
 * The primary prompt has one rendering: the prompt passed to the line reader and the prompt prefix of every
 * message printed with {@code displayPrompt} (including the blank {@code println("")} lines that end most
 * commands) are the same characters, for every theme and color setting. Before, the prefixes printed the
 * plain prompt while the input prompt was styled, so two different prompts appeared one above the other.
 */
class TestPromptRenderingConsistency {

	private static final String PROMPT = "DEVDB> ";

	/** Records the prompt the console hands to the line reader, the way JLine would draw it. */
	private static final class RecordingReader implements ConsoleLineReader {
		String prompt;

		@Override
		public String readLine(String prompt) {
			this.prompt = prompt;
			return "";
		}

		@Override
		public char[] readPassword(String prompt) {
			return new char[0];
		}
	}

	@AfterEach
	void reset() {
		TerminalStyleHolder.set(null);
	}

	/** The input prompt, then the prefix of a prompt-prefixed message and of a blank prompt line, under {@code style}. */
	private static String[] render(TerminalStyle style, boolean production) {
		TerminalStyleHolder.set(style);
		CapturingShellConsole console = new CapturingShellConsole();
		console.setPrompt(PROMPT);
		console.setProductionIndicator(() -> production);
		RecordingReader reader = new RecordingReader();
		console.setLineReader(reader);
		console.readLine(true);
		console.println("");
		String blankLine = console.getOutput().replace("\r\n", "\n").split("\n", -1)[0];
		console.clear();
		console.print("rows updated", true);
		String message = console.getOutput();
		return new String[] { reader.prompt, blankLine, message };
	}

	private static TerminalStyle on(String theme) throws Exception {
		return TerminalStyle.resolve(ColorMode.ON, Theme.named(theme), TestTerminalStyle.colorTerminal());
	}

	@Test
	void everyThemedPromptIsRenderedIdenticallyForInputAndForMessages() throws Exception {
		for (String theme : new String[] { Theme.DEFAULT_DARK, Theme.DEFAULT_LIGHT, Theme.MONO }) {
			for (boolean production : new boolean[] { false, true }) {
				String[] out = render(on(theme), production);
				String input = out[0];
				Assertions.assertEquals(PROMPT, TestTerminalStyle.ANSI.matcher(input).replaceAll(""), theme);
				Assertions.assertTrue(input.contains("\u001B["), theme + ": a theme styles the prompt");
				Assertions.assertEquals(input, out[1], theme + " production=" + production + ": the blank prompt line differs from the input prompt");
				Assertions.assertTrue(out[2].startsWith(input), theme + " production=" + production + ": a message prefix differs from the input prompt");
			}
		}
	}

	@Test
	void themeNoneAndColorOffPrintThePlainPromptWithoutAnyEscapeSequence() throws Exception {
		TerminalStyle none = on(Theme.NONE);
		TerminalStyle off = TerminalStyle.resolve(ColorMode.OFF, Theme.named(Theme.DEFAULT_DARK), TestTerminalStyle.colorTerminal());
		for (TerminalStyle style : new TerminalStyle[] { none, off, TerminalStyle.PLAIN }) {
			for (boolean production : new boolean[] { false, true }) {
				String[] out = render(style, production);
				Assertions.assertEquals(PROMPT, out[0]);
				Assertions.assertEquals(PROMPT, out[1]);
				Assertions.assertEquals(PROMPT + "rows updated", out[2]);
			}
		}
	}
}
