package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibEdit;
import com.upandcoding.broadsql.controller.shell.commands.extensions.CommandEdit;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.FakeScriptLibraryLauncher;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryLauncher;

/**
 * {@code EDIT} and {@code LIB EDIT} (with no name, or with a relative Script path) open/focus the BroadSQL Editor on the Scripts
 * Library (SPRINT 1909S): a name is a Scripts Library path resolved by {@code ScriptResolver}, with no
 * alias/basename/extension guessing and no catalog to choose between. A {@link FakeScriptLibraryLauncher}
 * verifies the open/focus decision headlessly.
 */
class TestCommandEdit {

	private interface Setter {
		void set(ScriptLibraryLauncher launcher);
	}

	private static ConsoleSettings settingsFor(Path library) {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setScriptsLibraryPath(library.toString());
		return settings;
	}

	private void checkAll(String keyword, Path library, String argument, List<String> expectedCalls, String expectedOpened) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ConsoleSettings settings = settingsFor(library);
		FakeScriptLibraryLauncher launcher = new FakeScriptLibraryLauncher();
		String query = argument == null ? keyword : keyword + " " + argument;
		switch (keyword) {
		case "EDIT" -> {
			CommandEdit cmd = CommandTestSupport.create(CommandEdit.class, null, console, settings);
			cmd.setScriptLibraryLauncher(launcher);
			cmd.execute(query);
		}
		case "LIB EDIT" -> {
			CommandLibEdit cmd = CommandTestSupport.create(CommandLibEdit.class, null, console, settings);
			cmd.setScriptLibraryLauncher(launcher);
			cmd.execute(query);
		}
		default -> throw new IllegalArgumentException(keyword);
		}
		Assertions.assertEquals(expectedCalls, launcher.calls, keyword);
		if (expectedOpened != null) {
			Assertions.assertEquals(expectedOpened, launcher.openedRelativePath, keyword);
		}
	}

	@Test
	void noArgumentOpensTheWorkspaceWithNothingPreselected(@TempDir Path library) throws BroadSQLException {
		for (String keyword : new String[] { "EDIT", "LIB EDIT" }) {
			checkAll(keyword, library, null, List.of("openWorkspace"), null);
		}
	}

	@Test
	void libEditWithNoArgumentOpensTheEditorAndWithAPathOpensThatScript(@TempDir Path library) throws IOException, BroadSQLException {
		Files.writeString(library.resolve("one.bsql"), "select 1;");
		checkAll("LIB EDIT", library, null, List.of("openWorkspace"), null);
		checkAll("LIB EDIT", library, "one.bsql", List.of("openAsset"), "one.bsql");
	}

	@Test
	void aNameThatMatchesNothingOffersToCreateIt(@TempDir Path library) throws BroadSQLException {
		for (String keyword : new String[] { "EDIT", "LIB EDIT" }) {
			checkAll(keyword, library, "nonexistent.bsql", List.of("openWorkspace", "offerCreate"), null);
		}
	}

	@Test
	void aMissingLibraryFolderStillOffersToCreateBecauseTheEditorCreatesItLazily(@TempDir Path tmp) throws BroadSQLException {
		checkAll("EDIT", tmp.resolve("not-yet-created"), "first.bsql", List.of("openWorkspace", "offerCreate"), null);
	}

	@Test
	void anExistingScriptOpensDirectlyWhateverItsExtension(@TempDir Path library) throws IOException, BroadSQLException {
		for (String name : new String[] { "customer.bsql", "customer.sql", "customer.txt", "customer" }) {
			Files.writeString(library.resolve(name), "select 1;");
			checkAll("EDIT", library, name, List.of("openAsset"), name);
		}
	}

	@Test
	void aScriptInASubfolderOpensByItsLibraryPath(@TempDir Path library) throws IOException, BroadSQLException {
		Files.createDirectories(library.resolve("maintenance"));
		Files.writeString(library.resolve("maintenance").resolve("cleanup.bsql"), "select 1;");
		checkAll("LIB EDIT", library, "maintenance/cleanup.bsql", List.of("openAsset"), "maintenance/cleanup.bsql");
	}

	@Test
	void noExtensionIsGuessedSoAnExtensionlessNameOfAnExistingBsqlFileOffersToCreate(@TempDir Path library) throws IOException, BroadSQLException {
		Files.writeString(library.resolve("customer.bsql"), "select 1;");
		checkAll("EDIT", library, "customer", List.of("openWorkspace", "offerCreate"), null);
	}

	@Test
	void aPathThatEscapesTheLibraryIsAnErrorNotAnOffer(@TempDir Path tmp) throws IOException {
		Path library = Files.createDirectories(tmp.resolve("lib"));
		Assertions.assertThrows(BroadSQLException.class, () -> checkAll("EDIT", library, "../outside.bsql", List.of(), null));
	}

	@Test
	void aFolderOrABinaryFileCannotBeOpened(@TempDir Path library) throws IOException {
		Files.createDirectories(library.resolve("adir"));
		Files.write(library.resolve("blob.bin"), new byte[] { 0, 1, 2, 3, 0, 0 });
		Assertions.assertThrows(BroadSQLException.class, () -> checkAll("EDIT", library, "adir", List.of(), null));
		Assertions.assertThrows(BroadSQLException.class, () -> checkAll("EDIT", library, "blob.bin", List.of(), null));
	}
}
