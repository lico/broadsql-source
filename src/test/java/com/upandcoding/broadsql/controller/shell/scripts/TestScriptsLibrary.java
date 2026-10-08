package com.upandcoding.broadsql.controller.shell.scripts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * SPRINT 1909S: {@link ScriptsLibrary}, the management side of the Scripts Library: recursive listing of
 * text Scripts, metadata, confined writes, and the single archive/restore model. It has no name lookup.
 */
class TestScriptsLibrary {

	@AfterEach
	void clearHook() {
		ScriptsLibrary.setHistoryLinkage(null);
	}

	private Path put(Path root, String relative, String text) throws IOException {
		Path file = root.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

	@Test
	void listsEveryTextScriptRecursivelyWhateverItsExtension(@TempDir Path root) throws Exception {
		put(root, "a.bsql", "select 1;");
		put(root, "b.sql", "select 1;");
		put(root, "c.txt", "select 1;");
		put(root, "noext", "select 1;");
		put(root, "sub/deep/d.foo", "select 1;");

		ScriptsLibrary library = new ScriptsLibrary(root.toString());

		Assertions.assertEquals(Set.of("a.bsql", "b.sql", "c.txt", "noext", "sub/deep/d.foo"), Set.copyOf(library.getList()));
	}

	@Test
	void nonTextFilesAndTheReservedArchivesFolderAreNotListed(@TempDir Path root) throws Exception {
		put(root, "ok.bsql", "select 1;");
		Files.write(root.resolve("blob.bin"), new byte[] { 0, 1, 2, 3, 0 });
		put(root, "archives/old.bsql.20260101-000000", "select 0;");

		ScriptsLibrary library = new ScriptsLibrary(root.toString());

		Assertions.assertEquals(List.of("ok.bsql"), List.copyOf(library.getList()));
		Assertions.assertEquals(1, library.getArchivedEntries().size());
		Assertions.assertEquals("old.bsql", library.getArchivedEntries().get(0).getOriginalRelativePath());
	}

	@Test
	void sameNameInDifferentFoldersDoesNotCollide(@TempDir Path root) throws Exception {
		put(root, "finance/country.sql", "select 1;");
		put(root, "hr/country.sql", "select 2;");

		ScriptsLibrary library = new ScriptsLibrary(root.toString());

		Assertions.assertTrue(library.hasKey("finance/country.sql"));
		Assertions.assertTrue(library.hasKey("hr/country.sql"));
		Assertions.assertFalse(library.hasKey("country.sql"), "there is no basename lookup");
	}

	@Test
	void metadataIsParsedButAliasIsInertAndPlaysNoPartInLookup(@TempDir Path root) throws Exception {
		put(root, "q.bsql", "-- @description: d\n-- @environment: PROD\n-- @alias: nick\nselect 1;");

		ScriptsLibrary library = new ScriptsLibrary(root.toString());

		Assertions.assertEquals("d", library.getEntryMetadata("q.bsql").getDescription());
		Assertions.assertFalse(library.hasKey("nick"));
	}

	@Test
	void readReturnsTheEncodingStateAndBodyStripsTheHeader(@TempDir Path root) throws Exception {
		put(root, "q.bsql", "-- @description: d\nselect 1;");

		ScriptsLibrary library = new ScriptsLibrary(root.toString());

		Assertions.assertEquals(StandardCharsets.UTF_8, library.read("q.bsql").getCharset());
		Assertions.assertFalse(library.getBody("q.bsql").contains("@description"));
		Assertions.assertTrue(library.getBody("q.bsql").contains("select 1;"));
	}

	@Test
	void writeIsConfinedToTheLibraryAndAtomic(@TempDir Path tmp) throws Exception {
		Path root = Files.createDirectories(tmp.resolve("lib"));
		ScriptsLibrary library = new ScriptsLibrary(root.toString());

		library.write(root.resolve("new/x.bsql"), ScriptTextIO.TextContent.newFile("select 1;"));
		Assertions.assertEquals("select 1;", Files.readString(root.resolve("new/x.bsql")));

		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class,
				() -> library.write(tmp.resolve("outside.bsql"), ScriptTextIO.TextContent.newFile("x")));
		Assertions.assertTrue(e.getMessage().contains("outside the Scripts Library"), e.getMessage());
		Assertions.assertFalse(Files.exists(tmp.resolve("outside.bsql")));
	}

	@Test
	void archiveMovesTheScriptOutOfTheLibraryKeepingItsFolder(@TempDir Path root) throws Exception {
		Path file = put(root, "maintenance/x.bsql", "select 1;");
		ScriptsLibrary library = new ScriptsLibrary(root.toString());

		String archiveKey = library.archive(file);

		Assertions.assertFalse(Files.exists(file));
		Assertions.assertTrue(archiveKey.startsWith("archives/maintenance/x.bsql."), archiveKey);
		Assertions.assertTrue(Files.exists(root.resolve(archiveKey)));
		ScriptsLibrary reopened = new ScriptsLibrary(root.toString());
		Assertions.assertFalse(reopened.hasKey("maintenance/x.bsql"));
		Assertions.assertEquals("maintenance/x.bsql", reopened.getArchivedEntries().get(0).getOriginalRelativePath());
	}

