package com.upandcoding.broadsql.dao.pull;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.pull.PullCommandParser.Grammar;
import com.upandcoding.broadsql.dao.pull.PullStatement.Format;
import com.upandcoding.broadsql.dao.pull.PullStatement.SourceKind;

/**
 * SPRINT 2309T (#161/#163/#165): the one export grammar in its DUMP spelling (optional {@code TO},
 * {@code /} as the last displayed result, {@code LIB <script>}, {@code AS TEXT}) and the compatibility
 * guarantees of its PULL spelling. Pure parser tests: nothing is executed.
 */
class TestPullCommandParserDump {

	private static PullStatement dump(String afterKeyword) throws BroadSQLException {
		return PullCommandParser.parse(Grammar.dump("results"), afterKeyword, "ODS", null);
	}

	private static PullStatement pull(String afterKeyword) throws BroadSQLException {
		return PullCommandParser.parse(afterKeyword, "SELECT 42", "ODS", null);
	}

	// --- DUMP / ---------------------------------------------------------------------------------------

	@Test
	void slashIsTheLastResultAndCarriesNoQueryToRerun() throws BroadSQLException {
		PullStatement st = dump("/");
		Assertions.assertEquals(SourceKind.LAST_RESULT, st.getSourceKind());
		Assertions.assertNull(st.getSourceQuery(), "DUMP / must never carry a query to re-run");
		Assertions.assertEquals("results", st.getTargetName());
		Assertions.assertEquals(Format.ODS, st.getFormat(), "no AS: the configured default format");
		Assertions.assertEquals("DATA", st.getTargetTable());
	}

	@Test
	void slashDoesNotNeedAQueryInMemory() throws BroadSQLException {
		PullStatement st = PullCommandParser.parse(Grammar.dump("results"), "/", "CSV", null);
		Assertions.assertEquals(SourceKind.LAST_RESULT, st.getSourceKind());
	}

	@Test
	void slashWithDestinationAndFormat() throws BroadSQLException {
		PullStatement st = dump("/ TO customers AS CSV");
		Assertions.assertEquals("customers", st.getTargetName());
		Assertions.assertEquals(Format.CSV, st.getFormat());
		Assertions.assertNull(st.getTargetTable());

		PullStatement xlsx = dump("/ TO customers.FR AS XLSX");
		Assertions.assertEquals("customers", xlsx.getTargetName());
		Assertions.assertEquals("FR", xlsx.getTargetTable());

		Assertions.assertEquals("DATA", dump("/ TO customers AS XLSX").getTargetTable());
	}

	@Test
	void pullSlashIsTheSameLastResultAsDumpSlash() throws BroadSQLException {
		PullStatement st = pull("/ TO REPORT AS CSV");
		Assertions.assertEquals(SourceKind.LAST_RESULT, st.getSourceKind());
		Assertions.assertNull(st.getSourceQuery(), "PULL / must never carry a query to re-run");
		PullStatement noQueryInMemory = PullCommandParser.parse("/ TO R AS CSV", null);
		Assertions.assertEquals(SourceKind.LAST_RESULT, noQueryInMemory.getSourceKind());
	}

	// --- Optional TO (DUMP and PULL) ----------------------------------------------------------------------

	@Test
	void tableWithoutToIsNamedAfterTheTable() throws BroadSQLException {
		PullStatement st = dump("CUSTOMER AS CSV");
		Assertions.assertEquals(SourceKind.TABLE, st.getSourceKind());
		Assertions.assertEquals("SELECT * FROM CUSTOMER", st.getSourceQuery());
		Assertions.assertEquals("CUSTOMER", st.getTargetName());
	}

	@Test
	void queryWithoutToUsesTheDefaultResultName() throws BroadSQLException {
		PullStatement st = dump("(SELECT * FROM CUSTOMER WHERE COUNTRY = 'FR')");
		Assertions.assertEquals(SourceKind.QUERY, st.getSourceKind());
		Assertions.assertEquals("SELECT * FROM CUSTOMER WHERE COUNTRY = 'FR'", st.getSourceQuery());
		Assertions.assertEquals("results", st.getTargetName());
	}

	@Test
	void pullAcceptsTheSameDefaultedDestinationAsDump() throws BroadSQLException {
		for (String line : new String[] { "CUSTOMER AS CSV", "/", "(SELECT 1 AS X)", "LIB sales.sql AS JSON", "CUSTOMER TO C", "/ TO C.S AS XLSX" }) {
			PullStatement d = dump(line);
			PullStatement p = pull(line);
			Assertions.assertEquals(d.getSourceKind(), p.getSourceKind(), line);
			Assertions.assertEquals(d.getSourceQuery(), p.getSourceQuery(), line);
			Assertions.assertEquals(d.getTargetName(), p.getTargetName(), line);
			Assertions.assertEquals(d.getTargetTable(), p.getTargetTable(), line);
			Assertions.assertEquals(d.getFormat(), p.getFormat(), line);
		}
	}

