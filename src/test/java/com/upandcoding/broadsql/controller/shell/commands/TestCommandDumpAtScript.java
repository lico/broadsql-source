package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.pull.PullCommandParser;
import com.upandcoding.broadsql.dao.pull.PullStatement;

/**
 * {@code DUMP @<script>}: the Scripts Library source written the way a Script is run at the prompt. It must
 * parse into exactly the source {@code DUMP LIB <script>} parses into and behave identically end to end (real
 * interpreter, real H2, real files): same files, same defaults, same failures, same isolation from the
 * session's previous result.
 */
class TestCommandDumpAtScript {

	@TempDir
	Path tmp;

	private Path out;
	private Path library;
	private ConsoleSettings settings;
	private DatabaseConnection db;
	private CapturingShellConsole console;
	private CommandInterpreter interpreter;

	@BeforeEach
	void setUp() throws Exception {
		out = Files.createDirectories(tmp.resolve("out"));
		library = Files.createDirectories(tmp.resolve("scripts"));
		settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(out.toString() + File.separator);
		settings.setScriptsLibraryPath(library.toString());
		settings.setExtractDefaultFileName("results.xlsx");
		settings.setDefaultFileFormatRaw("CSV");
		settings.setCsvSeparatorRaw("SEMICOLON");
		db = TestDatabaseConnections.connectInMemory(settings,
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50), AMOUNT DECIMAL(10,2))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice', 10.50), (2, 'Bob', 20.00)");
		console = new CapturingShellConsole();
		db.setCmdLineConsole(console);
		interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, db);
		LastQueryResultHolder.set(null);
		lib("QR10.sql", "CREATE LOCAL TEMPORARY TABLE X AS SELECT NAME, AMOUNT FROM CUSTOMER;\nSELECT NAME, AMOUNT FROM X ORDER BY NAME;\nDROP TABLE X;");
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
		LastQueryResultHolder.set(null);
	}

	private void run(String line) throws BroadSQLException {
		interpreter.setQuery(line);
		interpreter.executeCommand();
	}

	private void lib(String name, String text) throws Exception {
		Path file = library.resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
	}

	private byte[] bytes(String name) throws Exception {
		Path file = out.resolve(name);
		Assertions.assertTrue(Files.exists(file), "expected " + name + "; console: " + console.getOutput());
		return Files.readAllBytes(file);
	}

	private String text(String name) throws Exception {
		String content = new String(bytes(name), StandardCharsets.UTF_8);
		return content.startsWith("﻿") ? content.substring(1) : content;
	}

	private static PullStatement parse(String afterDump) throws BroadSQLException {
		return PullCommandParser.parse(PullCommandParser.Grammar.dump("results"), afterDump, "CSV", null);
	}

	// ---- parsing: the same source as DUMP LIB ----

	@Test
	void theAtFormParsesIntoTheLibraryScriptSourceOfDumpLib() throws Exception {
		String[][] pairs = { { "@QR10.sql", "LIB QR10.sql" }, { "@QR10.sql TO result", "LIB QR10.sql TO result" },
				{ "@QR10.sql TO result AS CSV", "LIB QR10.sql TO result AS CSV" },
				{ "@QR10.sql TO report.DATA AS XLSX", "LIB QR10.sql TO report.DATA AS XLSX" },
				{ "@reports/monthly.sql AS JSON", "LIB reports/monthly.sql AS JSON" },
				{ "@\"my report.sql\" TO r AS TEXT", "LIB \"my report.sql\" TO r AS TEXT" } };
		for (String[] pair : pairs) {
			PullStatement at = parse(pair[0]);
			PullStatement lib = parse(pair[1]);
			Assertions.assertEquals(PullStatement.SourceKind.LIBRARY_SCRIPT, at.getSourceKind(), pair[0]);
			Assertions.assertEquals(lib.getSourceKind(), at.getSourceKind(), pair[0]);
			Assertions.assertEquals(lib.getLibraryScript(), at.getLibraryScript(), pair[0]);
			Assertions.assertEquals(lib.getTargetName(), at.getTargetName(), pair[0]);
			Assertions.assertEquals(lib.getTargetTable(), at.getTargetTable(), pair[0]);
			Assertions.assertEquals(lib.getFormat(), at.getFormat(), pair[0]);
		}
		Assertions.assertEquals("my report.sql", parse("@\"my report.sql\"").getLibraryScript());
	}

	@Test
	void aMissingScriptNameIsRefusedLikeDumpLib() {
		BroadSQLException bare = Assertions.assertThrows(BroadSQLException.class, () -> parse("@"));
		Assertions.assertTrue(bare.getMessage().contains("requires a Scripts Library script"), bare.getMessage());
		Assertions.assertThrows(BroadSQLException.class, () -> parse("@ QR10.sql"), "@ is glued to the script, as at the prompt");
		Assertions.assertThrows(BroadSQLException.class, () -> parse("@\"unterminated"));
	}

	// ---- execution ----

	@Test
	void dumpAtScriptUsesTheDefaultFormatAndIsNamedAfterTheScript() throws Exception {
		run("DUMP @QR10.sql");
		Assertions.assertEquals("NAME;AMOUNT\r\nAlice;10.50\r\nBob;20.00\r\n", text("QR10.csv"));
	}

	@Test
	void dumpAtScriptWithTo() throws Exception {
		run("DUMP @QR10.sql TO result");
		Assertions.assertEquals("NAME;AMOUNT\r\nAlice;10.50\r\nBob;20.00\r\n", text("result.csv"));
	}

	@Test
	void dumpAtScriptToCsv() throws Exception {
		settings.setDefaultFileFormatRaw("ODS");
		run("DUMP @QR10.sql TO result AS CSV");
		Assertions.assertEquals("NAME;AMOUNT\r\nAlice;10.50\r\nBob;20.00\r\n", text("result.csv"));
	}

	@Test
	void dumpAtScriptToAnXlsxWorksheet() throws Exception {
		run("DUMP @QR10.sql TO report.DATA AS XLSX");
		try (XSSFWorkbook workbook = new XSSFWorkbook(new FileInputStream(out.resolve("report.xlsx").toFile()))) {
			Assertions.assertNotNull(workbook.getSheet("DATA"));
			Assertions.assertEquals("Alice", workbook.getSheet("DATA").getRow(1).getCell(0).getStringCellValue());
		}
	}

	@Test
	void dumpAtScriptAndDumpLibWriteIdenticalFiles() throws Exception {
		for (String format : new String[] { "CSV", "TEXT", "JSON", "MD", "HTML" }) {
			run("DUMP LIB QR10.sql TO viaLib" + format + " AS " + format);
			run("DUMP @QR10.sql TO viaAt" + format + " AS " + format);
		}
		for (String ext : new String[] { "csv", "txt", "json", "md", "html" }) {
			String format = switch (ext) { case "csv" -> "CSV"; case "txt" -> "TEXT"; case "json" -> "JSON"; case "md" -> "MD"; default -> "HTML"; };
			Assertions.assertArrayEquals(bytes("viaLib" + format + "." + ext), bytes("viaAt" + format + "." + ext), format);
		}
	}

	@Test
	void aScriptNameWithSpacesIsQuotedAsAtThePrompt() throws Exception {
		lib("my report.sql", "SELECT ID FROM CUSTOMER WHERE ID = 2;");
		run("DUMP @\"my report.sql\" TO spaced AS CSV");
		Assertions.assertEquals("ID\r\n2\r\n", text("spaced.csv"));
	}

	@Test
	void failuresAreTheSameAsForDumpLib() throws Exception {
		lib("prep.sql", "UPDATE CUSTOMER SET NAME = NAME;");
		BroadSQLException viaLib = Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP LIB prep.sql AS CSV"));
		BroadSQLException viaAt = Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP @prep.sql AS CSV"));
		Assertions.assertEquals(viaLib.getMessage(), viaAt.getMessage());

		BroadSQLException missingLib = Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP LIB nope.sql AS CSV"));
		BroadSQLException missingAt = Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP @nope.sql AS CSV"));
		Assertions.assertEquals(missingLib.getMessage(), missingAt.getMessage());
		Assertions.assertEquals(0, out.toFile().list().length);
	}

	@Test
	void theSessionsPreviousResultIsNeitherExportedNorReplaced() throws Exception {
		run("SELECT NAME FROM CUSTOMER WHERE ID = 1");
		LastQueryResult before = LastQueryResultHolder.get();
		lib("prep.sql", "UPDATE CUSTOMER SET NAME = NAME;");

		Assertions.assertThrows(BroadSQLException.class, () -> run("DUMP @prep.sql AS CSV"));
		Assertions.assertEquals(0, out.toFile().list().length, "a script without a result never exports the previous SELECT");

		run("DUMP @QR10.sql TO r AS CSV");
		Assertions.assertSame(before, LastQueryResultHolder.get(), "DUMP @ must not replace what DUMP / exports");
	}

	@Test
	void aTableIsStillDumpedWhenNoAtIsUsed() throws Exception {
		run("DUMP CUSTOMER");
		Assertions.assertTrue(out.resolve("CUSTOMER.csv").toFile().exists(), console.getOutput());
	}
}
