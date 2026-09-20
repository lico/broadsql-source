package com.upandcoding.broadsql.controller.shell.commands.core.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

class TestFileCatalog {

	@Test
	void sameNameInDifferentSubfoldersDoesNotCollide(@TempDir Path root) throws IOException, BroadSQLException {
		Files.createDirectories(root.resolve("finance"));
		Files.createDirectories(root.resolve("hr"));
		Files.writeString(root.resolve("finance/country.sql"), "select 1;");
		Files.writeString(root.resolve("hr/country.sql"), "select 2;");

		FileCatalog catalog = new FileCatalog(root.toString());

		Assertions.assertEquals(2, catalog.getList().size(), "both files must be indexed, not one overwriting the other");
		Assertions.assertTrue(catalog.contains("finance/country.sql"));
		Assertions.assertTrue(catalog.contains("hr/country.sql"));
	}

	@Test
	void archivesFolderIsExcludedFromTheWorkingCatalog(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("country.sql"), "select 1;");
		Files.createDirectories(root.resolve("archives"));
		Files.writeString(root.resolve("archives/old.sql.20260101-000000"), "select 0;");

		FileCatalog catalog = new FileCatalog(root.toString());

		Assertions.assertEquals(1, catalog.getList().size());
		Assertions.assertFalse(catalog.contains("archives/old.sql.20260101-000000"));
	}

	@Test
	void resolvesByExactNameByAliasAndByUniqueBasename(@TempDir Path root) throws IOException, BroadSQLException {
		Files.createDirectories(root.resolve("finance"));
		Files.writeString(root.resolve("finance/revenue.sql"), "-- @alias: rev\nselect 1;");
		Files.writeString(root.resolve("unique.sql"), "select 1;");

		FileCatalog catalog = new FileCatalog(root.toString());

		Assertions.assertEquals("finance/revenue.sql", catalog.resolve("finance/revenue.sql"));
		Assertions.assertEquals("finance/revenue.sql", catalog.resolve("REV"), "alias resolution must be case-insensitive");
		Assertions.assertEquals("unique.sql", catalog.resolve("unique"), "missing .sql extension must still resolve");
		Assertions.assertEquals("finance/revenue.sql", catalog.resolve("revenue.sql"), "unique basename must resolve even without the subfolder");
		Assertions.assertNull(catalog.resolve("doesnotexist"));
	}

	@Test
	void ambiguousBasenameDoesNotResolve(@TempDir Path root) throws IOException, BroadSQLException {
		Files.createDirectories(root.resolve("finance"));
		Files.createDirectories(root.resolve("hr"));
		Files.writeString(root.resolve("finance/country.sql"), "select 1;");
		Files.writeString(root.resolve("hr/country.sql"), "select 2;");

		FileCatalog catalog = new FileCatalog(root.toString());

		Assertions.assertNull(catalog.resolve("country.sql"), "ambiguous basename must not silently pick one");
	}

	@Test
	void queryBodyStripsTheMetadataHeaderBeforeJoiningLines(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("q.sql"),
				"-- @description: d\n-- @environment: PROD\nselect *\nfrom country\nwhere id = %1;");

		FileCatalog catalog = new FileCatalog(root.toString());
		String body = catalog.getQueryBody("q.sql");

		Assertions.assertFalse(body.contains("@description"), "the metadata header must never reach the executed query");
		Assertions.assertTrue(body.contains("select *"));
		Assertions.assertTrue(body.contains("where id = %1"));
	}

	@Test
	void getParamNumbersFindsDistinctPlaceholders(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("q.sql"), "select * from t where a = %1 and b = %2 and c = %1;");

		FileCatalog catalog = new FileCatalog(root.toString());

		Assertions.assertEquals(Set.of(1, 2), catalog.getParamNumbers("q.sql"));
	}

	@Test
	void writeCreatesThenOverwritesAnEntry(@TempDir Path root) throws BroadSQLException, IOException {
		FileCatalog catalog = new FileCatalog(root.toString());

		catalog.write("newentry.sql", "-- @description: new\nselect 1;");
		Assertions.assertTrue(Files.exists(root.resolve("newentry.sql")));
		Assertions.assertEquals("-- @description: new\nselect 1;", Files.readString(root.resolve("newentry.sql")));

		catalog.write("newentry.sql", "select 2;");
		Assertions.assertEquals("select 2;", Files.readString(root.resolve("newentry.sql")));
	}

	@Test
	void archiveMovesTheFileOutOfTheWorkingCatalogWithNoConsoleInvolved(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("country.sql"), "select 1;");
		FileCatalog catalog = new FileCatalog(root.toString());

		catalog.archive("country.sql");

		Assertions.assertFalse(Files.exists(root.resolve("country.sql")));
		FileCatalog reloaded = new FileCatalog(root.toString());
		Assertions.assertFalse(reloaded.contains("country.sql"));
		Assertions.assertEquals(1, reloaded.getArchivedEntries().size());
		Assertions.assertEquals("country.sql", reloaded.getArchivedEntries().get(0).getOriginalRelativePath());
	}

	@Test
	void undoRestoresTheMostRecentlyArchivedEntry(@TempDir Path root) throws IOException, BroadSQLException, InterruptedException {
		Files.writeString(root.resolve("a.sql"), "select 'a';");
		Files.writeString(root.resolve("b.sql"), "select 'b';");
		FileCatalog catalog = new FileCatalog(root.toString());
		catalog.archive("a.sql");
		Thread.sleep(1100); // archive timestamps have 1-second resolution
		catalog = new FileCatalog(root.toString());
		catalog.archive("b.sql");

		catalog = new FileCatalog(root.toString());
		catalog.undo();

		Assertions.assertTrue(Files.exists(root.resolve("b.sql")), "the most recently archived entry (b) must come back");
		Assertions.assertFalse(Files.exists(root.resolve("a.sql")), "the older archived entry (a) must stay archived");
	}

	@Test
	void restoreRefusesToOverwriteAnEntryThatAlreadyExistsAgain(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("country.sql"), "select 1;");
		FileCatalog catalog = new FileCatalog(root.toString());
		catalog.archive("country.sql");
		Files.writeString(root.resolve("country.sql"), "select 'a new one now lives here';");

		FileCatalog reloaded = new FileCatalog(root.toString());
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> reloaded.restore("country.sql"));
		Assertions.assertTrue(ex.getMessage().contains("already exists again"), ex.getMessage());
	}

	@Test
	void restoreByNameFindsTheArchivedOriginal(@TempDir Path root) throws IOException, BroadSQLException {
		Files.createDirectories(root.resolve("finance"));
		Files.writeString(root.resolve("finance/revenue.sql"), "select 1;");
		FileCatalog catalog = new FileCatalog(root.toString());
		catalog.archive("finance/revenue.sql");

		FileCatalog reloaded = new FileCatalog(root.toString());
		reloaded.restore("revenue.sql");

		Assertions.assertTrue(Files.exists(root.resolve("finance/revenue.sql")));
	}

	@Test
	void undoWithNothingArchivedReportsAClearError(@TempDir Path root) throws BroadSQLException {
		FileCatalog catalog = new FileCatalog(root.toString());

		Assertions.assertThrows(BroadSQLException.class, catalog::undo);
	}

	@Test
	void blankPathIsRejected() {
		Assertions.assertThrows(BroadSQLException.class, () -> new FileCatalog(""));
	}
}
