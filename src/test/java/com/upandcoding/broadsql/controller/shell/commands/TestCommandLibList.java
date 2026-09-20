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
	void reportsNoLibraryConfigured() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, console);

		cmd.execute("LIB LIST");

		Assertions.assertTrue(console.getOutput().contains("No SQL library folder specified in the INI file"),
				"expected the no-library message, got:\n" + console.getOutput());
	}

	@Test
	void reportsAnEmptyLibrary(@TempDir Path libraryDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);

		cmd.execute("LIB LIST");

		Assertions.assertTrue(console.getOutput().contains("The SQL library is empty"),
				"expected the empty-library message, got:\n" + console.getOutput());
	}

	@Test
	void listsEveryFileWithNoSearchTerm(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("COUNTRY.sql"), "SELECT * FROM COUNTRY;");
		Files.writeString(libraryDir.resolve("CUSTOMER.sql"), "SELECT * FROM CUSTOMER;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
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
		settings.setLibraryPath(libraryDir.toString());
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
		settings.setLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);

		cmd.execute("LIB LIST");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("File") && output.contains("Description") && output.contains("Instance") && output.contains("Environment")
				&& output.contains("Tags") && output.contains("Status") && output.contains("Modified") && output.contains("Params"),
				"expected the grid column headers, got:\n" + output);
		Assertions.assertTrue(output.contains("All countries"), "expected the description in the grid, got:\n" + output);
	}

	@Test
	void defaultViewHidesAnEntryTaggedForAnotherEnvironmentButAllShowsIt(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("PRODONLY.sql"), "-- @environment: PROD\nSELECT 1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
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
		settings.setLibraryPath(libraryDir.toString());
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
		settings.setLibraryPath(libraryDir.toString());
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
		settings.setLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);
		CommandTestSupport.wireEnvironment(cmd, "DB1", "TEST");

		cmd.execute("LIB LIST");

		Assertions.assertTrue(console.getOutput().contains("ANY.sql"), "an untagged (NONE) entry must always show, got:\n" + console.getOutput());
	}

	@Test
	void hidesSubfolderEntriesByDefaultButListsSubfoldersShowsThem(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("TOPLEVEL.sql"), "SELECT 1;");
		Files.createDirectories(libraryDir.resolve("sub"));
		Files.writeString(libraryDir.resolve("sub/NESTED.sql"), "SELECT 1;");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);

		cmd.execute("LIB LIST");
		String output = console.getOutput();
		Assertions.assertTrue(output.contains("TOPLEVEL.sql"), "expected the top-level entry, got:\n" + output);
		Assertions.assertFalse(output.contains("NESTED.sql"), "a sub-folder entry must not show by default, got:\n" + output);
		Assertions.assertTrue(output.contains("ListSubfolders"), "expected a hint about ListSubfolders, got:\n" + output);

		console.clear();
		settings.setListSubfolders(true);
		cmd.execute("LIB LIST");
		output = console.getOutput();
		Assertions.assertTrue(output.contains("TOPLEVEL.sql"), "expected the top-level entry, got:\n" + output);
		Assertions.assertTrue(output.contains("NESTED.sql"), "ListSubfolders=true must show sub-folder entries too, got:\n" + output);
	}

	@Test
	void listsArchivedEntries(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("OLD.sql"), "SELECT 1;");
		new com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog(libraryDir.toString()).archive("OLD.sql");
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setLibraryPath(libraryDir.toString());
		CommandLibList cmd = CommandTestSupport.create(CommandLibList.class, null, console, settings);

		cmd.execute("LIB LIST ARCHIVES");

		Assertions.assertTrue(console.getOutput().contains("OLD.sql"), "expected the archived entry, got:\n" + console.getOutput());
	}
}