	@Test
	void h2WithoutToIsRefused() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> dump("CUSTOMER AS H2"));
		Assertions.assertTrue(ex.getMessage().contains("AS H2 requires TO"), ex.getMessage());
	}

	// --- LIB <script> ---------------------------------------------------------------------------------

	@Test
	void libraryScriptSource() throws BroadSQLException {
		PullStatement st = dump("LIB sales.sql");
		Assertions.assertEquals(SourceKind.LIBRARY_SCRIPT, st.getSourceKind());
		Assertions.assertEquals("sales.sql", st.getLibraryScript());
		Assertions.assertNull(st.getSourceQuery());
		Assertions.assertEquals("sales", st.getTargetName(), "no TO: named after the script");
		Assertions.assertEquals(Format.ODS, st.getFormat());
	}

	@Test
	void libraryScriptWithFolderDestinationAndFormat() throws BroadSQLException {
		PullStatement st = dump("LIB reports/sales.sql TO sales AS CSV");
		Assertions.assertEquals("reports/sales.sql", st.getLibraryScript());
		Assertions.assertEquals("sales", st.getTargetName());
		Assertions.assertEquals(Format.CSV, st.getFormat());

		Assertions.assertEquals("reports/sales.sql", dump("LIB reports/sales.sql").getLibraryScript());
		Assertions.assertEquals("sales", dump("LIB reports/sales.sql").getTargetName());

		PullStatement xlsx = dump("LIB sales.sql TO sales.DATA AS XLSX");
		Assertions.assertEquals("DATA", xlsx.getTargetTable());
	}

	@Test
	void quotedLibraryScriptName() throws BroadSQLException {
		PullStatement st = dump("LIB \"my report.sql\" AS TEXT");
		Assertions.assertEquals("my report.sql", st.getLibraryScript());
		Assertions.assertEquals("my report", st.getTargetName());
		Assertions.assertEquals(Format.TXT, st.getFormat());
	}

	@Test
	void aTableNamedLibIsStillATable() throws BroadSQLException {
		PullStatement st = dump("LIB TO x AS CSV");
		Assertions.assertEquals(SourceKind.TABLE, st.getSourceKind());
		Assertions.assertEquals("SELECT * FROM LIB", st.getSourceQuery());
		Assertions.assertEquals(SourceKind.TABLE, dump("LIB AS CSV").getSourceKind());
	}

	@Test
	void modeAppendIsRefusedForSourcesWithoutSql() {
		for (String source : new String[] { "/", "LIB sales.sql" }) {
			BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
					() -> dump(source + " TO W.T AS H2 MODE APPEND KEY(ID)"));
			Assertions.assertTrue(ex.getMessage().contains("MODE APPEND is not supported"), ex.getMessage());
		}
	}

	// --- Formats --------------------------------------------------------------------------------------

	@Test
	void textIsTheCanonicalNameOfTheTabSeparatedFormat() throws BroadSQLException {
		Assertions.assertEquals(Format.TXT, dump("CUSTOMER AS TEXT").getFormat());
		Assertions.assertEquals(Format.TXT, dump("CUSTOMER AS TXT").getFormat(), "TXT stays accepted");
		Assertions.assertEquals(Format.TXT, pull("CUSTOMER TO R AS TEXT").getFormat());
		Assertions.assertEquals(Format.JSON, dump("CUSTOMER AS JSON").getFormat());
		Assertions.assertEquals(Format.CSV, dump("CUSTOMER AS csv").getFormat());
	}

	@Test
	void theConfiguredDefaultIsUsedOnlyWithoutAs() throws BroadSQLException {
		Assertions.assertEquals(Format.CSV, PullCommandParser.parse(Grammar.dump("results"), "CUSTOMER TO C", "CSV", null).getFormat());
		Assertions.assertEquals(Format.JSON, PullCommandParser.parse(Grammar.dump("results"), "CUSTOMER TO C AS JSON", "CSV", null).getFormat());
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> PullCommandParser.parse(Grammar.dump("results"), "/", null, "DUMP requires AS <format> because ..."));
		Assertions.assertEquals("DUMP requires AS <format> because ...", ex.getMessage());
	}

	@Test
	void errorsNameTheCommandThatWasTyped() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> dump("SELECT * FROM CUSTOMER"));
		Assertions.assertTrue(ex.getMessage().startsWith("A bare, unparenthesized query is not accepted by DUMP"), ex.getMessage());
		BroadSQLException empty = Assertions.assertThrows(BroadSQLException.class, () -> dump(""));
		Assertions.assertTrue(empty.getMessage().startsWith("DUMP requires a source"), empty.getMessage());
	}

	@Test
	void pullGainsLibraryScriptsThroughTheSharedGrammar() throws BroadSQLException {
		PullStatement st = pull("LIB sales.sql TO sales AS CSV");
		Assertions.assertEquals(SourceKind.LIBRARY_SCRIPT, st.getSourceKind());
		Assertions.assertEquals("sales.sql", st.getLibraryScript());
	}
}
