package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibDel;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibFind;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibLint;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibRestore;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibShow;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibUndo;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;

/**
 * SPRINT 1909S: {@code LIB SHOW/FIND/LINT/DEL/RESTORE/UNDO} on the Scripts Library. Names are Scripts
 * Library paths resolved by {@code ScriptResolver} (no alias, basename or extension guessing); deletion is
 * the library's single archive model.
 */
class TestLibManagement {

	/** A console that answers the {@code LIB DEL} y/n confirmation instead of blocking on a real terminal. */
	private static final class AnsweringConsole extends CapturingShellConsole {
		private final String answer;
		String lastPrompt;

		AnsweringConsole(String answer) {
			this.answer = answer;
		}

		@Override
		public String inputField(DatabaseDefinition def, String fieldName, String currentValue, boolean nullAllowed, boolean isPassword, boolean isYesNo,
				Set<String> listOfValues) {
			lastPrompt = fieldName;
			return answer;
		}
	}

	@TempDir
	Path library;

	ConsoleSettings settings;

	@BeforeEach
	void setUp() {
		settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(library.toString());
	}

	@AfterEach
	void clearHook() {
		ScriptsLibrary.setHistoryLinkage(null);
	}

	private Path put(String relative, String text) throws IOException {
		Path file = library.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	// ---- LIB SHOW ----

	@Test
	void showPrintsTheContentOfAScriptInASubfolder() throws Exception {
		put("maintenance/cleanup.bsql", "-- @description: nightly\nDELETE FROM T;");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibShow cmd = CommandTestSupport.create(CommandLibShow.class, null, console, settings);

		cmd.execute("LIB SHOW maintenance/cleanup.bsql");

		Assertions.assertTrue(console.getOutput().contains("DELETE FROM T;"), console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("@description: nightly"), "the header is shown too: " + console.getOutput());
	}

	@Test
	void showDoesNotResolveAliasesBasenamesOrExtensions() throws Exception {
		put("deep/thing.bsql", "-- @alias: nick\nSELECT 1;");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibShow cmd = CommandTestSupport.create(CommandLibShow.class, null, console, settings);

		for (String fuzzy : new String[] { "thing.bsql", "nick", "deep/thing", "thing" }) {
			BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("LIB SHOW " + fuzzy), fuzzy);
			Assertions.assertTrue(e.getMessage().contains("does not contain"), e.getMessage());
		}
	}

