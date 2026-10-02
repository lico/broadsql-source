package com.upandcoding.broadsql.controller.shell.output;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandCategoryCatalog;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.commands.CommandLoader;
import com.upandcoding.broadsql.controller.shell.commands.CommandTestSupport;
import com.upandcoding.broadsql.controller.shell.commands.core.help.CommandHelp;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibList;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandFindIndexes;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowForeignKeys;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * The BroadSQL table format (docs/02. guidelines/TABLE_OUTPUT_STANDARD.md): the rule itself in
 * {@link TableBorders}, then one representative output of each renderer that uses it (SQL results, the shared
 * grid of BroadSQL commands, HELP), all checked by the same {@link #assertStandardTables} so the old
 * unbordered form ({@code ID|NAME|} with no leading border and no footer) cannot come back.
 */
class TestTableOutputStandard {

	private DatabaseConnection db;

	@AfterEach
	void tearDown() throws Exception {
		TestDatabaseConnections.close(db);
		DisplayLayout.configure(DisplayMode.AUTO, () -> 0);
		LastQueryResultHolder.set(null);
	}

	/**
	 * Every table in {@code output} (a run of lines containing {@code |}) is: top separator, header, separator,
	 * data rows, footer separator (or no footer when there is no data row); every line starts and ends with
	 * {@code |}; separator cells contain only {@code -}; column boundaries are at the same positions on every line.
	 */
	static void assertStandardTables(String output, int expectedTables) {
		List<List<String>> tables = new ArrayList<>();
		List<String> current = null;
		for (String line : output.split("\\R")) {
			if (line.contains("|")) {
				if (current == null) {
					current = new ArrayList<>();
					tables.add(current);
				}
				current.add(line);
			} else {
				current = null;
			}
		}
		Assertions.assertEquals(expectedTables, tables.size(), "tables in:\n" + output);
		for (List<String> table : tables) {
			Assertions.assertTrue(table.size() >= 3, "top border, header and separator at least:\n" + output);
			String separator = table.get(0);
			Assertions.assertTrue(separator.matches("\\|(-+\\|)+"), "top border: [" + separator + "]\n" + output);
			Assertions.assertEquals(separator, table.get(2), "header/data separator\n" + output);
			Assertions.assertFalse(table.get(1).matches("\\|(-+\\|)+"), "header row expected second\n" + output);
			if (table.size() > 3) {
				Assertions.assertEquals(separator, table.get(table.size() - 1), "footer\n" + output);
			}
			for (String line : table) {
				Assertions.assertTrue(line.startsWith("|") && line.endsWith("|"), "line must start and end with |: [" + line + "]\n" + output);
				Assertions.assertEquals(separator.length(), line.length(), "column boundaries: [" + line + "]\n" + output);
				for (int i = 0; i < separator.length(); i++) {
					if (separator.charAt(i) == '|') {
						Assertions.assertEquals('|', line.charAt(i), "boundary " + i + " of [" + line + "]\n" + output);
					}
				}
			}
		}
	}

	// ---- the rule ----

	@Test
	void theRuleBuildsBorderedLinesWithDashFill() {
		int[] widths = { 3, 5 };
		Assertions.assertEquals("|---|-----|", TableBorders.separator(widths, 0));
		Assertions.assertEquals("|ID |NAME |", TableBorders.row(List.of("ID", "NAME"), widths, 0));
		Assertions.assertEquals("|-----|-------|", TableBorders.separator(widths, 1));
		Assertions.assertEquals("| ID  | NAME  |", TableBorders.row(List.of("ID", "NAME"), widths, 1));
		Assertions.assertEquals("|1  |     |", TableBorders.row(java.util.Arrays.asList("1", null), widths, 0), "a null cell is empty");
		Assertions.assertEquals(";---;-----;", TableBorders.separator(widths, 0, ';'), "the ScreenSeparator of query results");
	}

	@Test
	void theSharedGridFollowsTheRule() {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsolePrinter printer = new ConsolePrinter();
		printer.shellConsole = console;
		printer.printTable(List.of("File", "Group", "Environment", "Status", "Modified"),
				List.of(List.of("QR1.sql", "MyWorld", "INT", "ACTIVE", "2026-09-25"), List.of("QR2.sql", "MyWorld", "QA", "ACTIVE", "2026-09-24")));
		assertStandardTables(console.getOutput(), 1);
		Assertions.assertTrue(console.getOutput().startsWith("|-------|-------|-----------|------|----------|\n|File   |Group  |Environment|Status|Modified  |\n"),
				console.getOutput());
	}

	// ---- representative outputs ----

	@Test
	void sqlSelectResultsAreBorderedWithAFooterInEveryGridMode() throws Exception {
		CapturingShellConsole console = new CapturingShellConsole();
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE T (ID INT, NOTE VARCHAR(4), CREATED TIMESTAMP)",
				"INSERT INTO T VALUES (2, 'B', TIMESTAMP '2015-05-07 09:49:01.016'), (3, 'C', NULL)");
		db.setCmdLineConsole(console);
		for (DisplayMode mode : new DisplayMode[] { DisplayMode.NORMAL, DisplayMode.WIDE, DisplayMode.COMPACT }) {
			console.clear();
			DisplayLayout.configure(mode, () -> 200);
			db.executeSelectQuery("SELECT ID, NOTE, CREATED FROM T ORDER BY ID");
			assertStandardTables(console.getOutput(), 1);
			Assertions.assertTrue(console.getOutput().contains("2 rows fetched") || console.getOutput().contains("\n2 "), mode + ":\n" + console.getOutput());
		}
		console.clear();
		DisplayLayout.configure(DisplayMode.NORMAL, () -> 0);
		db.executeSelectQuery("SELECT ID, NOTE FROM T WHERE ID < 0");
		assertStandardTables(console.getOutput(), 1);
	}

	@Test
	void sqlResultsKeepTheirValuesWidthsAndTheCapturedResult() throws Exception {
		CapturingShellConsole console = new CapturingShellConsole();
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE T (ID INT, NOTE VARCHAR(4))", "INSERT INTO T VALUES (2, 'B')");
		db.setCmdLineConsole(console);
		DisplayLayout.configure(DisplayMode.NORMAL, () -> 0);
		db.executeSelectQuery("SELECT ID, NOTE FROM T");
		String out = console.getOutput();
		// the established NORMAL widths (column size, header length), now between two borders
		Assertions.assertTrue(out.contains("\n|ID         |NOTE|\n") && out.contains("\n|2          |B   |\n"), out);
		Assertions.assertEquals("B", LastQueryResultHolder.get().rows().get(0)[1], "the captured result is unchanged");
	}

	@Test
	void metadataAndFindTablesFollowTheRule() throws Exception {
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY)",
				"CREATE TABLE ORDERS (ID INT PRIMARY KEY, CUSTOMER_ID INT, CONSTRAINT FK_ORDER_CUST FOREIGN KEY (CUSTOMER_ID) REFERENCES CUSTOMER(ID))",
				"CREATE INDEX IX_ORDERS_CUST ON ORDERS(CUSTOMER_ID)");
		CapturingShellConsole show = new CapturingShellConsole();
		CommandTestSupport.create(CommandShowForeignKeys.class, db, show).execute("SHOW FK ORDERS");
		assertStandardTables(show.getOutput(), 1);
		CapturingShellConsole find = new CapturingShellConsole();
		CommandTestSupport.create(CommandFindIndexes.class, db, find).execute("FIND INDEX orders");
		assertStandardTables(find.getOutput(), 1);
	}

	@Test
	void libListFollowsTheRule(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("QR1.sql"), "-- @instance: MYWORLD\n-- @environment: INT\nselect 1;\n");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(dir.toString());
		CommandTestSupport.create(CommandLibList.class, null, console, settings).execute("LIB LIST ALL");
		assertStandardTables(console.getOutput(), 1);
	}

	@SuppressWarnings("unchecked")
	private static String help(String query) throws Exception {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		CapturingShellConsole console = new CapturingShellConsole();
		CommandInterpreter interpreter = CommandTestSupport.createCommandInterpreter(settings, console);
		Field field = CommandLoader.class.getDeclaredField("availableClasses");
		field.setAccessible(true);
		Map<String, String> classes = (Map<String, String>) field.get(interpreter.getCommands().getConsoleCommandLoader());
		for (Class<? extends Command> type : CommandCategoryCatalog.registeredClasses().keySet()) {
			classes.put(type.getName(), CommandLoader.CMD_CORE);
		}
		CommandHelp help = CommandTestSupport.create(CommandHelp.class, null, console, settings);
		help.setConsoleCommandInterpreter(interpreter);
		help.execute(query);
		return console.getOutput();
	}

	@Test
	void helpAndHelpShortcutsFollowTheRule() throws Exception {
		assertStandardTables(help("HELP"), 2);
		assertStandardTables(help("HELP SHORTCUTS"), 2);
	}
}
