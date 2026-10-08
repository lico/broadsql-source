package com.upandcoding.broadsql.controller.shell.style;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.ShellPromptBuilder;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;

/**
 * SPRINT 2409K: the centralized semantic messages of {@code ShellConsole} ({@code error}/{@code warn}/
 * {@code info}/{@code success}) and the styled prompt: unchanged text, escape sequences only when styling is
 * on, never in plain/redirected output.
 */
class TestSemanticMessagesAndPrompt {

	@AfterEach
	void reset() {
		TerminalStyleHolder.set(null);
	}

	private static String plain(String s) {
		return TestTerminalStyle.ANSI.matcher(s).replaceAll("");
	}

	private static String printAll(CapturingShellConsole console) {
		console.error("bad", false);
		console.warn("careful", false);
		console.info("note", false);
		console.successln("done");
		console.writeln("regular output");
		return console.getOutput();
	}

	@Test
	void withStylingOffMessagesAreExactlyTheLegacyText() {
		TerminalStyleHolder.set(TerminalStyle.PLAIN);
		String out = printAll(new CapturingShellConsole());
		Assertions.assertFalse(out.contains("\u001B"), out);
		Assertions.assertEquals("ERROR: bad\nWARNING: careful\nINFO: note\ndone\nregular output\n", out.replace("\r\n", "\n"));
	}

	@Test
	void withStylingOnEachMessageIsStyledAndKeepsItsText() throws Exception {
		TerminalStyleHolder.set(TerminalStyle.resolve(ColorMode.ON, Theme.named(Theme.DEFAULT_DARK), TestTerminalStyle.colorTerminal()));
		String out = printAll(new CapturingShellConsole());
		Assertions.assertTrue(out.contains("\u001B["), out);
		Assertions.assertEquals("ERROR: bad\nWARNING: careful\nINFO: note\ndone\nregular output\n", plain(out).replace("\r\n", "\n"));
		for (String line : out.replace("\r\n", "\n").split("\n")) {
			if (line.contains("regular output")) {
				Assertions.assertFalse(line.contains("\u001B"), "ordinary output is not a semantic message: " + line);
			}
		}
		Assertions.assertFalse(out.contains("\u001B[0m\u001B"), "no style should bleed across the line break");
	}

	@Test
	void theStyledPromptHasExactlyThePlainPromptText() throws Exception {
		TerminalStyle on = TerminalStyle.resolve(ColorMode.ON, Theme.named(Theme.DEFAULT_DARK), TestTerminalStyle.colorTerminal());
		for (String prompt : new String[] { "DEVDB> ", "DEVDB [API crm:Staging]> ", "[API crm:Staging]> ", "BroadSQL> ", "odd prompt" }) {
			Assertions.assertEquals(prompt, plain(ShellPromptBuilder.style(prompt, on, false)), prompt);
			Assertions.assertEquals(prompt, plain(ShellPromptBuilder.style(prompt, on, true)), prompt);
			Assertions.assertSame(prompt, ShellPromptBuilder.style(prompt, TerminalStyle.PLAIN, true), "styling off returns the prompt itself");
		}
	}

	@Test
	void aProductionConnectionUsesThePromptProductionRole() throws Exception {
		TerminalStyle on = TerminalStyle.resolve(ColorMode.ON, Theme.named(Theme.DEFAULT_DARK), TestTerminalStyle.colorTerminal());
		String production = ShellPromptBuilder.style("PRODDB> ", on, true);
		String normal = ShellPromptBuilder.style("PRODDB> ", on, false);
		Assertions.assertTrue(production.startsWith(on.render(StyleRole.PROMPT_PRODUCTION, "PRODDB")), production.replace("\u001B", "ESC"));
		Assertions.assertTrue(normal.startsWith(on.render(StyleRole.PROMPT_CONNECTION, "PRODDB")), normal.replace("\u001B", "ESC"));
		Assertions.assertTrue(ShellPromptBuilder.style("DB [API crm:Staging]> ", on, false).contains(on.render(StyleRole.PROMPT_ENVIRONMENT, "Staging")));
	}
}
