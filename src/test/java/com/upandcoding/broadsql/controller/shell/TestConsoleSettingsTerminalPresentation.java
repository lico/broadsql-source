package com.upandcoding.broadsql.controller.shell;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.output.DisplayMode;
import com.upandcoding.broadsql.controller.shell.style.ColorMode;
import com.upandcoding.broadsql.controller.shell.style.Theme;

/**
 * SPRINT 2409K: {@code color}, {@code theme} and {@code displaymode} follow the GitHub #154 settings
 * convention (missing or invalid: documented default plus one startup notice), and the official INI
 * validator knows their allowed values.
 */
class TestConsoleSettingsTerminalPresentation {

	@Test
	void missingKeysUseTheDefaultsWithANotice() {
		ConsoleSettings settings = new ConsoleSettings();
		Assertions.assertEquals(ColorMode.AUTO, settings.getColorMode());
		Assertions.assertEquals(Theme.DEFAULT_DARK, settings.getTheme().getName());
		Assertions.assertEquals(DisplayMode.AUTO, settings.getDisplayMode());
		Assertions.assertEquals("color not found in the INI file, using default AUTO.", settings.getColorStartupNotice());
		Assertions.assertEquals("theme not found in the INI file, using default default-dark.", settings.getThemeStartupNotice());
		Assertions.assertEquals("displaymode not found in the INI file, using default AUTO.", settings.getDisplayModeStartupNotice());
	}

	@Test
	void validValuesAreCaseInsensitiveAndSilent() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setColorRaw(" off ");
		settings.setThemeRaw("Mono");
		settings.setDisplayModeRaw("compact");
		Assertions.assertEquals(ColorMode.OFF, settings.getColorMode());
		Assertions.assertEquals(Theme.MONO, settings.getTheme().getName());
		Assertions.assertEquals(DisplayMode.COMPACT, settings.getDisplayMode());
		Assertions.assertNull(settings.getColorStartupNotice());
		Assertions.assertNull(settings.getThemeStartupNotice());
		Assertions.assertNull(settings.getDisplayModeStartupNotice());
	}

	@Test
	void invalidValuesFallBackWithANoticeNamingTheAllowedValues() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setColorRaw("sometimes");
		settings.setThemeRaw("solarized");
		settings.setDisplayModeRaw("huge");
		Assertions.assertEquals(ColorMode.AUTO, settings.getColorMode());
		Assertions.assertEquals(Theme.DEFAULT_DARK, settings.getTheme().getName());
		Assertions.assertEquals(DisplayMode.AUTO, settings.getDisplayMode());
		Assertions.assertTrue(settings.getThemeStartupNotice().contains("'solarized'") && settings.getThemeStartupNotice().contains("mono"),
				settings.getThemeStartupNotice());
		Assertions.assertTrue(settings.getColorStartupNotice().contains("AUTO, ON or OFF"), settings.getColorStartupNotice());
		Assertions.assertTrue(settings.getDisplayModeStartupNotice().contains("COMPACT"), settings.getDisplayModeStartupNotice());
	}
}
