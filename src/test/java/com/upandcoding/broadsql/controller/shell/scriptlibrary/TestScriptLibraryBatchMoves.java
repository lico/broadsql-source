package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryService.MoveRequest;

/**
 * SPRINT 3009A correction pass: moving several Scripts and folders at once (multiple selection, drag and drop).
 * The whole batch is normalized and validated before anything moves; a filesystem failure in the middle moves
 * back what had moved; identities and histories follow every moved Script.
 */
class TestScriptLibraryBatchMoves {

	private static ScriptLibraryService serviceFor(Path lib, Path vault) {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setScriptsLibraryPath(lib.toString());
		settings.setScriptHistoryVaultRaw(vault.toString());
		return new ScriptLibraryService(settings);
	}

	private static void files(Path lib, String... paths) throws IOException {
		for (String path : paths) {
			Files.createDirectories(lib.resolve(path).getParent());
			Files.writeString(lib.resolve(path), "select '" + path + "';");
		}
	}

	private static MoveRequest script(String path) {
		return new MoveRequest(path, false);
	}

	private static MoveRequest folder(String path) {
		return new MoveRequest(path, true);
	}

	@Test
	void severalScriptsMoveTogether(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		files(lib, "a.sql", "b.sql", "c.sql");
		Files.createDirectories(lib.resolve("archive"));
		ScriptLibraryService service = serviceFor(lib, vault);
		String a = service.open("a.sql").assetId();

		ScriptLibraryService.MoveOutcome outcome = service.moveItems(List.of(script("a.sql"), script("b.sql"), script("c.sql")), "archive");

		Assertions.assertEquals(List.of("archive/a.sql", "archive/b.sql", "archive/c.sql"), outcome.newKeys());
		for (String name : List.of("a.sql", "b.sql", "c.sql")) {
			Assertions.assertFalse(Files.exists(lib.resolve(name)));
			Assertions.assertTrue(Files.exists(lib.resolve("archive/" + name)));
		}
		Assertions.assertEquals(a, service.open("archive/a.sql").assetId(), "identity and history follow");
	}

	@Test
	void severalFoldersAndAMixOfFoldersAndScriptsMoveTogether(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		files(lib, "q/x.sql", "r/y.sql", "z.sql");
		Files.createDirectories(lib.resolve("dest"));
		ScriptLibraryService service = serviceFor(lib, vault);
		String y = service.open("r/y.sql").assetId();

		ScriptLibraryService.MoveOutcome outcome = service.moveItems(List.of(folder("q"), folder("r"), script("z.sql")), "dest");

		Assertions.assertEquals(List.of("dest/q", "dest/r", "dest/z.sql"), outcome.newKeys());
		Assertions.assertEquals(List.of("dest/q/x.sql", "dest/r/y.sql", "dest/z.sql"), outcome.movedScripts().stream().map(ScriptAsset::relativePath).toList());
		Assertions.assertEquals(y, service.open("dest/r/y.sql").assetId());
	}

	@Test
	void anItemInsideASelectedFolderMovesOnlyOnceWithItsFolder(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		files(lib, "reports/january.sql", "reports/february.sql");
		Files.createDirectories(lib.resolve("archive"));
		ScriptLibraryService service = serviceFor(lib, vault);

		ScriptLibraryService.MoveOutcome outcome = service.moveItems(List.of(folder("reports"), script("reports/january.sql"), script("reports/january.sql")),
				"archive");

		Assertions.assertEquals(List.of("archive/reports"), outcome.newKeys(), "the descendant and the duplicate are dropped from the move set");
		Assertions.assertTrue(Files.exists(lib.resolve("archive/reports/january.sql")));
		Assertions.assertTrue(Files.exists(lib.resolve("archive/reports/february.sql")));
		Assertions.assertFalse(Files.exists(lib.resolve("archive/january.sql")), "never moved a second time, out of its folder");
	}

	@Test
	void aConflictAnywhereInTheBatchIsFoundBeforeAnythingMoves(@TempDir Path lib, @TempDir Path vault) throws IOException {
		files(lib, "a.sql", "b.sql", "archive/b.sql");
		ScriptLibraryService service = serviceFor(lib, vault);

		BroadSQLException refused = Assertions.assertThrows(BroadSQLException.class,
				() -> service.moveItems(List.of(script("a.sql"), script("b.sql")), "archive"));

		Assertions.assertTrue(refused.getMessage().contains("archive/b.sql") && refused.getMessage().contains("already exists"), refused.getMessage());
		Assertions.assertTrue(Files.exists(lib.resolve("a.sql")), "the first, valid item did not move either");
		Assertions.assertFalse(Files.exists(lib.resolve("archive/a.sql")));
	}

