package com.upandcoding.broadsql.controller.shell.commands.core.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;

/**
 * {@code LIB EDIT}'s actual Notepad round-trip (like {@code EDIT}'s no-argument mode - see
 * {@code TestCommandEdit}'s own Javadoc, {@code docs/TESTS_STRATEGY.md}) cannot be exercised here: it
 * would block waiting on a real GUI window, and {@code CommandEdit}'s existing precedent already
 * excludes that from this repo's automated tests. What CAN be tested, and is here, are the two pieces
 * {@code CommandLibEdit} deliberately keeps free of any console/process interaction: building the seed
 * content (existing entry, or the pre-filled metadata skeleton for a new one) and persisting edited
 * content back into the catalog.
 */
class TestCommandLibEdit {

	@Test
	void seedsANewEntryWithTheMetadataSkeleton(@TempDir Path libraryDir) throws BroadSQLException {
		FileCatalog catalog = new FileCatalog(libraryDir.toString());
		CommandLibEdit cmd = new CommandLibEdit();

		String seed = cmd.buildSeedContent(catalog, null);

		Assertions.assertEquals(CommandLibEdit.SEED_SKELETON, seed);
		Assertions.assertTrue(seed.contains("-- @instance: NONE"));
		Assertions.assertTrue(seed.contains("-- @environment: NONE"));
		Assertions.assertTrue(seed.contains("-- @status: draft"));
	}

	@Test
	void seedsAnExistingEntryWithItsCurrentContent(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("Q.sql"), "-- @description: existing\nselect 1;");
		FileCatalog catalog = new FileCatalog(libraryDir.toString());
		CommandLibEdit cmd = new CommandLibEdit();

		String seed = cmd.buildSeedContent(catalog, "Q.sql");

		Assertions.assertEquals("-- @description: existing\nselect 1;", seed);
	}

	@Test
	void persistWritesTheEditedContentToTheLibrary(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		FileCatalog catalog = new FileCatalog(libraryDir.toString());
		CommandLibEdit cmd = new CommandLibEdit();

		cmd.persist(catalog, "NEW.sql", "-- @description: new one\nselect 1;");

		Assertions.assertEquals("-- @description: new one\nselect 1;", Files.readString(libraryDir.resolve("NEW.sql")));
	}

	@Test
	void persistOverwritesAnExistingEntry(@TempDir Path libraryDir) throws BroadSQLException, IOException {
		Files.writeString(libraryDir.resolve("Q.sql"), "select 1;");
		FileCatalog catalog = new FileCatalog(libraryDir.toString());
		CommandLibEdit cmd = new CommandLibEdit();

		cmd.persist(catalog, "Q.sql", "select 2;");

		Assertions.assertEquals("select 2;", Files.readString(libraryDir.resolve("Q.sql")));
	}
}
