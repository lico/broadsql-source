package com.upandcoding.broadsql.controller.shell.output;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * SPRINT 2409K: display mode geometry from the actual terminal width, and its effect on the two shared
 * renderers (SQL results, metadata/catalog grids). Presentation only: the captured result keeps full values.
 */
class TestDisplayLayout {

	@AfterEach
	void reset() {
		DisplayLayout.configure(DisplayMode.AUTO, () -> 0);
		LastQueryResultHolder.set(null);
	}

	@Test
	void autoFollowsTheActualWidthWithCentralThresholds() {
		Assertions.assertEquals(DisplayMode.NORMAL, DisplayLayout.resolve(DisplayMode.AUTO, 0), "unknown width");
		Assertions.assertEquals(DisplayMode.COMPACT, DisplayLayout.resolve(DisplayMode.AUTO, DisplayLayout.COMPACT_BELOW_COLUMNS - 1));
		Assertions.assertEquals(DisplayMode.NORMAL, DisplayLayout.resolve(DisplayMode.AUTO, DisplayLayout.COMPACT_BELOW_COLUMNS));
		Assertions.assertEquals(DisplayMode.NORMAL, DisplayLayout.resolve(DisplayMode.AUTO, DisplayLayout.WIDE_FROM_COLUMNS - 1));
		Assertions.assertEquals(DisplayMode.WIDE, DisplayLayout.resolve(DisplayMode.AUTO, DisplayLayout.WIDE_FROM_COLUMNS));
		Assertions.assertEquals(DisplayMode.COMPACT, DisplayLayout.resolve(DisplayMode.COMPACT, 300), "an explicit mode wins");
	}

	@Test
	void aResizeIsSeenByTheNextTableWithoutRestarting() {
		AtomicInteger width = new AtomicInteger(200);
		DisplayLayout.configure(DisplayMode.AUTO, width::get);
		Assertions.assertEquals(DisplayMode.WIDE, DisplayLayout.effectiveMode());
		width.set(60);
		Assertions.assertEquals(DisplayMode.COMPACT, DisplayLayout.effectiveMode());
		width.set(120);
		Assertions.assertEquals(DisplayMode.NORMAL, DisplayLayout.effectiveMode());
	}

	@Test
	void compactNarrowsTheWidestColumnsThenGoesVertical() {
		DisplayLayout.Plan fits = DisplayLayout.plan(DisplayMode.COMPACT, 60, new int[] { 10, 10 }, 1);
		Assertions.assertFalse(fits.isVertical());
		Assertions.assertEquals(10, fits.width(0));

		DisplayLayout.Plan narrowed = DisplayLayout.plan(DisplayMode.COMPACT, 40, new int[] { 4, 200, 30 }, 1);
		Assertions.assertFalse(narrowed.isVertical());
		Assertions.assertEquals(4, narrowed.width(0), "a column already narrower than the cap keeps its width");
		Assertions.assertTrue(narrowed.width(0) + narrowed.width(1) + narrowed.width(2) + 3 <= 39);

		DisplayLayout.Plan vertical = DisplayLayout.plan(DisplayMode.COMPACT, 30, new int[] { 20, 20, 20, 20, 20, 20 }, 1);
		Assertions.assertTrue(vertical.isVertical());

		DisplayLayout.Plan normal = DisplayLayout.plan(DisplayMode.NORMAL, 30, new int[] { 500 }, 1);
		Assertions.assertEquals(500, normal.width(0), "NORMAL never narrows");
		Assertions.assertFalse(normal.truncates());
	}

	@Test
	void fitShortensForDisplayOnly() {
		Assertions.assertEquals("abc", DisplayLayout.fit("abc", 5));
		Assertions.assertEquals("abcd~", DisplayLayout.fit("abcdefgh", 5));
		Assertions.assertEquals("~", DisplayLayout.fit("abcdefgh", 1));
	}

	private static String select(String sql) throws BroadSQLException {
		DatabaseConnection db = TestDatabaseConnections.connectInMemory(TestDatabaseConnections.defaultConsoleSettings(),
				"CREATE TABLE T (ID INT, NAME VARCHAR(30), NOTE VARCHAR(200))", "INSERT INTO T VALUES (1, 'Alice', 'a very long note that is longer than a narrow terminal allows')");
		try {
			CapturingShellConsole console = new CapturingShellConsole();
			db.setCmdLineConsole(console);
			db.executeSelectQuery(sql);
			return console.getOutput();
		} finally {
			TestDatabaseConnections.close(db);
		}
	}

	@Test
	void compactShortensWideSelectColumnsButTheCapturedResultKeepsFullValues() throws Exception {
		DisplayLayout.configure(DisplayMode.AUTO, () -> 70);
		String out = select("SELECT ID, NAME, NOTE FROM T");
		for (String line : out.split("\\R")) {
			Assertions.assertTrue(line.length() <= 69, "line wider than the terminal: [" + line + "]");
		}
		Assertions.assertTrue(out.contains(DisplayLayout.TRUNCATION_MARK), out);
		Assertions.assertEquals("a very long note that is longer than a narrow terminal allows", LastQueryResultHolder.get().rows().get(0)[2]);
	}

	@Test
	void compactShowsRowsVerticallyWhenNothingCanFit() throws Exception {
		DisplayLayout.configure(DisplayMode.COMPACT, () -> 20);
		String out = select("SELECT ID, NAME, NOTE, NOTE AS N2, NOTE AS N3 FROM T");
		Assertions.assertTrue(out.contains("NAME: Alice"), out);
		Assertions.assertFalse(out.contains("|-----"), "no grid in vertical layout:\n" + out);
	}

	@Test
	void wideAddsSpacingAndNeverShortens() throws Exception {
		DisplayLayout.configure(DisplayMode.AUTO, () -> 200);
		String out = select("SELECT ID, NOTE FROM T");
		Assertions.assertTrue(out.contains(" | "), out);
		Assertions.assertTrue(out.contains("a very long note that is longer than a narrow terminal allows"), out);
	}

	@Test
	void theSharedGridRendererAdaptsToo() {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsolePrinter printer = new ConsolePrinter();
		printer.shellConsole = console;
		List<List<String>> rows = List.of(List.of("FK_ORDER_CUSTOMER_WITH_A_LONG_NAME", "ORDERS", "CUSTOMER_IDENTIFIER_COLUMN"));

		DisplayLayout.configure(DisplayMode.NORMAL, () -> 40);
		printer.printTable(List.of("FK_NAME", "TABLE", "COLUMN"), rows);
		Assertions.assertTrue(console.getOutput().contains("|FK_ORDER_CUSTOMER_WITH_A_LONG_NAME|ORDERS|CUSTOMER_IDENTIFIER_COLUMN|"), console.getOutput());

		CapturingShellConsole compact = new CapturingShellConsole();
		printer.shellConsole = compact;
		DisplayLayout.configure(DisplayMode.COMPACT, () -> 40);
		printer.printTable(List.of("FK_NAME", "TABLE", "COLUMN"), rows);
		for (String line : compact.getOutput().split("\\R")) {
			Assertions.assertTrue(line.length() <= 39, "[" + line + "]\n" + compact.getOutput());
		}
		Assertions.assertTrue(compact.getOutput().contains("1 rows fetched."), compact.getOutput());
	}
}