	@Test
	void twoItemsLandingOnTheSameNameAreRefused(@TempDir Path lib, @TempDir Path vault) throws IOException {
		files(lib, "p/x.sql", "q/x.sql");
		Files.createDirectories(lib.resolve("dest"));
		ScriptLibraryService service = serviceFor(lib, vault);

		BroadSQLException refused = Assertions.assertThrows(BroadSQLException.class,
				() -> service.moveItems(List.of(script("p/x.sql"), script("q/x.sql")), "dest"));

		Assertions.assertTrue(refused.getMessage().contains("would both become"), refused.getMessage());
		Assertions.assertTrue(Files.exists(lib.resolve("p/x.sql")));
		Assertions.assertTrue(Files.exists(lib.resolve("q/x.sql")));
	}

	@Test
	void aFolderOfTheBatchIntoItsOwnDescendantRefusesTheWholeBatch(@TempDir Path lib, @TempDir Path vault) throws IOException {
		files(lib, "a.sql", "reports/2026/q1.sql");
		ScriptLibraryService service = serviceFor(lib, vault);

		Assertions.assertThrows(BroadSQLException.class, () -> service.moveItems(List.of(script("a.sql"), folder("reports")), "reports/2026"));
		Assertions.assertTrue(Files.exists(lib.resolve("a.sql")));
		Assertions.assertTrue(Files.exists(lib.resolve("reports/2026/q1.sql")));
	}

	@Test
	void itemsAlreadyInTheDestinationAreSkippedAndAllAlreadyThereIsRefused(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		files(lib, "archive/old.sql", "new.sql");
		ScriptLibraryService service = serviceFor(lib, vault);

		Assertions.assertThrows(BroadSQLException.class, () -> service.moveItems(List.of(script("archive/old.sql")), "archive"));
		ScriptLibraryService.MoveOutcome outcome = service.moveItems(List.of(script("archive/old.sql"), script("new.sql")), "archive");
		Assertions.assertEquals(List.of("archive/new.sql"), outcome.newKeys());
	}

	@Test
	void aFilesystemFailureInTheMiddleMovesBackWhatHadMoved(@TempDir Path lib, @TempDir Path vault) throws IOException, BroadSQLException {
		files(lib, "a.sql", "b.sql", "c.sql");
		Files.createDirectories(lib.resolve("archive"));
		ScriptLibraryService service = serviceFor(lib, vault);
		String a = service.open("a.sql").assetId();
		int aRevisions = service.revisionVault().listRevisions(a).size();
		AtomicInteger moves = new AtomicInteger();
		service.setPathMoverForTesting((source, target) -> {
			if (moves.incrementAndGet() == 2) {
				throw new java.nio.file.AccessDeniedException(source.toString());
			}
			Files.move(source, target);
		});

		BroadSQLException failure = Assertions.assertThrows(BroadSQLException.class,
				() -> service.moveItems(List.of(script("a.sql"), script("b.sql"), script("c.sql")), "archive"));

		Assertions.assertTrue(failure.getMessage().startsWith("Could not move 'b.sql'"), failure.getMessage());
		Assertions.assertFalse(failure.getMessage().contains("could not be moved back"), failure.getMessage());
		for (String name : List.of("a.sql", "b.sql", "c.sql")) {
			Assertions.assertTrue(Files.exists(lib.resolve(name)), name + " is where it was");
			Assertions.assertFalse(Files.exists(lib.resolve("archive/" + name)));
		}
		Assertions.assertEquals(aRevisions, service.revisionVault().listRevisions(a).size(), "no history recorded for a move that was undone");
		Assertions.assertEquals(a, service.open("a.sql").assetId());
	}

	@Test
	void anItemThatCannotBeMovedBackIsNamedInTheError(@TempDir Path lib, @TempDir Path vault) throws IOException {
		files(lib, "a.sql", "b.sql");
		Files.createDirectories(lib.resolve("archive"));
		ScriptLibraryService service = serviceFor(lib, vault);
		AtomicInteger moves = new AtomicInteger();
		service.setPathMoverForTesting((source, target) -> {
			int n = moves.incrementAndGet();
			if (n >= 2) { // the second move fails, and so does moving the first one back
				throw new java.nio.file.AccessDeniedException(source.toString());
			}
			Files.move(source, target);
		});

		BroadSQLException failure = Assertions.assertThrows(BroadSQLException.class,
				() -> service.moveItems(List.of(script("a.sql"), script("b.sql")), "archive"));

		Assertions.assertTrue(failure.getMessage().contains("a.sql is now archive/a.sql"), failure.getMessage());
		Assertions.assertTrue(Files.exists(lib.resolve("archive/a.sql")), "the disk really is in that state, as reported");
	}
}
