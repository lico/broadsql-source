package com.upandcoding.broadsql.controller.shell;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * GitHub #154 - {@code MaxRowXLSX} resolution: the sprint's own named example
 * ({@code maxrowxls=format}). Covers the setting's own parsing/validation logic directly (no Spring
 * context - see {@link TestConsoleSettingsIniResilience} for the real-context regression proof), and its
 * distinguishing feature versus the other five hardened settings: an invalid (not merely missing) value
 * is separately exposed via {@link ConsoleSettings#getMaxRowXlsxInvalidValue()} rather than only folded
 * into a generic notice - see {@code CommandDumpTable}, which uses it for the sprint's "feature-local
 * failure" requirement.
 */
class TestConsoleSettingsMaxRowXlsx {

	@Test
	void aValidValueResolvesWithNoNotice() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setMaxRowXlsxRaw("250000");

		Assertions.assertEquals(250000, settings.getMaxRowXlsx());
		Assertions.assertNull(settings.getMaxRowXlsxStartupNotice());
		Assertions.assertNull(settings.getMaxRowXlsxInvalidValue());
	}

	@Test
	void aMissingValueUsesTheDocumentedFallbackWithANotice() {
		ConsoleSettings settings = new ConsoleSettings();

		Assertions.assertEquals(ConsoleSettings.MAX_ROW_XLSX_FALLBACK, settings.getMaxRowXlsx());
		Assertions.assertNotNull(settings.getMaxRowXlsxStartupNotice());
		Assertions.assertTrue(settings.getMaxRowXlsxStartupNotice().contains("MaxRowXLSX"), settings.getMaxRowXlsxStartupNotice());
		Assertions.assertNull(settings.getMaxRowXlsxInvalidValue(), "missing is not \"invalid\"");
	}

	@Test
	void theSprintsOwnNamedExampleIsHandledWithoutThrowing() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setMaxRowXlsxRaw("format");

		Assertions.assertDoesNotThrow(settings::getMaxRowXlsx);
		Assertions.assertEquals(ConsoleSettings.MAX_ROW_XLSX_FALLBACK, settings.getMaxRowXlsx());
		Assertions.assertEquals("format", settings.getMaxRowXlsxInvalidValue());

		String notice = settings.getMaxRowXlsxStartupNotice();
		Assertions.assertNotNull(notice);
		Assertions.assertTrue(notice.contains("MaxRowXLSX"), notice);
		Assertions.assertTrue(notice.contains("format"), notice);
		Assertions.assertTrue(notice.toLowerCase().contains("integer"), notice);
	}

	@Test
	void anExplicitSetterAlwaysWinsAndClearsAnyPriorNotice() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setMaxRowXlsxRaw("format");
		Assertions.assertNotNull(settings.getMaxRowXlsxInvalidValue());

		settings.setMaxRowXlsx(999);

		Assertions.assertEquals(999, settings.getMaxRowXlsx());
		Assertions.assertNull(settings.getMaxRowXlsxStartupNotice());
		Assertions.assertNull(settings.getMaxRowXlsxInvalidValue());
	}

	@Test
	void aBlankValueIsTreatedTheSameAsAbsent() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setMaxRowXlsxRaw("   ");

		Assertions.assertEquals(ConsoleSettings.MAX_ROW_XLSX_FALLBACK, settings.getMaxRowXlsx());
		Assertions.assertNull(settings.getMaxRowXlsxInvalidValue());
	}

	@Test
	void resolutionIsMemoizedNotReparsedOnEveryCall() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setMaxRowXlsxRaw("format");

		String firstNotice = settings.getMaxRowXlsxStartupNotice();
		// Mutating the raw field after first resolution (as a Spring-populated field never would in
		// practice) must not change an already-resolved value - same "resolve once" contract as every
		// other lazily-resolved setting in this class.
		settings.setMaxRowXlsxRaw("123");

		Assertions.assertEquals(ConsoleSettings.MAX_ROW_XLSX_FALLBACK, settings.getMaxRowXlsx());
		Assertions.assertSame(firstNotice, settings.getMaxRowXlsxStartupNotice());
	}
}
