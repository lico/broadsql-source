package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibFind;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibList;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * SPRINT 0917-01 corrective pass (SPRINT 1909S: Scripts Library only, no Alias column since the library has no
 * aliases): {@code LIB LIST} and {@code LIB FIND} render the same compact catalog grid, exactly
 * {@code File | Group | Environment | Status | Modified}, close it with a
 * separator, end with the row count, and (FIND) never print a "Matching lines:" section. Exact-output
 * assertions on the table and everything after it.
 */
class TestCatalogCompactOutput {

	private static final String[] COMPACT_TABLE = {
			"|-------|-------|-----------|------|----------------|",
			"|File   |Group  |Environment|Status|Modified        |",
			"|-------|-------|-----------|------|----------------|",
			"|QR1.sql|MYWORLD|PROD       |draft |2026-09-18 16:24|",
			"|-------|-------|-----------|------|----------------|",
	};

	private static void writeEntry(Path dir, String name, String content) throws IOException {
		Path file = dir.resolve(name);
		Files.writeString(file, content);
		Files.setLastModifiedTime(file, FileTime.from(LocalDateTime.of(2026, 9, 18, 16, 24).atZone(ZoneId.systemDefault()).toInstant()));
	}

	/** The output from the table's first separator on: the table itself plus everything after it. */
	private static List<String> fromTheTableOn(CapturingShellConsole console) {
		List<String> lines = new ArrayList<>();
		boolean inTable = false;
		for (String line : console.getOutput().split("\r?\n")) {
			if (line.startsWith("|")) {
				inTable = true;
			}
			if (inTable && !line.isBlank()) {
				lines.add(line);
			}
		}
		return lines;
	}

	private static List<String> expected(String footer) {
		List<String> lines = new ArrayList<>(List.of(COMPACT_TABLE));
		lines.add(footer);
		return lines;
	}

	private static final String ENTRY = "-- @description: A very long description that would have made the table far too wide to read\n"
			+ "-- @instance: MYWORLD\n-- @environment: PROD\n-- @tags: btag,other,and,many,more,tags\n-- @alias: QR1\n-- @status: draft\nselect 1;\n";

	@Test
	void libListAllIsCompactExactAndClosed(@TempDir Path dir) throws BroadSQLException, IOException {
		writeEntry(dir, "QR1.sql", ENTRY);
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(dir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);

		cmd.execute("LIB LIST ALL");

		Assertions.assertEquals(expected("1 rows fetched."), fromTheTableOn(console), console.getOutput());
		String output = console.getOutput();
		Assertions.assertFalse(output.contains("Description") || output.contains("Tags") || output.contains("Params") || output.contains("btag"), output);
	}

	@Test
	void libListKeepsItsDatabaseGroupScopingWhileCompact(@TempDir Path dir) throws BroadSQLException, IOException {
		writeEntry(dir, "QR1.sql", ENTRY);
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(dir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);
		CommandTestSupport.wireInstanceAndEnvironment(cmd, "DB1", "OTHERGROUP", "TEST");

		cmd.execute("LIB LIST");

		Assertions.assertFalse(console.getOutput().contains("QR1.sql"), "an entry for another Group/Environment stays hidden, got:\n" + console.getOutput());
	}

	@Test
	void libFindPrintsTheCompactTableAndRowCountThenStops(@TempDir Path dir) throws BroadSQLException, IOException {
		writeEntry(dir, "QR1.sql", ENTRY);
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(dir.toString());
		CommandLibFind cmd = CommandTestSupport.create(CommandLibFind.class, null, console, settings);

		cmd.execute("LIB FIND btag");

		Assertions.assertEquals(expected("1 rows fetched."), fromTheTableOn(console), console.getOutput());
		Assertions.assertFalse(console.getOutput().contains("Matching lines"), console.getOutput());
	}

	@Test
	void libFindStillSearchesMetadataAndContentEvenThoughItNoLongerShowsTheMatchingLine(@TempDir Path dir) throws BroadSQLException, IOException {
		writeEntry(dir, "QR1.sql", ENTRY);
		writeEntry(dir, "QR2.sql", "select unique_body_token from t;\n");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(dir.toString());
		CommandLibFind cmd = CommandTestSupport.create(CommandLibFind.class, null, console, settings);

		cmd.execute("LIB FIND unique_body_token");
		Assertions.assertTrue(console.getOutput().contains("QR2.sql") && !console.getOutput().contains("QR1.sql"), console.getOutput());

		console.clear();
		cmd.execute("LIB FIND very long description");
		Assertions.assertTrue(console.getOutput().contains("QR1.sql"), "the description is still searched, got:\n" + console.getOutput());
	}
}
