package com.upandcoding.broadsql.controller.shell;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * SPRINT 2309T (#165): {@code CsvSeparator} supports exactly two conventions, {@code COMMA} and
 * {@code SEMICOLON} (the literal {@code ,}/{@code ;} of earlier templates read the same way), semicolon
 * when absent. Any other value is refused by the CSV export format, reported at startup, and left as it
 * was for {@code LOAD}.
 */
class TestConsoleSettingsCsvSeparator {

	private static ConsoleSettings withRaw(String raw) {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setCsvSeparatorRaw(raw);
		return settings;
	}

	@Test
	void namedValuesAreCaseInsensitive() throws BroadSQLException {
		Assertions.assertEquals(',', withRaw("COMMA").getCsvExportSeparator());
		Assertions.assertEquals(',', withRaw("comma").getCsvExportSeparator());
		Assertions.assertEquals(';', withRaw("SEMICOLON").getCsvExportSeparator());
		Assertions.assertEquals(';', withRaw(" Semicolon ").getCsvExportSeparator());
	}

	@Test
	void literalCharactersFromEarlierTemplatesStillWork() throws BroadSQLException {
		Assertions.assertEquals(';', withRaw(";").getCsvExportSeparator());
		Assertions.assertEquals(',', withRaw(",").getCsvExportSeparator());
	}

	@Test
	void absentOrBlankDefaultsToSemicolonAsBefore() throws BroadSQLException {
		Assertions.assertEquals(';', withRaw(null).getCsvExportSeparator());
		Assertions.assertEquals(';', withRaw("  ").getCsvExportSeparator());
		Assertions.assertNull(withRaw(null).getCsvSeparatorStartupNotice());
	}

	@Test
	void anyOtherDelimiterIsRefusedByTheCsvFormat() {
		for (String raw : new String[] { "|", "TAB", "PIPE", ":" }) {
			ConsoleSettings settings = withRaw(raw);
			BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, settings::getCsvExportSeparator, raw);
			Assertions.assertTrue(ex.getMessage().contains("'CsvSeparator' is set to '" + raw + "'"), ex.getMessage());
			Assertions.assertTrue(ex.getMessage().contains("CsvSeparator=COMMA or CsvSeparator=SEMICOLON"), ex.getMessage());
			Assertions.assertNotNull(settings.getCsvSeparatorStartupNotice(), raw);
		}
	}

	@Test
	void programmaticOverrideIsValidatedTheSameWay() throws BroadSQLException {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setCsvSeparator(',');
		Assertions.assertEquals(',', settings.getCsvExportSeparator());
		settings.setCsvSeparator('|');
		Assertions.assertThrows(BroadSQLException.class, settings::getCsvExportSeparator);
	}

	@Test
	void loadKeepsItsFormerReadingOfAnUnsupportedValue() {
		Assertions.assertEquals('|', withRaw("|").getCsvSeparator(), "LOAD reads CSV files with getCsvSeparator(), unchanged");
		Assertions.assertEquals(',', withRaw("COMMA").getCsvSeparator(), "the named values are understood by LOAD too");
	}
}