	@Test
	void showRejectsAPathOutsideTheLibrary() throws Exception {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibShow cmd = CommandTestSupport.create(CommandLibShow.class, null, console, settings);
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("LIB SHOW ../secret.txt"));
		Assertions.assertTrue(e.getMessage().contains("outside the Scripts Library"), e.getMessage());
	}

	@Test
	void showRequiresAName() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibShow cmd = CommandTestSupport.create(CommandLibShow.class, null, console, settings);
		Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("LIB SHOW"));
	}

	@Test
	void showWarnsOnEnvironmentAndInstanceMismatchButStillShowsContent() throws Exception {
		put("prod.bsql", "-- @environment: PROD\n-- @instance: MYSAP\nSELECT 1;");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibShow cmd = CommandTestSupport.create(CommandLibShow.class, null, console, settings);
		CommandTestSupport.wireInstanceAndEnvironment(cmd, "DB1", "JIRA", "TEST");

		cmd.execute("LIB SHOW prod.bsql");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("is tagged for environment PROD"), output);
		Assertions.assertTrue(output.contains("is tagged for instance MYSAP"), output);
		Assertions.assertTrue(output.contains("SELECT 1;"), output);
	}

	// ---- LIB FIND ----

	@Test
	void findSearchesContentAcrossSubfoldersRegardlessOfEnvironment() throws Exception {
		put("a/one.bsql", "-- @environment: PROD\nSELECT revenue FROM T;");
		put("two.txt", "SELECT other FROM T;");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibFind cmd = CommandTestSupport.create(CommandLibFind.class, null, console, settings);
		CommandTestSupport.wireEnvironment(cmd, "DB1", "TEST");

		cmd.execute("LIB FIND revenue");

		Assertions.assertTrue(console.getOutput().contains("a/one.bsql"), console.getOutput());
		Assertions.assertFalse(console.getOutput().contains("two.txt"), console.getOutput());
	}

	@Test
	void findReportsNoMatch() throws Exception {
		put("a.bsql", "SELECT 1;");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibFind cmd = CommandTestSupport.create(CommandLibFind.class, null, console, settings);
		cmd.execute("LIB FIND zzz");
		Assertions.assertTrue(console.getOutput().contains("No script in the Scripts Library matches 'zzz'"), console.getOutput());
	}

	// ---- LIB LINT ----

	@Test
	void lintReportsNoIssuesForACleanLibrary() throws Exception {
		put("a.bsql", "-- @params: x\nSELECT * FROM T WHERE X = ${x};");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibLint cmd = CommandTestSupport.create(CommandLibLint.class, null, console, settings);
		cmd.execute("LIB LINT");
		Assertions.assertTrue(console.getOutput().contains("no issues found"), console.getOutput());
	}

	@Test
	void lintReportsLegacyPositionalParametersInAnyScript() throws Exception {
		// SPRINT 0110A: the non-contiguous %N rule is replaced by the legacy positional parameter rule
		put("sub/gap.txt", "SELECT * FROM T WHERE A = %1 AND C = %3;");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibLint cmd = CommandTestSupport.create(CommandLibLint.class, null, console, settings);
		cmd.execute("LIB LINT");
		Assertions.assertTrue(console.getOutput().contains("sub/gap.txt: statement 1: possible legacy positional parameter %1"), console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("possible legacy positional parameter %3"), console.getOutput());
		Assertions.assertFalse(console.getOutput().contains("missing %2"), console.getOutput());
	}

	@Test
	void lintNoLongerChecksAliasesAndAnAliasLineIsInert() throws Exception {
		put("a.bsql", "-- @alias: same\nSELECT 1;");
		put("b.bsql", "-- @alias: same\nSELECT 2;");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibLint cmd = CommandTestSupport.create(CommandLibLint.class, null, console, settings);
		cmd.execute("LIB LINT");
		Assertions.assertTrue(console.getOutput().contains("no issues found"), console.getOutput());
	}

	@Test
	void lintOfOneScriptReportsOnlyThatScript() throws Exception {
		put("gap1.bsql", "SELECT %1, %3;");
		put("gap2.bsql", "SELECT %1, %4;");
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibLint cmd = CommandTestSupport.create(CommandLibLint.class, null, console, settings);
		cmd.execute("LIB LINT gap1.bsql");
		Assertions.assertTrue(console.getOutput().contains("gap1.bsql"), console.getOutput());
		Assertions.assertFalse(console.getOutput().contains("gap2.bsql"), console.getOutput());
	}

	// ---- LIB DEL / RESTORE / UNDO: one archive model ----

	@Test
	void delArchivesAfterConfirmationAndRestoreBringsItBack() throws Exception {
		Path file = put("maintenance/old.bsql", "SELECT 1;");
		AnsweringConsole console = new AnsweringConsole("y");
		CommandLibDel del = CommandTestSupport.create(CommandLibDel.class, null, console, settings);

		del.execute("LIB DEL maintenance/old.bsql");

		Assertions.assertFalse(Files.exists(file));
		Assertions.assertTrue(console.lastPrompt.contains("maintenance/old.bsql"), console.lastPrompt);
		Assertions.assertEquals(1, new ScriptsLibrary(library.toString()).getArchivedEntries().size());

		CapturingShellConsole restoreConsole = new CapturingShellConsole();
		CommandLibRestore restore = CommandTestSupport.create(CommandLibRestore.class, null, restoreConsole, settings);
		restore.execute("LIB RESTORE maintenance/old.bsql");
		Assertions.assertTrue(Files.exists(file));
		Assertions.assertTrue(restoreConsole.getOutput().contains("Restored 'maintenance/old.bsql'"), restoreConsole.getOutput());
	}

	@Test
	void delAbortsWhenNotConfirmed() throws Exception {
		Path file = put("keep.bsql", "SELECT 1;");
		AnsweringConsole console = new AnsweringConsole("n");
		CommandLibDel del = CommandTestSupport.create(CommandLibDel.class, null, console, settings);
		del.execute("LIB DEL keep.bsql");
		Assertions.assertTrue(Files.exists(file));
		Assertions.assertTrue(console.getOutput().contains("Operation aborted"), console.getOutput());
	}

	@Test
	void delOfAMissingOrFuzzyNameIsAClearError() throws Exception {
		put("deep/thing.bsql", "SELECT 1;");
		AnsweringConsole console = new AnsweringConsole("y");
		CommandLibDel del = CommandTestSupport.create(CommandLibDel.class, null, console, settings);
		Assertions.assertThrows(BroadSQLException.class, () -> del.execute("LIB DEL thing.bsql"));
		Assertions.assertThrows(BroadSQLException.class, () -> del.execute("LIB DEL nope"));
		Assertions.assertTrue(Files.exists(library.resolve("deep/thing.bsql")));
	}

	@Test
	void undoRestoresTheMostRecentArchive() throws Exception {
		ScriptsLibrary lib = new ScriptsLibrary(library.toString());
		lib.archive(put("a.bsql", "a"));
		lib.archive(put("b.bsql", "b"));
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibUndo undo = CommandTestSupport.create(CommandLibUndo.class, null, console, settings);

		undo.execute("LIB UNDO");

		Assertions.assertTrue(Files.exists(library.resolve("b.bsql")));
		Assertions.assertFalse(Files.exists(library.resolve("a.bsql")));
	}

	@Test
	void undoAndRestoreWithNothingArchivedAreClearErrors() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibUndo undo = CommandTestSupport.create(CommandLibUndo.class, null, console, settings);
		CommandLibRestore restore = CommandTestSupport.create(CommandLibRestore.class, null, console, settings);
		Assertions.assertThrows(BroadSQLException.class, () -> undo.execute("LIB UNDO"));
		Assertions.assertThrows(BroadSQLException.class, () -> restore.execute("LIB RESTORE x.bsql"));
		Assertions.assertThrows(BroadSQLException.class, () -> restore.execute("LIB RESTORE"));
	}

	@Test
	void restoreMatchesTheExactOriginalPathOnly() throws Exception {
		new ScriptsLibrary(library.toString()).archive(put("deep/thing.bsql", "x"));
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibRestore restore = CommandTestSupport.create(CommandLibRestore.class, null, console, settings);
		Assertions.assertThrows(BroadSQLException.class, () -> restore.execute("LIB RESTORE thing.bsql"));
		restore.execute("LIB RESTORE deep/thing.bsql");
		Assertions.assertTrue(Files.exists(library.resolve("deep/thing.bsql")));
	}

	@Test
	void everyLibCommandNamesAMissingLibraryFolderInItsError(@TempDir Path tmp) {
		settings.setScriptsLibraryPath(tmp.resolve("absent").toString());
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLibShow show = CommandTestSupport.create(CommandLibShow.class, null, console, settings);
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> show.execute("LIB SHOW a.bsql"));
		Assertions.assertTrue(e.getMessage().contains("absent"), e.getMessage());
	}
}
