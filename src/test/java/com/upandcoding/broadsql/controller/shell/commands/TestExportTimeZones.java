package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.TimeZone;
import java.util.zip.ZipFile;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.h2.util.DateTimeUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import com.github.miachm.sods.SpreadSheet;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandDumpTable;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Time-zone-aware timestamps export the same way whatever the time zone of the machine running BroadSQL: every test
 * runs under several JVM default zones, far apart, and expects the exact same output. The text formats keep each
 * value's own offset; XLSX and ODS hold native date/time cells of the same instant in UTC; {@code DUMP ... AS H2}
 * keeps the offset; time-zone-less values are never converted. Before, exports read these values with
 * {@code getTimestamp()} and wrote them in the JVM's zone, without offset.
 *
 * <p>Source rows: {@code 2024-01-15 10:00:00+05:00} (UTC {@code 05:00}), the same with microseconds, and
 * {@code 2024-03-31 02:30:00}, which falls in the daylight-saving gap of {@code Europe/Paris} (a time-zone-less
 * value there must stay {@code 02:30}).
 */
class TestExportTimeZones {

	private static final String CRLF = "\r\n";

	private TimeZone original;
	private DatabaseConnection db;
	private ConsoleSettings settings;
	private CapturingShellConsole console;

	@TempDir
	Path dir;

