package com.upandcoding.broadsql.controller.shell;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * GitHub #152 - {@link ConsoleSettings#getDefaultFileFormatInvalidValue()}, the accessor
 * {@code CommandPull} uses to tell an absent {@code DefaultFileFormat} (silently resolves to ODS,
 * unchanged DUMP/EXPORT behavior established 27/08/2026) apart from a present but unrecognized one (a
 * real misconfiguration PULL's implicit-{@code AS <format>} form refuses outright). Reuses the exact
 * same {@code defaultFileFormatRaw}/{@code resolveDefaultFileFormat()} state {@link #getDefaultFileFormat()}
 * and {@link #getDefaultFileFormatStartupNotice()} already resolve - no second setting introduced.
 */
class TestConsoleSettingsDefaultFileFormatInvalidValue {

	@Test
	void aBlankOrAbsentValueIsNotReportedAsInvalid() {
		ConsoleSettings settings = new ConsoleSettings();
		Assertions.assertNull(settings.getDefaultFileFormatInvalidValue());
		// and it still silently resolves to the existing ODS fallback, unchanged
		Assertions.assertEquals(ConsoleSettings.FILE_FORMAT_ODS, settings.getDefaultFileFormat());
	}

	@Test
	void aValidValueIsNotReportedAsInvalid() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setDefaultFileFormatRaw("XLSX");
		Assertions.assertNull(settings.getDefaultFileFormatInvalidValue());
		Assertions.assertEquals("XLSX", settings.getDefaultFileFormat());
	}

	@Test
	void aValidValueIsCaseInsensitive() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setDefaultFileFormatRaw("xlsx");
		Assertions.assertNull(settings.getDefaultFileFormatInvalidValue());
	}

	@Test
	void aPresentButUnrecognizedValueIsReportedExactlyAsTyped() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setDefaultFileFormatRaw("BOGUS");
		Assertions.assertEquals("BOGUS", settings.getDefaultFileFormatInvalidValue());
		// existing DUMP/EXPORT behavior is unaffected: still silently falls back to ODS for them
		Assertions.assertEquals(ConsoleSettings.FILE_FORMAT_ODS, settings.getDefaultFileFormat());
	}

	@Test
	void h2IsNotASupportedDefaultFileFormatValueEitherEvenThoughPullAcceptsAsH2() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setDefaultFileFormatRaw("H2");
		Assertions.assertEquals("H2", settings.getDefaultFileFormatInvalidValue());
	}

	@Test
	void aBlankStringIsTreatedTheSameAsAbsent() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setDefaultFileFormatRaw("   ");
		Assertions.assertNull(settings.getDefaultFileFormatInvalidValue());
	}
}
