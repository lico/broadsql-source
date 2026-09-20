package com.upandcoding.broadsql.dao;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Regression test for the dead Derby check fixed 03/09/2026 (item 18 of the 5.0.8 feedback list,
 * docs/TODO.md): {@link DatabaseConnection#directUpdate(String, String, boolean)} compared
 * {@code getDbType()} against the literal {@code "derby"}, which never matches the actual values
 * ({@code "DERBY Embedded"}/{@code "DERBY Client"}), so a CSV header whose casing didn't already
 * match Derby's own (uppercase, for an unquoted table) column names failed with a
 * {@link NullPointerException} instead of being uppercased first.
 */
class TestDatabaseConnectionDerbyCsvLoad {

	private DatabaseConnection db;
	private File csvFile;

	@BeforeEach
	void setUp() throws BroadSQLException {
		String uniqueName = "derbytest_" + UUID.randomUUID().toString().replace("-", "");

		DatabaseDefinition platform = new DatabaseDefinition(uniqueName);
		// Not "org.apache.derby.jdbc.EmbeddedDriver" (the driver class name seeded into the CDF
		// per docs/SUPPORTED_DATABASES.md) - that class no longer exists in the Derby version this
		// project pins (10.17.1.0, confirmed empirically); flagged separately, out of scope here.
		platform.setDbDriver("org.apache.derby.iapi.jdbc.AutoloadedDriver");
		platform.setDbType(SpringPropertiesConfig.DBTYPE_DERBY_Embedded);
		platform.setDbName(uniqueName);
		platform.setUrl("jdbc:derby:memory:" + uniqueName + ";create=true");

		db = new DatabaseConnection();
		db.consoleSettings = TestDatabaseConnections.defaultConsoleSettings();
		db.setPlatform(platform);
		db.connect();
		db.setCmdLineConsole(new CapturingShellConsole());

		db.executeUpdateQuery("CREATE TABLE PEOPLE (ID INT, NAME VARCHAR(50))");
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
		if (csvFile != null) {
			csvFile.delete();
			// directUpdate() always writes a "<file>_LOG.txt" sidecar next to the CSV.
			new File(csvFile.getAbsolutePath().replaceFirst("\\.csv$", "") + "_LOG.txt").delete();
		}
	}

	@Test
	void loadsARowWhenTheCsvHeaderCasingDiffersFromDerbysUppercaseColumnNames() throws BroadSQLException, IOException, SQLException {
		csvFile = File.createTempFile("derby_csv_load_", ".csv");
		try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile))) {
			// Lower-case header: Derby itself reports "ID"/"NAME" (unquoted DDL columns are
			// upper-cased) - only the fix's uppercasing keeps these in sync.
			writer.println("id;name");
			writer.println("1;Alice");
		}

		db.directUpdate("PEOPLE", csvFile.getAbsolutePath(), false);

		try (Statement statement = db.connection.createStatement();
				ResultSet results = statement.executeQuery("SELECT NAME FROM PEOPLE WHERE ID = 1")) {
			Assertions.assertTrue(results.next(), "expected the CSV row to have been inserted");
			Assertions.assertEquals("Alice", results.getString("NAME"));
		}
	}
}
