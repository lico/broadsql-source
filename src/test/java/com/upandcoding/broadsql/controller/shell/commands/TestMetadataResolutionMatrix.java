package com.upandcoding.broadsql.controller.shell.commands;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandDumpTable;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandDescr;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowForeignKeys;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowIndexes;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowPrimaryKeys;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowReferences;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * Every metadata command naming one exact table ({@code DESCR}, {@code SHOW PK}, {@code SHOW FK},
 * {@code SHOW REFERENCES}, {@code SHOW INDEXES}) resolves a typed name the same way, through the one canonical
 * resolver: the same identifier resolves to the same table, or fails with the same kind of error, in all five.
 * The database holds deliberately confusing names: {@code COUNTRY} (so {@code TRY} is a substring of an existing
 * table), {@code FOO_BAR} and {@code FOOXBAR} ({@code _} is a JDBC wildcard), {@code PCT%T} ({@code %} is one),
 * and a second {@code COUNTRY} in schema {@code S2}. {@code DESCR} and {@code SHOW PK} used to take the name as a
 * JDBC search pattern: {@code SHOW PK country} failed while {@code SHOW FK country} worked, and {@code DESCR TRY}
 * printed an empty grid.
 */
class TestMetadataResolutionMatrix {

	private static final Map<String, Class<? extends Command>> FAMILY = new LinkedHashMap<>();
	static {
		FAMILY.put("DESCR", CommandDescr.class);
		FAMILY.put("SHOW PK", CommandShowPrimaryKeys.class);
		FAMILY.put("SHOW FK", CommandShowForeignKeys.class);
		FAMILY.put("SHOW REFERENCES", CommandShowReferences.class);
		FAMILY.put("SHOW INDEXES", CommandShowIndexes.class);
	}

	private DatabaseConnection db;
	private ConsoleSettings settings;

	@BeforeEach
	void setUp() throws BroadSQLException {
		settings = TestDatabaseConnections.defaultConsoleSettings();
		db = TestDatabaseConnections.connectInMemory(settings,
				"CREATE TABLE COUNTRY (CODE CHAR(3) PRIMARY KEY, COUNTRY_NAME VARCHAR(50))",
				"CREATE TABLE CITY (CITY_ID INT PRIMARY KEY, COUNTRY_CODE CHAR(3), CONSTRAINT FK_CITY_COUNTRY FOREIGN KEY (COUNTRY_CODE) REFERENCES COUNTRY(CODE))",
				"CREATE INDEX IX_CITY_COUNTRY ON CITY(COUNTRY_CODE)",
				"CREATE TABLE FOO_BAR (FOO_BAR_ID INT PRIMARY KEY, BAR_COL INT)",
				"CREATE INDEX IX_FOO_BAR_COL ON FOO_BAR(BAR_COL)",
				"CREATE TABLE FOOXBAR (FOOXBAR_ID INT PRIMARY KEY, SECRET_COL INT)",
				"CREATE INDEX IX_FOOXBAR_SECRET ON FOOXBAR(SECRET_COL)",
				"CREATE TABLE \"PCT%T\" (PCT_ID INT PRIMARY KEY)",
				"CREATE SCHEMA S2",
				"CREATE TABLE S2.COUNTRY (S2_CODE INT PRIMARY KEY, S2_NAME VARCHAR(10))",
				"CREATE INDEX S2.IX_S2_NAME ON S2.COUNTRY(S2_NAME)");
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	/** The outcome of one command: its output and whether it reported an error. */
	private record Outcome(String output, boolean error) {
	}

	private Outcome run(String keyword, String identifier) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		Command cmd = CommandTestSupport.create(FAMILY.get(keyword), db, console, settings);
		cmd.execute(keyword + " " + identifier);
		return new Outcome(console.getOutput(), console.wasErrorReported());
	}

	/**
	 * Identifiers that name exactly one table: a column only that table has (shown by DESCR), its primary key column
	 * (shown by SHOW PK), and texts of the other tables no command may show.
	 */
	static Stream<Arguments> resolvingIdentifiers() {
		return Stream.of(
				Arguments.of("exact uppercase", "COUNTRY", "COUNTRY_NAME", "CODE", List.of("S2_")),
				Arguments.of("lowercase", "country", "COUNTRY_NAME", "CODE", List.of("S2_")),
				Arguments.of("mixed case", "Country", "COUNTRY_NAME", "CODE", List.of("S2_")),
				Arguments.of("name containing _", "FOO_BAR", "BAR_COL", "FOO_BAR_ID", List.of("FOOXBAR", "SECRET_COL")),
				Arguments.of("name containing %", "PCT%T", "PCT_ID", "PCT_ID", List.of("COUNTRY", "FOO")),
				Arguments.of("schema-qualified", "S2.COUNTRY", "S2_NAME", "S2_CODE", List.of("COUNTRY_NAME", "FK_CITY_COUNTRY")),
				Arguments.of("schema-qualified, lower case", "s2.country", "S2_NAME", "S2_CODE", List.of("COUNTRY_NAME", "FK_CITY_COUNTRY")));
	}

