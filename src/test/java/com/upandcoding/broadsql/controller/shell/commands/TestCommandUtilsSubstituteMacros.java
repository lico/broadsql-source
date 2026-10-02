package com.upandcoding.broadsql.controller.shell.commands;

import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.listsource.ClipboardAccess;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;

/**
 * End-to-end coverage of {@link CommandUtils#substituteMacros} - the entry point every {@code <@...>}
 * pattern goes through, regardless of which {@link com.upandcoding.broadsql.controller.shell.commands.listsource.ListSource}
 * it resolves to. No test existed for this method before (verified: no {@code TestCommandUtils.java} in
 * the repo prior to this change).
 */
class TestCommandUtilsSubstituteMacros {

	@BeforeEach
	@AfterEach
	void resetLastQueryResultHolder() {
		// LastQueryResultHolder is a JVM-wide static (see LastResultListSource) - reset around every
		// test so <@last:...> tests here never depend on, or leak into, another test's state.
		LastQueryResultHolder.set(null);
	}

	@Test
	void substitutesTheLastMacro() throws Exception {
		LastQueryResultHolder.set(new LastQueryResult(List.of("CUSTOMER_ID"),
				Arrays.<String[]>asList(new String[] { "C001" }, new String[] { "C002" }), 2, "TESTCONN"));

		String result = CommandUtils.substituteMacros("SELECT * FROM T WHERE ID IN <@last:CUSTOMER_ID>");

		Assertions.assertEquals("SELECT * FROM T WHERE ID IN ('C001','C002')", result);
	}

	@Test
	void lastMacroKeywordIsCaseInsensitive() throws Exception {
		LastQueryResultHolder.set(new LastQueryResult(List.of("ID"), Arrays.<String[]>asList(new String[] { "1" }), 1, null));

		Assertions.assertEquals("SELECT * FROM T WHERE ID IN ('1')",
				CommandUtils.substituteMacros("SELECT * FROM T WHERE ID IN <@LAST:ID>"));
	}

	@Test
	void lastMacroEscapesEmbeddedSingleQuotesLikeEveryOtherSource() throws Exception {
		LastQueryResultHolder.set(new LastQueryResult(List.of("NAME"), Arrays.<String[]>asList(new String[] { "O'BRIEN" }), 1, null));

		Assertions.assertEquals("SELECT * FROM T WHERE NAME IN ('O''BRIEN')",
				CommandUtils.substituteMacros("SELECT * FROM T WHERE NAME IN <@last:NAME>"));
	}

	@Test
	void lastMacroWithNoPreviousResultFailsClearly() {
		LastQueryResultHolder.set(null);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> CommandUtils.substituteMacros("SELECT * FROM T WHERE ID IN <@last:ID>"));
		Assertions.assertTrue(ex.getMessage().contains("No previous query result"), "got: " + ex.getMessage());
	}

	@Test
	void lastMacroWithAnEmptyResultFailsClearlyInsteadOfGeneratingInParens() {
		LastQueryResultHolder.set(new LastQueryResult(List.of("ID"), List.of(), 0, null));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> CommandUtils.substituteMacros("SELECT * FROM T WHERE ID IN <@last:ID>"));
		Assertions.assertTrue(ex.getMessage().toLowerCase().contains("no usable values"), "got: " + ex.getMessage());
	}

	@Test
	void substitutesAFileMacroIntoAQuotedList(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("ids.txt");
		Files.writeString(file, "A\nB\nC\n", StandardCharsets.UTF_8);

		String result = CommandUtils.substituteMacros("SELECT * FROM T WHERE ID IN <@" + file + ">");

		Assertions.assertEquals("SELECT * FROM T WHERE ID IN ('A','B','C')", result);
	}

	@Test
	void escapesEmbeddedSingleQuotes(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("ids.txt");
		Files.writeString(file, "O'BRIEN\n", StandardCharsets.UTF_8);

		String result = CommandUtils.substituteMacros("SELECT * FROM T WHERE NAME IN <@" + file + ">");

		Assertions.assertEquals("SELECT * FROM T WHERE NAME IN ('O''BRIEN')", result,
				"the original implementation produced the broken literal 'O'BRIEN' here - fixed as part of the list-source refactor");
	}

	@Test
	void numericLookingValuesStayQuoted(@TempDir Path tempDir) throws Exception {
		// Unchanged from <@file>'s original behavior: no numeric auto-detection.
		Path file = tempDir.resolve("ids.txt");
		Files.writeString(file, "00123\n456\n", StandardCharsets.UTF_8);

		String result = CommandUtils.substituteMacros("SELECT * FROM T WHERE ID IN <@" + file + ">");

		Assertions.assertEquals("SELECT * FROM T WHERE ID IN ('00123','456')", result);
	}

	@Test
	void anEmptyFileFailsClearlyInsteadOfGeneratingInParens(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("empty.txt");
		Files.writeString(file, "\n\n", StandardCharsets.UTF_8);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> CommandUtils.substituteMacros("SELECT * FROM T WHERE ID IN <@" + file + ">"));
		Assertions.assertTrue(ex.getMessage().toLowerCase().contains("no usable values"), "got: " + ex.getMessage());
	}

	@Test
	void substitutesMultipleReferencesInOneQuery(@TempDir Path tempDir) throws Exception {
		Path file1 = tempDir.resolve("a.txt");
		Path file2 = tempDir.resolve("b.txt");
		Files.writeString(file1, "A\n", StandardCharsets.UTF_8);
		Files.writeString(file2, "B\n", StandardCharsets.UTF_8);

		String result = CommandUtils.substituteMacros(
				"SELECT * FROM T WHERE X IN <@" + file1 + "> OR Y IN <@" + file2 + ">");

		Assertions.assertEquals("SELECT * FROM T WHERE X IN ('A') OR Y IN ('B')", result);
	}

	@Test
	void missingFileFailsWithTheOriginalMessage() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> CommandUtils.substituteMacros("SELECT * FROM T WHERE ID IN <@does-not-exist-xyz.txt>"));
		Assertions.assertEquals("File does-not-exist-xyz.txt not found", ex.getMessage());
	}

	@Test
	void substitutesTheClipboardMacro() throws Exception {
		Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no system clipboard available in a headless environment");
		try {
			ClipboardAccess.writeText("X\nY\n");
		} catch (BroadSQLException e) {
			// See TestClipboardListSource.requiresAUsableSystemClipboard: the OS clipboard can be locked
			// by something external to this session even in a non-headless environment.
			Assumptions.abort("system clipboard not currently usable in this environment: " + e.getMessage());
		}

		String result = CommandUtils.substituteMacros("SELECT * FROM T WHERE ID IN <@clipboard>");

		Assertions.assertEquals("SELECT * FROM T WHERE ID IN ('X','Y')", result);
	}
}
