package com.upandcoding.broadsql.controller.shell.style;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.terminal.impl.ExternalTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * SPRINT 2409K: when styling is on ({@code color} x {@code theme} x what the terminal reports), what the
 * built-in themes contain, and that styling off never emits an escape sequence.
 */
class TestTerminalStyle {

	static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

	@AfterEach
	void reset() {
		TerminalStyleHolder.set(null);
	}

	static Terminal colorTerminal() throws Exception {
		return new ExternalTerminal("test", "xterm-256color", new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), StandardCharsets.UTF_8);
	}

	static Terminal dumbTerminal() throws Exception {
		return new DumbTerminal("test", Terminal.TYPE_DUMB, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), StandardCharsets.UTF_8);
	}

	@Test
	void theSevenBuiltInThemesExist() {
		Assertions.assertEquals(List.of("default-dark", "default-light", "classic", "high-contrast-dark", "high-contrast-light", "mono", "none"), Theme.names());
		Assertions.assertEquals("default-dark", Theme.named(" Default-Dark ").getName());
		Assertions.assertNull(Theme.named("solarized"));
		Assertions.assertTrue(Theme.named("none").isLegacy());
	}

	@Test
	void autoStylesOnlyAColorTerminal() throws Exception {
		Theme theme = Theme.named(Theme.DEFAULT);
		Assertions.assertTrue(TerminalStyle.resolve(ColorMode.AUTO, theme, colorTerminal()).isEnabled());
		Assertions.assertFalse(TerminalStyle.resolve(ColorMode.AUTO, theme, dumbTerminal()).isEnabled(), "dumb terminal (redirected output)");
		Assertions.assertFalse(TerminalStyle.resolve(ColorMode.AUTO, theme, null).isEnabled(), "no JLine terminal (activatejline=OFF)");
	}

	@Test
	void onForcesAndOffSuppresses() throws Exception {
		Theme theme = Theme.named(Theme.DEFAULT);
		Assertions.assertTrue(TerminalStyle.resolve(ColorMode.ON, theme, null).isEnabled());
		Assertions.assertTrue(TerminalStyle.resolve(ColorMode.ON, theme, dumbTerminal()).isEnabled());
		Assertions.assertFalse(TerminalStyle.resolve(ColorMode.OFF, theme, colorTerminal()).isEnabled());
	}

	@Test
	void themeNoneIsNeverStyledWhateverTheColorMode() throws Exception {
		for (ColorMode mode : ColorMode.values()) {
			TerminalStyle style = TerminalStyle.resolve(mode, Theme.named(Theme.NONE), colorTerminal());
			Assertions.assertFalse(style.isEnabled(), mode.name());
			Assertions.assertFalse(style.highlightsInput(), mode.name());
			Assertions.assertEquals("ERROR: x", style.render(StyleRole.ERROR, "ERROR: x"));
		}
	}

	@Test
	void styledTextKeepsItsCharactersAndOffIsExactlyTheInput() throws Exception {
		TerminalStyle on = TerminalStyle.resolve(ColorMode.ON, Theme.named(Theme.DEFAULT_DARK), colorTerminal());
		String rendered = on.render(StyleRole.ERROR, "ERROR: boom");
		Assertions.assertTrue(ANSI.matcher(rendered).find(), rendered);
		Assertions.assertEquals("ERROR: boom", ANSI.matcher(rendered).replaceAll(""));
		Assertions.assertSame("ERROR: boom", TerminalStyle.PLAIN.render(StyleRole.ERROR, "ERROR: boom"));
	}

	@Test
	void everyColorThemeStylesTheMessageRolesAndMonoUsesNoColor() throws Exception {
		for (String name : Theme.names()) {
			if (name.equals(Theme.NONE)) {
				continue;
			}
			TerminalStyle style = TerminalStyle.resolve(ColorMode.ON, Theme.named(name), colorTerminal());
			Assertions.assertNotEquals("E", style.render(StyleRole.ERROR, "E"), name + " must style ERROR");
			Assertions.assertNotEquals("K", style.render(StyleRole.SQL_KEYWORD, "K"), name + " must style SQL_KEYWORD");
		}
		TerminalStyle mono = TerminalStyle.resolve(ColorMode.ON, Theme.named(Theme.MONO), colorTerminal());
		for (StyleRole role : StyleRole.values()) {
			String rendered = mono.render(role, "x");
			Assertions.assertFalse(Pattern.compile("\u001B\\[[0-9;]*(3[0-7]|9[0-7]|4[0-7]|10[0-7])[;m]").matcher(rendered).find(),
					"mono must not use colors: " + role + " -> " + rendered.replace("\u001B", "ESC"));
		}
	}
}
