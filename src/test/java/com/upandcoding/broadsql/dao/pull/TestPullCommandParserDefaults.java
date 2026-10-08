package com.upandcoding.broadsql.dao.pull;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * GitHub #152 - "Simplify PULL syntax with default format and DATA sheet": both {@code AS <format>} and
 * the destination's {@code .<table/tab>} part become optional. Exercises
 * {@link PullCommandParser#parse(String, String, String, String)} directly with a caller-supplied
 * default-format token/unavailability reason - the plain-string seam {@code CommandPull} feeds from
 * {@code ConsoleSettings} (see {@link TestConsoleSettingsDefaultFileFormatInvalidValue} for that
 * resolution, and {@code TestCommandPull} for the end-to-end wiring) - so this class stays exactly as
 * database-free and {@code Command}-free as every other {@code PullCommandParser} test
 * ({@link TestPullCommandParserSpreadsheet}'s own Javadoc). The two-argument {@link PullCommandParser#parse(String, String)}
 * overload used by every pre-existing PULL parser test is unaffected by this sprint - not one of those
 * files needed to change.
 */
class TestPullCommandParserDefaults {

	// ---- required test matrix (sprint instructions, "Required test matrix") ----

	@Test
	void bothExplicitIsUnaffected() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO TOTO.MYSHEET AS XLSX", null, "ODS", null);

		Assertions.assertEquals("MYSHEET", statement.getTargetTable());
		Assertions.assertEquals(PullStatement.Format.XLSX, statement.getFormat());
	}

	@Test
	void explicitSheetImplicitFormatUsesTheDefaultFormat() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO TOTO.MYSHEET", null, "XLSX", null);

		Assertions.assertEquals("MYSHEET", statement.getTargetTable());
		Assertions.assertEquals(PullStatement.Format.XLSX, statement.getFormat());
	}

	@Test
	void implicitSheetExplicitFormatDefaultsTheSheetToData() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO TOTO AS XLSX", null, "ODS", null);

		Assertions.assertEquals("DATA", statement.getTargetTable());
		Assertions.assertEquals(PullStatement.Format.XLSX, statement.getFormat());
	}

	@Test
	void bothImplicitUsesDataAndTheDefaultFormat() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO TOTO", null, "XLSX", null);

		Assertions.assertEquals("TOTO", statement.getTargetName());
		Assertions.assertEquals("DATA", statement.getTargetTable());
		Assertions.assertEquals(PullStatement.Format.XLSX, statement.getFormat());
		Assertions.assertEquals("SELECT * FROM CUSTOMER", statement.getSourceQuery());
		Assertions.assertEquals(PullStatement.Mode.OVERWRITE, statement.getMode());
	}

	@Test
	void explicitFormatOverridesTheConfiguredDefault() throws BroadSQLException {
		// "INI default: XLSX. Command: PULL (...) TO toto AS ODS; Expected format: ODS"
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO TOTO AS ODS", null, "XLSX", null);

		Assertions.assertEquals(PullStatement.Format.ODS, statement.getFormat());
		Assertions.assertEquals("DATA", statement.getTargetTable());
	}

	@Test
	void invalidOrMissingDefaultPlusImplicitFormatFailsWithAClearConfigurationError() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO TOTO", null, null,
						"DefaultFileFormat value 'BOGUS' in BroadSQL.ini is not a supported export format"));

		Assertions.assertTrue(ex.getMessage().contains("DefaultFileFormat"), "got: " + ex.getMessage());
		Assertions.assertTrue(ex.getMessage().contains("BOGUS"), "got: " + ex.getMessage());
	}

	@Test
	void invalidOrMissingDefaultPlusExplicitFormatStillWorks() throws BroadSQLException {
		// "Invalid/missing default plus: PULL (...) TO toto AS XLSX; must still work"
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO TOTO AS XLSX", null, null,
				"DefaultFileFormat value 'BOGUS' in BroadSQL.ini is not a supported export format");

		Assertions.assertEquals(PullStatement.Format.XLSX, statement.getFormat());
		Assertions.assertEquals("DATA", statement.getTargetTable());
	}

	// ---- the legacy two-argument overload keeps AS mandatory, unchanged ----

	@Test
	void theTwoArgumentOverloadStillRequiresAsExplicitly() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO TOTO", null));

		Assertions.assertTrue(ex.getMessage().contains("AS <format> is required"), "got: " + ex.getMessage());
	}

	@Test
	void theTwoArgumentOverloadStillDefaultsNothingForTheSheet() {
		// No destination table/tab and no default format token at all (both null): still the original
		// "AS <format> is required" message, not a DATA-defaulted destination silently accepted.
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO TOTO.MYSHEET", null));

		Assertions.assertTrue(ex.getMessage().contains("AS <format> is required"), "got: " + ex.getMessage());
	}

	// ---- H2 destination uses the exact same DATA default (the parser treats "table" and "tab" as one concept) ----

	@Test
	void h2DestinationAlsoDefaultsToData() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO WORKCOPY AS H2", null, "ODS", null);

		Assertions.assertEquals("WORKCOPY", statement.getTargetName());
		Assertions.assertEquals("DATA", statement.getTargetTable());
		Assertions.assertEquals(PullStatement.Format.H2, statement.getFormat());
	}

	// ---- flat-file formats have no table/tab concept at all - untouched by the DATA default ----

	@Test
	void flatFileFormatsHaveNoTargetTableRegardlessOfDefaulting() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO REPORT AS CSV", null, "XLSX", null);

		Assertions.assertEquals("REPORT", statement.getTargetName());
		Assertions.assertNull(statement.getTargetTable());
		Assertions.assertEquals(PullStatement.Format.CSV, statement.getFormat());
	}

	@Test
	void implicitFormatCanResolveToAFlatFileFormatWithNoTargetTable() throws BroadSQLException {
		// The default format resolved outside the parser (ConsoleSettings#getDefaultFileFormat()) can be
		// CSV or TXT too - the destination stays a bare name, exactly as if "AS CSV" had been typed.
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO REPORT", null, "CSV", null);

		Assertions.assertEquals("REPORT", statement.getTargetName());
		Assertions.assertNull(statement.getTargetTable());
		Assertions.assertEquals(PullStatement.Format.CSV, statement.getFormat());
	}

	@Test
	void aDotInTheDestinationIsStillRejectedForAnImplicitFlatFileFormat() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO REPORT.EXTRA", null, "CSV", null));

		Assertions.assertTrue(ex.getMessage().contains("must be a plain file name with no dot"), "got: " + ex.getMessage());
	}

	// ---- explicit destination/format combinations keep validating exactly as before ----

	@Test
	void aDefaultedDataTableNameIsStillRejectedIfReservedForH2Audit() {
		// PullAuditTable reserves BROADSQL_PULL_AUDIT, not DATA - sanity check the defaulted value still
		// goes through the normal validation path (it would reject BROADSQL_PULL_AUDIT the same way).
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO WORKCOPY.BROADSQL_PULL_AUDIT AS H2", null, "ODS", null));

		Assertions.assertTrue(ex.getMessage().contains("reserved by BroadSQL"), "got: " + ex.getMessage());
	}

	@Test
	void openStillParsesAfterAnImplicitFormatThatDefaultsToXlsx() throws BroadSQLException {
		PullStatement statement = PullCommandParser.parse("CUSTOMER TO REPORT OPEN", null, "XLSX", null);

		Assertions.assertEquals(PullStatement.Format.XLSX, statement.getFormat());
		Assertions.assertEquals("DATA", statement.getTargetTable());
		Assertions.assertTrue(statement.isOpen());
	}

	@Test
	void modeClauseAfterAnOmittedAsIsStillRejectedOnceTheDefaultedFormatIsNotH2() {
		// DefaultFileFormat's own supported set (XLSX, ODS, CSV, TXT) never includes H2, so a MODE clause
		// typed right after the destination (no AS at all) is parsed as MODE normally, then rejected the
		// same way "AS XLSX MODE ..." already is - defaulting never lets MODE reach a non-H2 destination.
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse("CUSTOMER TO WORKCOPY MODE APPEND KEY(ID)", null, "XLSX", null));

		Assertions.assertTrue(ex.getMessage().contains("MODE APPEND KEY(...) is only supported for AS H2"), "got: " + ex.getMessage());
	}
}
