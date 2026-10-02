package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibList;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandLibList {

	@Test
	void aMissingLibraryFolderIsAClearError(@TempDir Path tmp) {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(tmp.resolve("nope").toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);

		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("LIB LIST"));
		Assertions.assertTrue(e.getMessage().contains("The Scripts Library folder does not exist"), e.getMessage());
	}

	@Test
	void reportsAnEmptyLibrary(@TempDir Path libraryDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);

		cmd.execute("LIB LIST");

		Assertions.assertTrue(console.getOutput().contains("The Scripts Library is empty"),
				"expected the empty-library message, got:\n" + console.getOutput());
	}

	@Test
	void listsEveryFileWithNoSearchTerm(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("COUNTRY.sql"), "SELECT * FROM COUNTRY;");
		Files.writeString(libraryDir.resolve("CUSTOMER.sql"), "SELECT * FROM CUSTOMER;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);

		cmd.execute("LIB LIST");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("COUNTRY.sql"), "expected COUNTRY.sql in output, got:\n" + output);
		Assertions.assertTrue(output.contains("CUSTOMER.sql"), "expected CUSTOMER.sql in output, got:\n" + output);
	}

	@Test
	void filtersFilesByASearchTerm(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("COUNTRY.sql"), "SELECT * FROM COUNTRY;");
		Files.writeString(libraryDir.resolve("CUSTOMER.sql"), "SELECT * FROM CUSTOMER;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);

		cmd.execute("LIB LIST COUNTRY");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("COUNTRY.sql"), "expected COUNTRY.sql in output, got:\n" + output);
		Assertions.assertFalse(output.contains("CUSTOMER.sql"), "did not expect CUSTOMER.sql in output, got:\n" + output);
	}

	@Test
	void gridHasTheExpectedColumns(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("COUNTRY.sql"), "-- @description: All countries\n-- @tags: geo\nSELECT * FROM COUNTRY;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);

		cmd.execute("LIB LIST");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("File") && output.contains("Group") && output.contains("Environment")
				&& output.contains("Status") && output.contains("Modified"), "expected the compact grid column headers, got:\n" + output);
		Assertions.assertFalse(output.contains("Alias"), "the Scripts Library has no aliases, so no Alias column, got:\n" + output);
		Assertions.assertFalse(output.contains("Description") || output.contains("Tags") || output.contains("Params"), "no Description/Tags/Params column, got:\n" + output);
		Assertions.assertFalse(output.contains("All countries"), "the description is no longer a grid column, got:\n" + output);
	}

	@Test
	void defaultViewHidesAnEntryTaggedForAnotherEnvironmentButAllShowsIt(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("PRODONLY.sql"), "-- @environment: PROD\nSELECT 1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);
		CommandTestSupport.wireEnvironment(cmd, "DB1", "TEST");

		cmd.execute("LIB LIST");
		Assertions.assertFalse(console.getOutput().contains("PRODONLY.sql"),
				"a PROD-only entry must not show while connected to TEST, got:\n" + console.getOutput());

		console.clear();
		cmd.execute("LIB LIST ALL");
		Assertions.assertTrue(console.getOutput().contains("PRODONLY.sql"),
				"LIB LIST ALL must show every environment, got:\n" + console.getOutput());
	}

	@Test
	void defaultViewHidesAnEntryTaggedForAnotherInstanceButAllShowsIt(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("MYSAPONLY.sql"), "-- @instance: MYSAP\nSELECT 1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);
		CommandTestSupport.wireInstanceAndEnvironment(cmd, "DB1", "JIRA", null);

		cmd.execute("LIB LIST");
		Assertions.assertFalse(console.getOutput().contains("MYSAPONLY.sql"),
				"a MYSAP-only entry must not show while connected to JIRA, got:\n" + console.getOutput());

		console.clear();
		cmd.execute("LIB LIST ALL");
		Assertions.assertTrue(console.getOutput().contains("MYSAPONLY.sql"),
				"LIB LIST ALL must show every instance, got:\n" + console.getOutput());
	}

	@Test
	void defaultViewRequiresBothInstanceAndEnvironmentToMatch(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("BOTH.sql"), "-- @instance: MYSAP\n-- @environment: PROD\nSELECT 1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);
		// Instance matches, environment does not - must still be hidden (both dimensions are required).
		CommandTestSupport.wireInstanceAndEnvironment(cmd, "DB1", "MYSAP", "TEST");

		cmd.execute("LIB LIST");

		Assertions.assertFalse(console.getOutput().contains("BOTH.sql"),
				"an entry must be hidden when only one of its two scoping dimensions matches, got:\n" + console.getOutput());
	}

	@Test
	void untaggedEntryAlwaysShowsRegardlessOfEnvironment(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("ANY.sql"), "SELECT 1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);
		CommandTestSupport.wireEnvironment(cmd, "DB1", "TEST");

		cmd.execute("LIB LIST");

		Assertions.assertTrue(console.getOutput().contains("ANY.sql"), "an untagged (NONE) entry must always show, got:\n" + console.getOutput());
	}

	@Test
	void listsScriptsInSubfoldersAlwaysBecauseTheListingIsRecursive(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("TOPLEVEL.sql"), "SELECT 1;");
		Files.createDirectories(libraryDir.resolve("sub/deeper"));
		Files.writeString(libraryDir.resolve("sub/NESTED.bsql"), "SELECT 1;");
		Files.writeString(libraryDir.resolve("sub/deeper/DEEP.txt"), "SELECT 1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);

		cmd.execute("LIB LIST");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("TOPLEVEL.sql"), output);
		Assertions.assertTrue(output.contains("sub/NESTED.bsql"), "sub-folder Scripts are listed by their library path, got:\n" + output);
		Assertions.assertTrue(output.contains("sub/deeper/DEEP.txt"), output);
		Assertions.assertFalse(output.contains("ListSubfolders"), output);
	}

	@Test
	void nonTextFilesAndTheArchiveAreNotListedAsScripts(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("REAL.bsql"), "SELECT 1;");
		Files.write(libraryDir.resolve("blob.bin"), new byte[] { 0, 1, 2, 3, 0 });
		Files.createDirectories(libraryDir.resolve("archives"));
		Files.writeString(libraryDir.resolve("archives/OLD.bsql.20260101-000000"), "SELECT 0;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);

		cmd.execute("LIB LIST");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("REAL.bsql"), output);
		Assertions.assertFalse(output.contains("blob.bin"), output);
		Assertions.assertFalse(output.contains("OLD.bsql"), output);
	}

	/**
	 * SPRINT 0917-01 corrective acceptance pass, defect 3: the grid was missing its bottom closing
	 * separator - {@code separator/header/separator/row.../row}, never a trailing {@code separator}.
	 * The real bug was in the shared table renderer ({@code ConsolePrinter.printArrayOnConsole}, used by
	 * every {@code LIST}/{@code FIND}/{@code SHOW} table-producing command), not in {@code CommandLibList}
	 * itself - this exact-output regression test pins the fixed shape from {@code LIB LIST}'s own output.
	 */
	@Test
	void gridEndsWithAClosingSeparatorLine(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("COUNTRY.sql"), "SELECT * FROM COUNTRY;");
		Files.writeString(libraryDir.resolve("CUSTOMER.sql"), "SELECT * FROM CUSTOMER;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);

		cmd.execute("LIB LIST");

		String[] lines = console.getOutput().split("\n", -1);
		String lastNonBlank = null;
		for (String line : lines) {
			if (!line.trim().isEmpty()) {
				lastNonBlank = line.trim();
			}
		}
		Assertions.assertNotNull(lastNonBlank, "expected some output, got:\n" + console.getOutput());
		Assertions.assertTrue(lastNonBlank.startsWith("2 rows fetched"), "expected the row-count footer last, got:\n" + console.getOutput());

		java.util.List<String> nonBlank = new java.util.ArrayList<>();
		for (String line : lines) {
			if (!line.trim().isEmpty()) {
				nonBlank.add(line.trim());
			}
		}
		String lastTableLine = nonBlank.get(nonBlank.size() - 2);
		Assertions.assertTrue(lastTableLine.matches("\\|[-|]+"),
				"expected the table's last line before the row-count footer to be a closing separator (only '-'/'|'), got: '" + lastTableLine + "' in:\n" + console.getOutput());
	}

	@Test
	void listsArchivedEntries(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("OLD.sql"), "SELECT 1;");
		new com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary(libraryDir.toString()).archive(libraryDir.resolve("OLD.sql"));
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);

		cmd.execute("LIB LIST ARCHIVES");

		Assertions.assertTrue(console.getOutput().contains("OLD.sql"), "expected the archived entry, got:\n" + console.getOutput());
	}
}