	/** Opens the source database under {@code zone} as the JVM default, the way a user's machine would run. */
	private void startIn(String zone) throws BroadSQLException {
		original = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone(zone));
		DateTimeUtils.resetCalendar();
		settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(dir.toString() + File.separator);
		settings.setCsvSeparatorRaw("SEMICOLON");
		settings.setExtractDefaultFileName("results.csv");
		db = TestDatabaseConnections.connectInMemory(settings,
				"CREATE TABLE EVENTS (ID INT PRIMARY KEY, TZ TIMESTAMP(6) WITH TIME ZONE, LT TIMESTAMP(6), D DATE, TTZ TIME WITH TIME ZONE)",
				"INSERT INTO EVENTS VALUES (1, TIMESTAMP WITH TIME ZONE '2024-01-15 10:00:00+05:00', TIMESTAMP '2024-01-15 10:00:00', "
						+ "DATE '2024-01-15', TIME WITH TIME ZONE '10:00:00+05:00')",
				"INSERT INTO EVENTS VALUES (2, TIMESTAMP WITH TIME ZONE '2024-01-15 10:00:00.123456+05:00', TIMESTAMP '2024-01-15 10:00:00.123456', NULL, NULL)",
				"INSERT INTO EVENTS VALUES (3, TIMESTAMP WITH TIME ZONE '2024-03-31 02:30:00+00:00', TIMESTAMP '2024-03-31 02:30:00', NULL, NULL)",
				"INSERT INTO EVENTS VALUES (4, NULL, NULL, NULL, NULL)");
		console = new CapturingShellConsole();
		db.setCmdLineConsole(console);
		LastQueryResultHolder.set(null);
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
		LastQueryResultHolder.set(null);
		if (original != null) {
			TimeZone.setDefault(original);
			DateTimeUtils.resetCalendar();
		}
	}

	private String dump(String line, String file) throws Exception {
		CommandTestSupport.create(CommandDumpTable.class, db, console, settings).execute(line);
		Path path = dir.resolve(file);
		Assertions.assertTrue(Files.exists(path), line + " wrote no file:\n" + console.getOutput());
		String content = Files.readString(path, StandardCharsets.UTF_8);
		return content.startsWith("﻿") ? content.substring(1) : content;
	}

	private static final String EXPECTED_CSV = String.join(CRLF, "ID;TZ;LT;D;TTZ",
			"1;2024-01-15 10:00:00+05:00;2024-01-15 10:00:00;2024-01-15;10:00:00+05:00",
			"2;2024-01-15 10:00:00.123456+05:00;2024-01-15 10:00:00.123456;;",
			"3;2024-03-31 02:30:00+00:00;2024-03-31 02:30:00;;",
			"4;;;;") + CRLF;

	// ---------------------------------------------------------------- text formats

	@ParameterizedTest(name = "JVM zone {0}")
	@ValueSource(strings = { "Europe/Paris", "America/New_York", "Asia/Tokyo", "UTC" })
	void csvKeepsEachValuesOwnOffsetWhateverTheJvmZone(String zone) throws Exception {
		startIn(zone);
		Assertions.assertEquals(EXPECTED_CSV, dump("DUMP EVENTS TO ev AS CSV", "ev.csv"));
	}

	@ParameterizedTest(name = "JVM zone {0}")
	@ValueSource(strings = { "Europe/Paris", "America/New_York" })
	void textMarkdownAndHtmlKeepTheOffsetWhateverTheJvmZone(String zone) throws Exception {
		startIn(zone);
		for (String[] format : new String[][] { { "TEXT", "ev.txt" }, { "MD", "ev.md" }, { "HTML", "ev.html" } }) {
			String out = dump("DUMP EVENTS TO ev AS " + format[0], format[1]);
			for (String value : new String[] { "2024-01-15 10:00:00+05:00", "2024-01-15 10:00:00.123456+05:00", "2024-03-31 02:30:00+00:00",
					"2024-03-31 02:30:00", "10:00:00+05:00" }) {
				Assertions.assertTrue(out.contains(value), format[0] + " under " + zone + " lost " + value + ":\n" + out);
			}
			Assertions.assertFalse(out.contains("05:00:00") || out.contains("06:00:00"), format[0] + " converted a value:\n" + out);
		}
	}

	@ParameterizedTest(name = "JVM zone {0}")
	@ValueSource(strings = { "Europe/Paris", "America/New_York", "Asia/Tokyo" })
	void jsonKeepsTheOffsetInIsoForm(String zone) throws Exception {
		startIn(zone);
		String json = dump("DUMP EVENTS TO ev AS JSON", "ev.json");
		Assertions.assertTrue(json.contains("\"TZ\": \"2024-01-15T10:00:00+05:00\""), json);
		Assertions.assertTrue(json.contains("\"TZ\": \"2024-01-15T10:00:00.123456+05:00\""), json);
		Assertions.assertTrue(json.contains("\"LT\": \"2024-03-31T02:30:00\""), json);
		Assertions.assertTrue(json.contains("\"TZ\": null"), json);
	}

	// ---------------------------------------------------------------- spreadsheets: native cells, UTC

	@ParameterizedTest(name = "JVM zone {0}")
	@ValueSource(strings = { "Europe/Paris", "America/New_York", "Asia/Tokyo", "UTC" })
	void xlsxHoldsNativeDateCellsOfTheInstantInUtc(String zone) throws Exception {
		startIn(zone);
		CommandTestSupport.create(CommandDumpTable.class, db, console, settings).execute("DUMP EVENTS TO ev AS XLSX");
		File file = dir.resolve("ev.xlsx").toFile();
		Assertions.assertTrue(file.isFile(), console.getOutput());
		try (FileInputStream in = new FileInputStream(file); XSSFWorkbook workbook = new XSSFWorkbook(in)) {
			Sheet sheet = workbook.getSheet("DATA");
			assertDateCell(sheet.getRow(1).getCell(1), LocalDateTime.of(2024, 1, 15, 5, 0), zone);
			// Excel keeps milliseconds: .123456 is held as .123
			assertDateCell(sheet.getRow(2).getCell(1), LocalDateTime.of(2024, 1, 15, 5, 0, 0, 123_000_000), zone);
			assertDateCell(sheet.getRow(3).getCell(1), LocalDateTime.of(2024, 3, 31, 2, 30), zone);
			// time-zone-less values are never converted
			assertDateCell(sheet.getRow(1).getCell(2), LocalDateTime.of(2024, 1, 15, 10, 0), zone);
			assertDateCell(sheet.getRow(3).getCell(2), LocalDateTime.of(2024, 3, 31, 2, 30), zone);
			assertDateCell(sheet.getRow(1).getCell(3), LocalDateTime.of(2024, 1, 15, 0, 0), zone);
			Assertions.assertEquals(CellType.BLANK, sheet.getRow(4).getCell(1).getCellType(), "a NULL stays an empty cell");
		}
	}

	private static void assertDateCell(Cell cell, LocalDateTime expected, String zone) {
		Assertions.assertEquals(CellType.NUMERIC, cell.getCellType(), "a native date cell, not text (" + zone + ")");
		Assertions.assertTrue(DateUtil.isCellDateFormatted(cell), "formatted as a date: " + cell.getCellStyle().getDataFormatString());
		Assertions.assertEquals(expected, DateUtil.getLocalDateTime(cell.getNumericCellValue()), "under JVM zone " + zone);
	}

	@ParameterizedTest(name = "JVM zone {0}")
	@ValueSource(strings = { "Europe/Paris", "America/New_York", "Asia/Tokyo" })
	void odsHoldsNativeDateCellsOfTheInstantInUtc(String zone) throws Exception {
		startIn(zone);
		CommandTestSupport.create(CommandDumpTable.class, db, console, settings).execute("DUMP EVENTS TO ev AS ODS");
		File file = dir.resolve("ev.ods").toFile();
		Assertions.assertTrue(file.isFile(), console.getOutput());
		String content;
		try (ZipFile zip = new ZipFile(file)) {
			content = new String(zip.getInputStream(zip.getEntry("content.xml")).readAllBytes(), StandardCharsets.UTF_8);
		}
		// office:value-type="date" is a native date/time cell, not a text cell
		for (String value : new String[] { "2024-01-15T05:00:00", "2024-01-15T05:00:00.123456", "2024-03-31T02:30:00", "2024-01-15T10:00:00" }) {
			Assertions.assertTrue(content.matches("(?s).*office:value-type=\"date\"[^>]*office:date-value=\"" + value.replace(".", "\\.") + "\".*"),
					"ODS under " + zone + " has no native date cell " + value);
		}
		com.github.miachm.sods.Sheet sheet = new SpreadSheet(file).getSheet("DATA");
		Assertions.assertEquals(LocalDateTime.of(2024, 1, 15, 5, 0), sheet.getRange(1, 1).getValue(), "read back as a date, in UTC");
		Assertions.assertEquals(LocalDateTime.of(2024, 1, 15, 10, 0), sheet.getRange(1, 2).getValue(), "a time-zone-less value is not converted");
	}

	// ---------------------------------------------------------------- DUMP AS H2 and DUMP /

	@ParameterizedTest(name = "JVM zone {0}")
	@ValueSource(strings = { "Europe/Paris", "America/New_York" })
	void dumpAsH2KeepsTheOriginalOffset(String zone) throws Exception {
		startIn(zone);
		DatabaseDefinition target = TestDatabaseConnections.newInMemoryTarget("ZTARGET");
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault();
		HashMap<String, DatabaseDefinition> connections = new HashMap<>();
		connections.put("ZTARGET", target);
		vault.setDatabaseConnections(connections);
		CommandPull pull = CommandTestSupport.create(CommandPull.class, db, console, settings);
		pull.setDatabaseConnectionsVault(vault);

		pull.execute("PULL EVENTS TO ZTARGET.EV AS H2");

		try (Connection conn = DriverManager.getConnection(target.getUrl()); Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery("SELECT TZ, LT FROM EV ORDER BY ID")) {
			Assertions.assertTrue(rs.next(), console.getOutput());
			Assertions.assertEquals(OffsetDateTime.of(2024, 1, 15, 10, 0, 0, 0, ZoneOffset.ofHours(5)), rs.getObject(1, OffsetDateTime.class));
			Assertions.assertEquals(LocalDateTime.of(2024, 1, 15, 10, 0), rs.getObject(2, LocalDateTime.class));
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals(OffsetDateTime.of(2024, 1, 15, 10, 0, 0, 123_456_000, ZoneOffset.ofHours(5)), rs.getObject(1, OffsetDateTime.class));
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals(LocalDateTime.of(2024, 3, 31, 2, 30), rs.getObject(2, LocalDateTime.class), "no daylight-saving shift");
		}
	}

	@ParameterizedTest(name = "JVM zone {0}")
	@ValueSource(strings = { "Europe/Paris", "America/New_York", "Asia/Tokyo" })
	void dumpSlashAppliesTheSameRulesToTheDisplayedResult(String zone) throws Exception {
		startIn(zone);
		db.executeSelectQuery("SELECT * FROM EVENTS ORDER BY ID");

		Assertions.assertEquals(EXPECTED_CSV, dump("DUMP / TO slash AS CSV", "slash.csv"), "DUMP / lost the offset");
		CommandTestSupport.create(CommandDumpTable.class, db, console, settings).execute("DUMP / TO slash AS XLSX");
		try (FileInputStream in = new FileInputStream(dir.resolve("slash.xlsx").toFile()); XSSFWorkbook workbook = new XSSFWorkbook(in)) {
			assertDateCell(workbook.getSheet("DATA").getRow(1).getCell(1), LocalDateTime.of(2024, 1, 15, 5, 0), zone);
			assertDateCell(workbook.getSheet("DATA").getRow(1).getCell(2), LocalDateTime.of(2024, 1, 15, 10, 0), zone);
		}
	}
}
