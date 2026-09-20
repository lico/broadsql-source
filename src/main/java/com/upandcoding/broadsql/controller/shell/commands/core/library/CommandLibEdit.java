package com.upandcoding.broadsql.controller.shell.commands.core.library;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;
import com.sun.jna.Platform;

/**
 * Opens a library entry in Notepad, and saves it back into the library on close: {@code LIB EDIT <name>}.
 *
 * <p>Mirrors {@code EDIT}'s no-argument mode (see {@code CommandEdit.editLastQuery()}): writes the
 * entry's current content to a real temporary {@code .sql} file, shells to {@code notepad.exe} via
 * {@link CommandUtils#openInNotepadAndWaitForClose(java.io.File)}, waits for it to close, reads the
 * file back, and writes the result into the library under {@code name} (unlike {@code EDIT}, which
 * only updates the in-memory last query, this persists straight to the library file). Windows-only,
 * same constraint as {@code EDIT}'s no-argument mode.
 *
 * <p>If {@code name} doesn't already exist in the library, the temp file isn't empty: it's pre-filled
 * with a metadata skeleton (see {@link #SEED_SKELETON}) instead, so the header is something to edit
 * rather than something to remember to add from scratch. See
 * {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}, section 10.
 */
public class CommandLibEdit extends Command {

	static final String SEED_SKELETON = "-- @description:\n"
			+ "-- @instance: NONE\n"
			+ "-- @environment: NONE\n"
			+ "-- @tags:\n"
			+ "-- @alias:\n"
			+ "-- @status: draft\n";

	public CommandLibEdit() {
		super("LIB EDIT", "LI ED", "LIED");
	}

	@Override
	public boolean isHidden() {
		return (false);
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		String requested = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		if (StringUtils.isBlank(consoleSettings.getLibraryPath())) {
			console.println("No SQL library folder specified in the INI file");
			return;
		}
		if (StringUtils.isBlank(requested)) {
			console.println("You must provide a file name");
			return;
		}
		if (!Platform.isWindows()) {
			throw new BroadSQLException("This command is only available for Windows OS");
		}

		FileCatalog catalog = new FileCatalog(consoleSettings.getLibraryPath());
		String resolved = catalog.resolve(requested);
		String targetName = resolved != null ? resolved : withSqlExtension(requested);

		File tempFile = null;
		try {
			tempFile = File.createTempFile("broadsql_lib_edit_", ".sql");
			Files.writeString(tempFile.toPath(), buildSeedContent(catalog, resolved), StandardCharsets.UTF_8);

			CommandUtils.openInNotepadAndWaitForClose(tempFile);

			String edited = Files.readString(tempFile.toPath(), StandardCharsets.UTF_8);
			persist(catalog, targetName, edited);
			console.println("Saved '" + targetName + "' to the library.");
		} catch (IOException | InterruptedException e) {
			throw new BroadSQLException(e);
		} finally {
			if (tempFile != null) {
				tempFile.delete();
			}
		}
		console.println("");
	}

	/**
	 * The content to open in Notepad: the entry's current content if {@code resolved} is non-null
	 * (an existing entry), otherwise {@link #SEED_SKELETON}. No console/process interaction, so it is
	 * directly unit-testable, unlike the Notepad round-trip itself (see {@code TestCommandLibEdit}).
	 */
	String buildSeedContent(FileCatalog catalog, String resolved) throws BroadSQLException {
		return resolved != null ? catalog.getRawContent(resolved) : SEED_SKELETON;
	}

	/** Persists {@code content} into the catalog under {@code name}. No console/process interaction. */
	void persist(FileCatalog catalog, String name, String content) throws BroadSQLException {
		catalog.write(name, content);
	}

	private static String withSqlExtension(String name) {
		return name.toLowerCase().endsWith(".sql") ? name : name + ".sql";
	}

	@Override
	public String getDescription() {
		return ("Opens a library entry in Notepad for editing, and saves it back on close; pre-fills a metadata skeleton for a new entry");
	}

	@Override
	public String getArguments() {
		return "<name> (mandatory) an existing entry, or a new file name";
	}

	@Override
	public String getExamples() {
		return "LIB EDIT COUNTRY.SQL";
	}
}