	@Test
	void twoArchivesOfTheSameFileInOneSecondBothSurvive(@TempDir Path root) throws Exception {
		ScriptsLibrary library = new ScriptsLibrary(root.toString());
		Path file = put(root, "x.bsql", "one");
		String first = library.archive(file);
		put(root, "x.bsql", "two");
		String second = library.archive(root.resolve("x.bsql"));

		Assertions.assertNotEquals(first, second);
		Assertions.assertEquals(2, new ScriptsLibrary(root.toString()).getArchivedEntries().size());
	}

	@Test
	void undoRestoresTheMostRecentlyArchivedScript(@TempDir Path root) throws Exception {
		ScriptsLibrary library = new ScriptsLibrary(root.toString());
		library.archive(put(root, "old.bsql", "old"));
		library.archive(put(root, "new.bsql", "new"));

		new ScriptsLibrary(root.toString()).undo();

		Assertions.assertTrue(Files.exists(root.resolve("new.bsql")));
		Assertions.assertFalse(Files.exists(root.resolve("old.bsql")));
	}

	@Test
	void restoreMatchesTheOriginalPathExactlyAndNeverByBasename(@TempDir Path root) throws Exception {
		ScriptsLibrary library = new ScriptsLibrary(root.toString());
		library.archive(put(root, "deep/thing.bsql", "x"));

		ScriptsLibrary reopened = new ScriptsLibrary(root.toString());
		Assertions.assertThrows(BroadSQLException.class, () -> reopened.restore("thing.bsql"));
		reopened.restore("deep/thing.bsql");
		Assertions.assertTrue(Files.exists(root.resolve("deep/thing.bsql")));
	}

	@Test
	void restoreRefusesToOverwriteAScriptThatExistsAgain(@TempDir Path root) throws Exception {
		ScriptsLibrary library = new ScriptsLibrary(root.toString());
		library.archive(put(root, "x.bsql", "old"));
		put(root, "x.bsql", "new");

		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> new ScriptsLibrary(root.toString()).restore("x.bsql"));
		Assertions.assertTrue(e.getMessage().contains("already exists"), e.getMessage());
		Assertions.assertEquals("new", Files.readString(root.resolve("x.bsql")));
	}

	@Test
	void undoWithNothingArchivedIsAClearError(@TempDir Path root) throws Exception {
		Assertions.assertThrows(BroadSQLException.class, () -> new ScriptsLibrary(root.toString()).undo());
	}

	@Test
	void archiveOfAMissingFileIsAClearError(@TempDir Path root) throws Exception {
		ScriptsLibrary library = new ScriptsLibrary(root.toString());
		Assertions.assertThrows(BroadSQLException.class, () -> library.archive(root.resolve("nope.bsql")));
	}

	@Test
	void theHistoryHookIsToldAboutArchiveAndRestoreAndNeverBlocksThem(@TempDir Path root) throws Exception {
		List<String> events = new ArrayList<>();
		ScriptsLibrary.setHistoryLinkage(new ScriptsLibrary.HistoryLinkage() {
			@Override
			public void archived(Path libraryRoot, String relativeKey, String archiveRelativeKey) {
				events.add("archived " + relativeKey + " -> " + archiveRelativeKey.substring(0, archiveRelativeKey.lastIndexOf('.')));
			}

			@Override
			public void restored(Path libraryRoot, String archiveRelativeKey, String relativeKey) {
				events.add("restored " + relativeKey);
				throw new IllegalStateException("history failure must not propagate");
			}
		});
		ScriptsLibrary library = new ScriptsLibrary(root.toString());
		library.archive(put(root, "x.bsql", "x"));
		new ScriptsLibrary(root.toString()).restore("x.bsql");

		Assertions.assertEquals(List.of("archived x.bsql -> archives/x.bsql", "restored x.bsql"), events);
		Assertions.assertTrue(Files.exists(root.resolve("x.bsql")));
	}

	@Test
	void missingBlankOrFileRootsAreClearErrors(@TempDir Path tmp) throws Exception {
		Assertions.assertThrows(BroadSQLException.class, () -> new ScriptsLibrary(""));
		Assertions.assertThrows(BroadSQLException.class, () -> new ScriptsLibrary(tmp.resolve("nope").toString()));
		Path file = put(tmp, "afile", "x");
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> new ScriptsLibrary(file.toString()));
		Assertions.assertTrue(e.getMessage().contains("file, not a folder"), e.getMessage());
	}
}
