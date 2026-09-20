package com.upandcoding.broadsql.controller.shell.commands.core.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;

/**
 * Same scope limitation as {@code TestCommandLibEdit}: the actual Notepad round-trip is not
 * exercised here (see that class's Javadoc). Only the console/process-free pieces are tested.
 */
class TestCommandScriptEdit {

	@Test
	void seedsANewEntryWithTheMetadataSkeleton(@TempDir Path scriptsDir) throws BroadSQLException {
		FileCatalog catalog = new FileCatalog(scriptsDir.toString());
		CommandScriptEdit cmd = new CommandScriptEdit();

		String seed = cmd.buildSeedContent(catalog, null);

		Assertions.assertEquals(CommandScriptEdit.SEED_SKELETON, seed);
	}

	@Test
	void seedsAnExistingEntryWithItsCurrentContent(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		Files.writeString(scriptsDir.resolve("Q.sql"), "-- @description: existing\nBYE;");
		FileCatalog catalog = new FileCatalog(scriptsDir.toString());
		CommandScriptEdit cmd = new CommandScriptEdit();

		String seed = cmd.buildSeedContent(catalog, "Q.sql");

		Assertions.assertEquals("-- @description: existing\nBYE;", seed);
	}

	@Test
	void persistWritesTheEditedContentToTheCatalog(@TempDir Path scriptsDir) throws BroadSQLException, IOException {
		FileCatalog catalog = new FileCatalog(scriptsDir.toString());
		CommandScriptEdit cmd = new CommandScriptEdit();

		cmd.persist(catalog, "NEW.sql", "BYE;");

		Assertions.assertEquals("BYE;", Files.readString(scriptsDir.resolve("NEW.sql")));
	}
}
