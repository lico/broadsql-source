package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandDumpTable;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandAll;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandCnt;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * The commands that generate SQL from a table name ({@code ALL}, {@code CNT}, {@code DUMP <table>} in both its
 * forms, {@code PULL <table>}, and the row count behind {@code DUMP <table>}) read exactly the table the metadata
 * lookup resolves, by its quoted identity: never another table the unquoted name would designate. The database
 * holds deliberately confusable tables, each with a distinct marker and row count: {@code CUSTOMER} and
 * {@code "Customer"} (differing only by case), {@code SALES} and {@code "Sales Data"} ({@code Sales Data} unquoted
 * is table {@code SALES} with alias {@code Data}), the reserved word {@code "ORDER"}, and {@code FOO_BAR} next to
 * {@code FOOXBAR} ({@code _} is a JDBC wildcard).
 */
class TestTableSourceResolution {

	private DatabaseConnection db;
	private ConsoleSettings settings;
	private CapturingShellConsole console;

	@TempDir
	Path exportFolder;

	@BeforeEach
	void setUp() throws BroadSQLException {
		settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(exportFolder.toString() + File.separator);
		settings.setDefaultFileFormatRaw("CSV");
		db = TestDatabaseConnections.connectInMemory(settings,
				"CREATE TABLE CUSTOMER (SRC VARCHAR(30))",
				"INSERT INTO CUSTOMER VALUES ('upper CUSTOMER')",
				"CREATE TABLE \"Customer\" (SRC VARCHAR(30))",
				"INSERT INTO \"Customer\" VALUES ('mixed Customer'), ('mixed Customer')",
				"CREATE TABLE SALES (SRC VARCHAR(30))",
				"INSERT INTO SALES VALUES ('plain SALES')",
				"CREATE TABLE \"Sales Data\" (SRC VARCHAR(30))",
				"INSERT INTO \"Sales Data\" VALUES ('spaced Sales Data'), ('spaced Sales Data'), ('spaced Sales Data')",
				"CREATE TABLE \"ORDER\" (SRC VARCHAR(30))",
				"INSERT INTO \"ORDER\" VALUES ('reserved ORDER')",
				"CREATE TABLE FOO_BAR (SRC VARCHAR(30))",
				"INSERT INTO FOO_BAR VALUES ('underscore FOO_BAR')",
				"CREATE TABLE FOOXBAR (SRC VARCHAR(30))",
				"INSERT INTO FOOXBAR VALUES ('lookalike FOOXBAR'), ('lookalike FOOXBAR')");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private String run(Class<? extends Command> command, String line) throws BroadSQLException {
		console = new CapturingShellConsole();
		CommandTestSupport.create(command, db, console, settings).execute(line);
		return console.getOutput();
	}

	private String exported(String fileName) throws IOException {
		Path file = exportFolder.resolve(fileName);
		Assertions.assertTrue(Files.exists(file), "expected " + file + " to be written; console:\n" + console.getOutput());
		return Files.readString(file, StandardCharsets.UTF_8);
	}

	// ---------------------------------------------------------------- ALL

	@Test
	void allOfAQuotedMixedCaseTableReadsThatTableNotItsUpperCaseTwin() throws BroadSQLException {
		String output = run(CommandAll.class, "ALL \"Customer\"");
		Assertions.assertTrue(output.contains("mixed Customer"), output);
		Assertions.assertFalse(output.contains("upper CUSTOMER"), "read CUSTOMER instead of \"Customer\":\n" + output);
	}

	@Test
	void allOfTheUpperCaseNameReadsTheUpperCaseTable() throws BroadSQLException {
		String output = run(CommandAll.class, "ALL CUSTOMER");
		Assertions.assertTrue(output.contains("upper CUSTOMER"), output);
		Assertions.assertFalse(output.contains("mixed Customer"), output);
	}

	@Test
	void allOfAMixedCaseNameTypedWithoutQuotesReadsTheTableOfExactlyThatName() throws BroadSQLException {
		// The lookup tries the name as typed first: Customer names the mixed-case table, and that table is read
		String output = run(CommandAll.class, "ALL Customer");
		Assertions.assertTrue(output.contains("mixed Customer"), output);
		Assertions.assertFalse(output.contains("upper CUSTOMER"), output);
	}

	@Test
	void allOfALowerCaseNameReadsTheTableTheLookupFinds() throws BroadSQLException {
		String output = run(CommandAll.class, "ALL customer");
		Assertions.assertTrue(output.contains("upper CUSTOMER"), output);
		Assertions.assertFalse(output.contains("mixed Customer"), output);
	}

	@Test
	void allOfATableWhoseNameContainsASpaceReadsThatTableNotTheFirstWordAsATable() throws BroadSQLException {
		String output = run(CommandAll.class, "ALL \"Sales Data\"");
		Assertions.assertTrue(output.contains("spaced Sales Data"), output);
		Assertions.assertFalse(output.contains("plain SALES"), "read SALES aliased Data:\n" + output);
	}

	@Test
	void allOfATableNamedWithAReservedWordWorks() throws BroadSQLException {
		String output = run(CommandAll.class, "ALL ORDER");
		Assertions.assertFalse(console.wasErrorReported(), output);
		Assertions.assertTrue(output.contains("reserved ORDER"), output);
	}

	@Test
	void allOfAnOrdinaryTableIsUnchanged() throws BroadSQLException {
		String output = run(CommandAll.class, "ALL FOO_BAR");
		Assertions.assertTrue(output.contains("underscore FOO_BAR"), output);
		Assertions.assertFalse(output.contains("lookalike FOOXBAR"), output);
		Assertions.assertTrue(run(CommandAll.class, "ALL PUBLIC.SALES").contains("plain SALES"), console.getOutput());
	}

	@Test
	void allOfANameThatDoesNotResolveAndIsNotAPlainNameIsAnErrorAndRunsNothing() throws BroadSQLException {
		String output = run(CommandAll.class, "ALL \"Sales Figures\"");
		Assertions.assertTrue(console.wasErrorReported(), output);
		Assertions.assertTrue(output.contains("Table name 'Sales Figures' does not exist"), output);
		Assertions.assertFalse(output.contains("plain SALES"), output);
	}

	// ---------------------------------------------------------------- CNT and the DUMP row count

	@Test
	void cntCountsTheResolvedTable() throws BroadSQLException {
		Assertions.assertTrue(run(CommandCnt.class, "CNT \"Customer\"").contains("|2 "), console.getOutput());
		Assertions.assertTrue(run(CommandCnt.class, "CNT CUSTOMER").contains("|1 "), console.getOutput());
		Assertions.assertTrue(run(CommandCnt.class, "CNT \"Sales Data\"").contains("|3 "), console.getOutput());
		Assertions.assertTrue(run(CommandCnt.class, "CNT FOO_BAR").contains("|1 "), console.getOutput());
	}

	@Test
	void theRowCountBehindDumpCountsTheResolvedTable() throws BroadSQLException {
		Assertions.assertEquals(2, db.getNumberOfRecords("Customer"));
		Assertions.assertEquals(1, db.getNumberOfRecords("CUSTOMER"));
		Assertions.assertEquals(3, db.getNumberOfRecords("Sales Data"));
		Assertions.assertEquals(1, db.getNumberOfRecords("ORDER"));
	}

	// ---------------------------------------------------------------- DUMP and PULL

	@Test
	void dumpingAQuotedMixedCaseTableUsesTheResolvedTable() throws BroadSQLException, IOException {
		run(CommandDumpTable.class, "DUMP \"Customer\"");
		String content = exported("Customer.csv");
		Assertions.assertTrue(content.contains("mixed Customer"), content);
		Assertions.assertFalse(content.contains("upper CUSTOMER"), "dumped CUSTOMER instead of \"Customer\":\n" + content);
	}

	@Test
	void dumpingATableWithASpaceToAFileUsesTheResolvedTable() throws BroadSQLException, IOException {
		run(CommandDumpTable.class, "DUMP \"Sales Data\" TO salesdata AS CSV");
		String content = exported("salesdata.csv");
		Assertions.assertEquals(3, content.split("spaced Sales Data", -1).length - 1, content);
		Assertions.assertFalse(content.contains("plain SALES"), content);
	}

	@Test
	void dumpingTheUpperCaseTwinToAFileUsesThatTable() throws BroadSQLException, IOException {
		run(CommandDumpTable.class, "DUMP CUSTOMER TO upper AS CSV");
		String content = exported("upper.csv");
		Assertions.assertTrue(content.contains("upper CUSTOMER"), content);
		Assertions.assertFalse(content.contains("mixed Customer"), content);
	}

	@Test
	void pullingAQuotedMixedCaseTableUsesTheResolvedTable() throws BroadSQLException, IOException {
		run(CommandPull.class, "PULL \"Customer\" TO mixed AS CSV");
		String content = exported("mixed.csv");
		Assertions.assertTrue(content.contains("mixed Customer"), content);
		Assertions.assertFalse(content.contains("upper CUSTOMER"), content);
	}

	@Test
	void pullingAReservedWordTableWorks() throws BroadSQLException, IOException {
		run(CommandPull.class, "PULL ORDER TO reserved AS CSV");
		Assertions.assertTrue(exported("reserved.csv").contains("reserved ORDER"), console.getOutput());
	}

	@Test
	void dumpingAnAmbiguousTableIsAnErrorAndWritesNothing() throws BroadSQLException {
		TestDatabaseConnections.reportNoCurrentSchema(db);
		db.executeUpdateQuery("CREATE SCHEMA S2");
		db.executeUpdateQuery("CREATE TABLE S2.SALES (SRC VARCHAR(30))");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run(CommandDumpTable.class, "DUMP SALES TO ambiguous AS CSV"));

		Assertions.assertTrue(ex.getMessage().contains("is ambiguous"), ex.getMessage());
		Assertions.assertFalse(Files.exists(exportFolder.resolve("ambiguous.csv")));
	}
}