	@ParameterizedTest(name = "{0}: {1}")
	@MethodSource("resolvingIdentifiers")
	void everyCommandResolvesTheSameTable(String label, String identifier, String column, String pkColumn, List<String> otherTablesText) throws BroadSQLException {
		for (String keyword : FAMILY.keySet()) {
			Outcome outcome = run(keyword, identifier);
			Assertions.assertFalse(outcome.error(), keyword + " " + identifier + " must resolve:\n" + outcome.output());
			for (String other : otherTablesText) {
				Assertions.assertFalse(outcome.output().contains(other), keyword + " " + identifier + " shows another table (" + other + "):\n" + outcome.output());
			}
		}
		Assertions.assertTrue(run("DESCR", identifier).output().contains(column), "DESCR " + identifier);
		Assertions.assertTrue(run("SHOW PK", identifier).output().contains(pkColumn), "SHOW PK " + identifier);
	}

	/** Identifiers that name no table: every command reports the same error, never an empty grid. */
	static Stream<Arguments> missingIdentifiers() {
		return Stream.of(
				Arguments.of("nonexistent substring of COUNTRY", "TRY", "Table name 'TRY' does not exist"),
				Arguments.of("_ is not a wildcard", "FOO_BA_", "Table name 'FOO_BA_' does not exist"),
				Arguments.of("% is not a wildcard", "FOO%", "Table name 'FOO%' does not exist"),
				Arguments.of("% is not a wildcard, even as the whole name", "%", "Table name '%' does not exist"),
				Arguments.of("unknown schema", "NOSCHEMA.COUNTRY", "Table name 'NOSCHEMA.COUNTRY' does not exist"),
				Arguments.of("invalid identifier", "COUNTRY.", "Invalid table name 'COUNTRY.'"),
				Arguments.of("invalid identifier, no table part", ".COUNTRY", "Invalid table name '.COUNTRY'"));
	}

	@ParameterizedTest(name = "{0}: {1}")
	@MethodSource("missingIdentifiers")
	void everyCommandReportsTheSameErrorAndNoGrid(String label, String identifier, String message) throws BroadSQLException {
		for (String keyword : FAMILY.keySet()) {
			Outcome outcome = run(keyword, identifier);
			Assertions.assertTrue(outcome.error(), keyword + " " + identifier + " must be an error:\n" + outcome.output());
			Assertions.assertTrue(outcome.output().contains(message), keyword + " " + identifier + " expected [" + message + "]:\n" + outcome.output());
			Assertions.assertFalse(outcome.output().contains("|"), keyword + " " + identifier + " printed a grid:\n" + outcome.output());
			Assertions.assertFalse(outcome.output().contains(" ' does not exist"), "stray space in the message:\n" + outcome.output());
		}
	}

	@Test
	void anAmbiguousNameIsTheSameErrorInEveryCommand() throws BroadSQLException {
		// A driver reporting no current schema: COUNTRY then exists in PUBLIC and in S2
		TestDatabaseConnections.reportNoCurrentSchema(db);
		for (String keyword : FAMILY.keySet()) {
			Outcome outcome = run(keyword, "COUNTRY");
			Assertions.assertTrue(outcome.error(), keyword + ":\n" + outcome.output());
			Assertions.assertTrue(outcome.output().contains("Table name 'COUNTRY' is ambiguous") && outcome.output().contains("PUBLIC.COUNTRY")
					&& outcome.output().contains("S2.COUNTRY"), keyword + ":\n" + outcome.output());
			Outcome qualified = run(keyword, "S2.COUNTRY");
			Assertions.assertFalse(qualified.error(), keyword + " S2.COUNTRY must resolve once qualified:\n" + qualified.output());
		}
	}

	@Test
	void theSameTableNameInTwoSchemasIsTheCurrentSchemasOneWhenUnqualified() throws BroadSQLException {
		for (String keyword : FAMILY.keySet()) {
			Outcome outcome = run(keyword, "COUNTRY");
			Assertions.assertFalse(outcome.output().contains("S2_"), keyword + " COUNTRY must be PUBLIC.COUNTRY:\n" + outcome.output());
		}
		Assertions.assertTrue(run("DESCR", "COUNTRY").output().contains("COUNTRY_NAME"));
		Assertions.assertTrue(run("DESCR", "S2.COUNTRY").output().contains("S2_NAME"));
	}

	@Test
	void aCaseVariantNamesTheTableActuallyFoundInEveryCommand() throws BroadSQLException {
		for (String keyword : FAMILY.keySet()) {
			Assertions.assertTrue(run(keyword, "country").output().contains("Table 'country' not found. Found table 'COUNTRY' instead."), keyword);
			Assertions.assertTrue(run(keyword, "s2.country").output().contains("Table 's2.country' not found. Found table 'S2.COUNTRY' instead."), keyword);
		}
	}

	@Test
	void anExistingTableWithNothingToReportIsAMessageNotAnEmptyGrid() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection noPk = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE NOTES (TXT VARCHAR(10))");
		try {
			CommandTestSupport.create(CommandShowPrimaryKeys.class, noPk, console, settings).execute("SHOW PK NOTES");
			Assertions.assertTrue(console.getOutput().contains("has no primary key"), console.getOutput());
			Assertions.assertFalse(console.wasErrorReported(), console.getOutput());
		} finally {
			TestDatabaseConnections.close(noPk);
		}
	}

	@Test
	void theLegacyDumpOfATableUsesTheSameExactResolution() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandTestSupport.create(CommandDumpTable.class, db, console, settings).execute("DUMP TRY");
		Assertions.assertTrue(console.wasErrorReported(), console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("Table name 'TRY' does not exist"), console.getOutput());
		Assertions.assertFalse(console.getOutput().contains("records extracted"), console.getOutput());
	}
}
