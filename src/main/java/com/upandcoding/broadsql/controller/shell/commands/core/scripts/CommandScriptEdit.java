package com.upandcoding.broadsql.controller.shell.commands.core.scripts;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;
import com.sun.jna.Platform;

/**
 * Opens a script in Notepad, and saves it back into the catalog on close: {@code SCRIPT EDIT <name>}.
 * Same shape as {@code LIB EDIT} (see its Javadoc), applied to the {@code Scripts} catalog.
 */
public class CommandScriptEdit extends Command {

	static final String SEED_SKELETON = "-- @description:\n"
			+ "-- @instance: NONE\n"
			+ "-- @environment: NONE\n"
			+ "-- @tags:\n"
			+ "-- @alias:\n"
			+ "-- @status: draft\n";

	public CommandScriptEdit() {
		super("SCRIPT EDIT", "SC ED", "SCED");
	}

	@Override
	public boolean isHidden() {
		return (false);
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		String requested = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		if (StringUtils.isBlank(requested)) {
			console.println("You must provide a file name");
			return;
		}
		if (!Platform.isWindows()) {
			throw new BroadSQLException("This command is only available for Windows OS");
		}

		FileCatalog catalog = new FileCatalog(consoleSettings.getScriptsPath(), BroadSQLErrorMessages.ERR_SCRIPTS_01);
		String resolved = catalog.resolve(requested);
		String targetName = resolved != null ? resolved : withSqlExtension(requested);

		File tempFile = null;
		try {
			tempFile = File.createTempFile("broadsql_script_edit_", ".sql");
			Files.writeString(tempFile.toPath(), buildSeedContent(catalog, resolved), StandardCharsets.UTF_8);

			CommandUtils.openInNotepadAndWaitForClose(tempFile);

			String edited = Files.readString(tempFile.toPath(), StandardCharsets.UTF_8);
			persist(catalog, targetName, edited);
			console.println("Saved '" + targetName + "' to the scripts catalog.");
		} catch (IOException | InterruptedException e) {
			throw new BroadSQLException(e);
		} finally {
			if (tempFile != null) {
				tempFile.delete();
			}
		}
		console.println("");
	}

	/** See {@code CommandLibEdit.buildSeedContent} - no console/process interaction, directly testable. */
	String buildSeedContent(FileCatalog catalog, String resolved) throws BroadSQLException {
		return resolved != null ? catalog.getRawContent(resolved) : SEED_SKELETON;
	}

	/** See {@code CommandLibEdit.persist} - no console/process interaction, directly testable. */
	void persist(FileCatalog catalog, String name, String content) throws BroadSQLException {
		catalog.write(name, content);
	}

	private static String withSqlExtension(String name) {
		return name.toLowerCase().endsWith(".sql") ? name : name + ".sql";
	}

	@Override
	public String getDescription() {
		return ("Opens a scripts catalog entry in Notepad for editing, and saves it back on close; pre-fills a metadata skeleton for a new entry");
	}

	@Override
	public String getArguments() {
		return "<name> (mandatory) an existing entry, or a new file name";
	}

	@Override
	public String getExamples() {
		return "SCRIPT EDIT DAILY.SQL";
	}
}
