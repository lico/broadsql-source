package com.upandcoding.broadsql.controller.shell.commands;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.listsource.ListSourceMacroScanner;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;

/**
 * SPRINT 2409K: {@code <@...>} delimiter logic next to ordinary SQL {@code >} comparisons
 * ({@link ListSourceMacroScanner}, used by {@link CommandUtils#substituteMacros}). The first four cases are
 * the sprint's required statements; the others are the defects the previous
 * {@code substringBetween(query, "<@", ">")} loop actually had (verified with a probe before the fix: an
 * infinite loop, a {@code NullPointerException}, and a quoted {@code '<@'} capturing {@code AMOUNT >}).
 */
class TestCommandUtilsSubstituteMacrosDelimiters {

	@TempDir
	Path tmp;

	private Path ids;
	private Path ids1;
	private Path ids2;

	@BeforeEach
	void setUp() throws Exception {
		ids = Files.writeString(tmp.resolve("ids.txt"), "1\n2\n", StandardCharsets.UTF_8);
		ids1 = Files.writeString(tmp.resolve("ids1.txt"), "A\n", StandardCharsets.UTF_8);
		ids2 = Files.writeString(tmp.resolve("ids2.txt"), "B\nC\n", StandardCharsets.UTF_8);
		LastQueryResultHolder.set(null);
	}

	@AfterEach
	void tearDown() {
		LastQueryResultHolder.set(null);
	}

	private static String substitute(String sql) throws BroadSQLException {
		return Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> CommandUtils.substituteMacros(sql));
	}

	@Test
	void aComparisonBeforeTheMacro() throws Exception {
		Assertions.assertEquals("SELECT * FROM ORDERS WHERE AMOUNT > 100 AND ID IN ('1','2');",
				substitute("SELECT * FROM ORDERS WHERE AMOUNT > 100 AND ID IN <@" + ids + ">;"));
	}

	@Test
	void severalComparisonsBeforeTheMacro() throws Exception {
		Assertions.assertEquals("SELECT * FROM T WHERE A > 1 AND B > 2 AND ID IN ('1','2')",
				substitute("SELECT * FROM T WHERE A > 1 AND B > 2 AND ID IN <@" + ids + ">"));
	}

	@Test
	void twoMacrosOnSeparateLines() throws Exception {
		Assertions.assertEquals("SELECT * FROM T WHERE ID IN ('A')\nAND CODE IN ('B','C')",
				substitute("SELECT * FROM T WHERE ID IN <@" + ids1 + ">\nAND CODE IN <@" + ids2 + ">"));
	}

	@Test
	void aComparisonAfterTheMacro() throws Exception {
		Assertions.assertEquals("SELECT * FROM ORDERS\nWHERE ID IN ('1','2')\n  AND AMOUNT > 100;",
				substitute("SELECT * FROM ORDERS\nWHERE ID IN <@" + ids + ">\n  AND AMOUNT > 100;"));
	}

	@Test
	void theSprintExampleSpanningLines() throws Exception {
		Assertions.assertEquals("SELECT *\nFROM ORDERS\nWHERE AMOUNT > 100\n  AND ID IN ('1','2');",
				substitute("SELECT *\nFROM ORDERS\nWHERE AMOUNT > 100\n  AND ID IN <@" + ids + ">;"));
	}

	@Test
	void comparisonsBetweenAndAroundTwoMacrosOnOneLine() throws Exception {
		Assertions.assertEquals("SELECT * FROM T WHERE X >= 3 AND ID IN ('A') AND Y <> 4 AND CODE IN ('B','C') AND Z > 5",
				substitute("SELECT * FROM T WHERE X >= 3 AND ID IN <@" + ids1 + "> AND Y <> 4 AND CODE IN <@" + ids2 + "> AND Z > 5"));
	}

	@Test
	void spacesAroundTheTokenNoLongerLoopForever() throws Exception {
		Assertions.assertEquals("SELECT * FROM T WHERE ID IN ('1','2')", substitute("SELECT * FROM T WHERE ID IN <@ " + ids + " >"));
	}

	@Test
	void anOpenerInsideAStringLiteralIsNotAMacro() throws Exception {
		Assertions.assertEquals("SELECT * FROM T WHERE NOTE = '<@' AND AMOUNT > 100 AND ID IN ('1','2')",
				substitute("SELECT * FROM T WHERE NOTE = '<@' AND AMOUNT > 100 AND ID IN <@" + ids + ">"));
	}

	@Test
	void anOpenerInsideAnEscapedLiteralOrCommentIsNotAMacro() throws Exception {
		String sql = "SELECT 'it''s <@x>' AS \"<@y>\" -- <@z> > 1\n/* <@w> */ FROM T WHERE A > 1";
		Assertions.assertEquals(sql, substitute(sql));
	}

	@Test
	void anUnclosedMacroFailsClearlyInsteadOfThrowingANullPointerException() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> substitute("SELECT * FROM T WHERE A > 1 AND ID IN <@" + ids));
		Assertions.assertTrue(ex.getMessage().contains("Unclosed list source"), ex.getMessage());
	}

	@Test
	void anUnclosedMacroNeverBorrowsAComparisonFromTheNextLine() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> substitute("SELECT * FROM T WHERE ID IN <@" + ids + "\nAND AMOUNT > 100"));
		Assertions.assertTrue(ex.getMessage().contains("Unclosed list source"), ex.getMessage());
	}

	@Test
	void substitutedValuesAreNeverRescanned() throws Exception {
		Path tricky = Files.writeString(tmp.resolve("tricky.txt"), "<@" + ids + ">\n", StandardCharsets.UTF_8);
		Assertions.assertEquals("SELECT * FROM T WHERE V IN ('<@" + ids + ">')", substitute("SELECT * FROM T WHERE V IN <@" + tricky + ">"));
	}

	@Test
	void prefixedSourcesStillResolveNextToComparisons() throws Exception {
		LastQueryResultHolder.set(new LastQueryResult(List.of("ID"), List.<String[]>of(new String[] { "7" }), 1, null));
		Path csv = Files.writeString(tmp.resolve("c.csv"), "ID,NAME\n9,x\n", StandardCharsets.UTF_8);
		Assertions.assertEquals("SELECT * FROM T WHERE A > 1 AND ID IN ('7') AND B IN ('9') AND C > 2",
				substitute("SELECT * FROM T WHERE A > 1 AND ID IN <@last:ID> AND B IN <@csv:" + csv + ":ID> AND C > 2"));
	}

	@Test
	void aStatementWithoutMacrosIsReturnedUnchanged() throws Exception {
		String sql = "SELECT * FROM T WHERE A > 1 AND B < 2";
		Assertions.assertSame(sql, CommandUtils.substituteMacros(sql));
	}

	@Test
	void scannerReportsEachMacroWithItsOwnClosingDelimiter() throws Exception {
		String sql = "WHERE A > 1 AND X IN <@a.txt> AND B > 2 AND Y IN <@ b.txt >";
		List<ListSourceMacroScanner.Macro> macros = ListSourceMacroScanner.scan(sql);
		Assertions.assertEquals(2, macros.size());
		Assertions.assertEquals("<@a.txt>", sql.substring(macros.get(0).getStart(), macros.get(0).getEnd()));
		Assertions.assertEquals("a.txt", macros.get(0).getToken());
		Assertions.assertEquals("<@ b.txt >", sql.substring(macros.get(1).getStart(), macros.get(1).getEnd()));
		Assertions.assertEquals("b.txt", macros.get(1).getToken());
	}
}
