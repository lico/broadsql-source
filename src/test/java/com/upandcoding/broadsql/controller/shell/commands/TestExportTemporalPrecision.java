package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.zip.ZipFile;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandDumpTable;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.pull.TemporalText;

/**
 * DUMP (and its alias PULL) keeps the fractional seconds of time and timestamp values: the source text of each
 * value is compared with its exported text, in every format. The flat-file exporters used to format timestamps as
 * {@code yyyy-MM-dd HH:mm:ss}, so {@code 2015-05-07 09:49:01.016} was exported as {@code 2015-05-07 09:49:01}.
 * A whole second keeps its plain form (no {@code .000} is invented), and the fraction is written in groups of three
 * digits, as far as the value carries it.
 */
class TestExportTemporalPrecision {

	private DatabaseConnection db;
	private CapturingShellConsole console;
	private ConsoleSettings settings;

	@TempDir
	Path dir;

	@BeforeEach
	void setUp() throws BroadSQLException {
		settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName(dir.toString() + File.separator);
		db = TestDatabaseConnections.connectInMemory(settings,
				"CREATE TABLE EVENTS (ID INT PRIMARY KEY, TS TIMESTAMP(9), D DATE, TM TIME(3))",
				"INSERT INTO EVENTS VALUES (1, TIMESTAMP '2015-05-07 09:49:01', DATE '2015-05-07', TIME '09:49:01')",
				"INSERT INTO EVENTS VALUES (2, TIMESTAMP '2015-05-07 09:49:01.016', DATE '2015-05-08', TIME '09:49:01.016')",
				"INSERT INTO EVENTS VALUES (3, TIMESTAMP '2015-05-07 09:49:01.010', DATE '2015-05-09', TIME '09:49:01.010')",
				"INSERT INTO EVENTS VALUES (4, TIMESTAMP '2015-05-07 09:49:01.0165', NULL, NULL)",
				"INSERT INTO EVENTS VALUES (5, TIMESTAMP '2015-05-07 09:49:01.016500123', NULL, NULL)",
				"INSERT INTO EVENTS VALUES (6, NULL, NULL, NULL)");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private String export(Class<? extends Command> type, String keyword, String name, String format) throws Exception {
		CommandTestSupport.create(type, db, console, settings).execute(keyword + " EVENTS TO " + name + " AS " + format);
		File file = dir.resolve(name + "." + extension(format)).toFile();
		Assertions.assertTrue(file.isFile(), keyword + " AS " + format + " wrote no file:\n" + console.getOutput());
		return Files.readString(file.toPath(), StandardCharsets.UTF_8);
	}

	private static String extension(String format) {
		return switch (format) {
		case "TXT" -> "txt";
		case "MD" -> "md";
		default -> format.toLowerCase();
		};
	}

	@Test
	void theFractionIsWrittenOnlyWhenTheValueHasOne() {
		Assertions.assertEquals("", TemporalText.fraction(0));
		Assertions.assertEquals(".016", TemporalText.fraction(16_000_000));
		Assertions.assertEquals(".010", TemporalText.fraction(10_000_000));
		Assertions.assertEquals(".016500", TemporalText.fraction(16_500_000));
		Assertions.assertEquals(".016500123", TemporalText.fraction(16_500_123));
		Assertions.assertEquals("2015-05-07 09:49:01", TemporalText.dateTime(LocalDateTime.of(2015, 5, 7, 9, 49, 1)));
		Assertions.assertEquals("2015-05-07T09:49:01.016", TemporalText.isoDateTime(LocalDateTime.of(2015, 5, 7, 9, 49, 1, 16_000_000)));
	}

	@ParameterizedTest(name = "AS {0}")
	@ValueSource(strings = { "CSV", "TXT", "MD", "HTML" })
	void theTextFormatsKeepEveryFraction(String format) throws Exception {
		String out = export(CommandDumpTable.class, "DUMP", "EV_" + format, format);
		for (String value : new String[] { "2015-05-07 09:49:01.016", "2015-05-07 09:49:01.010", "2015-05-07 09:49:01.016500",
				"2015-05-07 09:49:01.016500123", "09:49:01.016", "09:49:01.010", "2015-05-07", "2015-05-08" }) {
			Assertions.assertTrue(out.contains(value), format + " lost " + value + ":\n" + out);
		}
		// The whole second stays as it is: no .000 invented
		Assertions.assertFalse(out.contains(".000"), format + ":\n" + out);
		Assertions.assertFalse(out.contains("09:49:01.0165\n") || out.contains(".0165 "), format + ":\n" + out);
		Assertions.assertTrue(out.matches("(?s).*2015-05-07 09:49:01[^.0-9].*"), format + " must keep the whole-second value plain:\n" + out);
	}

	@Test
	void jsonKeepsEveryFractionInIsoForm() throws Exception {
		String out = export(CommandDumpTable.class, "DUMP", "EV_JSON", "JSON");
		for (String value : new String[] { "\"2015-05-07T09:49:01\"", "\"2015-05-07T09:49:01.016\"", "\"2015-05-07T09:49:01.016500123\"",
				"\"09:49:01.016\"", "\"09:49:01\"" }) {
			Assertions.assertTrue(out.contains(value), "JSON lost " + value + ":\n" + out);
		}
		Assertions.assertTrue(out.contains("\"TS\": null"), "a NULL timestamp stays null:\n" + out);
	}

	@ParameterizedTest(name = "AS {0}")
	@ValueSource(strings = { "CSV", "TXT", "JSON", "MD", "HTML" })
	void dumpAndPullWriteTheSameFile(String format) throws Exception {
		Assertions.assertEquals(export(CommandDumpTable.class, "DUMP", "D_" + format, format), export(CommandPull.class, "PULL", "D_" + format, format));
	}

	@Test
	void xlsxKeepsTheMillisecondsAndShowsThemOnlyWhenPresent() throws Exception {
		CommandTestSupport.create(CommandDumpTable.class, db, console, settings).execute("DUMP EVENTS TO EV_XLSX AS XLSX");
		File file = dir.resolve("EV_XLSX.xlsx").toFile();
		Assertions.assertTrue(file.isFile(), console.getOutput());
		try (FileInputStream in = new FileInputStream(file); XSSFWorkbook workbook = new XSSFWorkbook(in)) {
			Sheet sheet = workbook.getSheetAt(0);
			Cell whole = cell(sheet, 1, 1);
			Cell millis = cell(sheet, 2, 1);
			Assertions.assertEquals(LocalDateTime.of(2015, 5, 7, 9, 49, 1), DateUtil.getLocalDateTime(whole.getNumericCellValue()));
			Assertions.assertEquals(LocalDateTime.of(2015, 5, 7, 9, 49, 1, 16_000_000), DateUtil.getLocalDateTime(millis.getNumericCellValue()));
			Assertions.assertFalse(whole.getCellStyle().getDataFormatString().contains(".000"), whole.getCellStyle().getDataFormatString());
			Assertions.assertTrue(millis.getCellStyle().getDataFormatString().endsWith("ss.000"), millis.getCellStyle().getDataFormatString());
			Assertions.assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK, sheet.getRow(6).getCell(1).getCellType(), "a NULL timestamp stays an empty cell");
		}
	}

	private static Cell cell(Sheet sheet, int row, int column) {
		Row r = sheet.getRow(row);
		Assertions.assertNotNull(r, "row " + row);
		return r.getCell(column);
	}

	@Test
	void odsKeepsTheFraction() throws Exception {
		CommandTestSupport.create(CommandDumpTable.class, db, console, settings).execute("DUMP EVENTS TO EV_ODS AS ODS");
		File file = dir.resolve("EV_ODS.ods").toFile();
		Assertions.assertTrue(file.isFile(), console.getOutput());
		String content;
		try (ZipFile zip = new ZipFile(file)) {
			content = new String(zip.getInputStream(zip.getEntry("content.xml")).readAllBytes(), StandardCharsets.UTF_8);
		}
		Assertions.assertTrue(content.contains("2015-05-07T09:49:01.016"), "ODS lost the milliseconds");
		Assertions.assertTrue(content.contains("09:49:01.016"), "ODS lost the milliseconds of the TIME value");
		Assertions.assertTrue(content.contains("2015-05-07T09:49:01.016500123"), "ODS lost the nanoseconds");
	}
}
