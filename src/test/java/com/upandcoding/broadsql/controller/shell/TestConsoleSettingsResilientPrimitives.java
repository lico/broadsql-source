package com.upandcoding.broadsql.controller.shell;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * GitHub #154 - the five settings hardened alongside {@code MaxRowXLSX} (covered separately in
 * {@link TestConsoleSettingsMaxRowXlsx}, the only one of the six with its own "invalid value" accessor
 * for feature-local failure): {@code FieldsSeparator}, {@code MaxRowsOnScreen}, {@code ScreenSeparator},
 * {@code Autocommit}, {@code IsLogActivated}. Each used to be bound directly onto a primitive
 * ({@code char}/{@code int}/{@code boolean}) {@code @Value} field - an invalid or missing value aborted
 * Spring context construction entirely (see {@link TestConsoleSettingsIniResilience} for the real-context
 * proof); each now resolves lazily from a raw String, falling back to its documented default with a
 * startup notice, exactly like {@code DefaultFileFormat}/{@code CsvSeparator} already did before this
 * sprint.
 */
class TestConsoleSettingsResilientPrimitives {

	// ---- FieldsSeparator (char) ----

	@Test
	void defaultSeparatorValidSingleCharacter() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setDefaultSeparatorRaw(";");
		Assertions.assertEquals(';', settings.getDefaultSeparator());
		Assertions.assertNull(settings.getDefaultSeparatorStartupNotice());
	}

	@Test
	void defaultSeparatorMissingFallsBackToTab() {
		ConsoleSettings settings = new ConsoleSettings();
		Assertions.assertEquals('\t', settings.getDefaultSeparator());
		Assertions.assertNotNull(settings.getDefaultSeparatorStartupNotice());
	}

	@Test
	void defaultSeparatorMultiCharacterIsInvalid() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setDefaultSeparatorRaw("ab");
		Assertions.assertDoesNotThrow(settings::getDefaultSeparator);
		Assertions.assertEquals('\t', settings.getDefaultSeparator());
		Assertions.assertTrue(settings.getDefaultSeparatorStartupNotice().contains("FieldsSeparator"), settings.getDefaultSeparatorStartupNotice());
	}

	@Test
	void defaultSeparatorExplicitSetterWins() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setDefaultSeparatorRaw("ab");
		settings.setDefaultSeparator(',');
		Assertions.assertEquals(',', settings.getDefaultSeparator());
		Assertions.assertNull(settings.getDefaultSeparatorStartupNotice());
	}

	// ---- MaxRowsOnScreen (int) ----

	@Test
	void maxRowsOnScreenValidInteger() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setMaxRowsOnScreenRaw("250");
		Assertions.assertEquals(250, settings.getMaxRowsOnScreen());
		Assertions.assertNull(settings.getMaxRowsOnScreenStartupNotice());
	}

	@Test
	void maxRowsOnScreenMissingFallsBackToDocumentedDefault() {
		ConsoleSettings settings = new ConsoleSettings();
		Assertions.assertEquals(ConsoleSettings.MAX_ROWS_ON_SCREEN_FALLBACK, settings.getMaxRowsOnScreen());
		Assertions.assertNotNull(settings.getMaxRowsOnScreenStartupNotice());
	}

	@Test
	void maxRowsOnScreenInvalidIntegerDoesNotThrow() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setMaxRowsOnScreenRaw("lots");
		Assertions.assertDoesNotThrow(settings::getMaxRowsOnScreen);
		Assertions.assertEquals(ConsoleSettings.MAX_ROWS_ON_SCREEN_FALLBACK, settings.getMaxRowsOnScreen());
		Assertions.assertTrue(settings.getMaxRowsOnScreenStartupNotice().contains("MaxRowsOnScreen"), settings.getMaxRowsOnScreenStartupNotice());
	}

	// ---- ScreenSeparator (char) ----

	@Test
	void onScreenSeparatorValidSingleCharacter() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setOnScreenSeparatorRaw(",");
		Assertions.assertEquals(',', settings.getOnScreenSeparator());
		Assertions.assertNull(settings.getOnScreenSeparatorStartupNotice());
	}

	@Test
	void onScreenSeparatorMissingFallsBackToPipe() {
		ConsoleSettings settings = new ConsoleSettings();
		Assertions.assertEquals('|', settings.getOnScreenSeparator());
		Assertions.assertNotNull(settings.getOnScreenSeparatorStartupNotice());
	}

	@Test
	void onScreenSeparatorInvalidDoesNotThrow() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setOnScreenSeparatorRaw("xyz");
		Assertions.assertDoesNotThrow(settings::getOnScreenSeparator);
		Assertions.assertEquals('|', settings.getOnScreenSeparator());
		Assertions.assertTrue(settings.getOnScreenSeparatorStartupNotice().contains("ScreenSeparator"), settings.getOnScreenSeparatorStartupNotice());
	}

	// ---- Autocommit (boolean) ----

	@Test
	void autoCommitValidTrueAndFalseCaseInsensitive() {
		ConsoleSettings settingsTrue = new ConsoleSettings();
		settingsTrue.setAutoCommitRaw("TRUE");
		Assertions.assertTrue(settingsTrue.isAutoCommit());
		Assertions.assertNull(settingsTrue.getAutoCommitStartupNotice());

		ConsoleSettings settingsFalse = new ConsoleSettings();
		settingsFalse.setAutoCommitRaw("false");
		Assertions.assertFalse(settingsFalse.isAutoCommit());
		Assertions.assertNull(settingsFalse.getAutoCommitStartupNotice());
	}

	@Test
	void autoCommitMissingFallsBackToTheInisOwnDocumentedDefault() {
		// The INI file's own long-standing comment: "if autocommit not in INI file, this creates an
		// error. default autocommit=false" - the second half is now actually true.
		ConsoleSettings settings = new ConsoleSettings();
		Assertions.assertEquals(ConsoleSettings.AUTOCOMMIT_FALLBACK, settings.isAutoCommit());
		Assertions.assertFalse(settings.isAutoCommit());
		Assertions.assertNotNull(settings.getAutoCommitStartupNotice());
	}

	@Test
	void autoCommitInvalidValueDoesNotSilentlyBecomeFalseWithoutANotice() {
		// Boolean.parseBoolean() would silently treat "maybe"/"1"/"yes" as false with no way to tell a
		// real typo from a deliberate false - validated explicitly instead.
		ConsoleSettings settings = new ConsoleSettings();
		settings.setAutoCommitRaw("maybe");
		Assertions.assertDoesNotThrow(settings::isAutoCommit);
		Assertions.assertEquals(ConsoleSettings.AUTOCOMMIT_FALLBACK, settings.isAutoCommit());
		Assertions.assertNotNull(settings.getAutoCommitStartupNotice());
		Assertions.assertTrue(settings.getAutoCommitStartupNotice().contains("Autocommit"), settings.getAutoCommitStartupNotice());
		Assertions.assertTrue(settings.getAutoCommitStartupNotice().contains("maybe"), settings.getAutoCommitStartupNotice());
	}

	@Test
	void autoCommitExplicitSetterWins() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setAutoCommitRaw("maybe");
		settings.setAutoCommit(true);
		Assertions.assertTrue(settings.isAutoCommit());
		Assertions.assertNull(settings.getAutoCommitStartupNotice());
	}

	// ---- IsLogActivated (boolean) ----

	@Test
	void logDefaultActivatedValidTrueAndFalse() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setLogDefaultActivatedRaw("TRUE");
		Assertions.assertTrue(settings.isLogDefaultActivated());
		Assertions.assertNull(settings.getLogDefaultActivatedStartupNotice());
	}

	@Test
	void logDefaultActivatedMissingFallsBackToFalse() {
		ConsoleSettings settings = new ConsoleSettings();
		Assertions.assertFalse(settings.isLogDefaultActivated());
		Assertions.assertNotNull(settings.getLogDefaultActivatedStartupNotice());
	}

	@Test
	void logDefaultActivatedInvalidValueDoesNotThrow() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setLogDefaultActivatedRaw("1");
		Assertions.assertDoesNotThrow(settings::isLogDefaultActivated);
		Assertions.assertFalse(settings.isLogDefaultActivated());
		Assertions.assertTrue(settings.getLogDefaultActivatedStartupNotice().contains("IsLogActivated"), settings.getLogDefaultActivatedStartupNotice());
	}
}
