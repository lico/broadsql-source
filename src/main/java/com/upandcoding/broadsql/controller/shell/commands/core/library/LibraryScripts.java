package com.upandcoding.broadsql.controller.shell.commands.core.library;

import java.nio.file.Path;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.RevisionVault;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptResolver;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;

/**
 * SPRINT 1909S: the two steps every {@code LIB} command shares - open the Scripts Library, and turn the
 * name the user typed into a listed Script through {@link ScriptResolver} (the only place names become
 * paths; there is no alias, basename or extension lookup here).
 */
final class LibraryScripts {

	private LibraryScripts() {
	}

	static ScriptsLibrary open(ConsoleSettings settings) throws BroadSQLException {
		try {
			// so LIB DEL / LIB RESTORE / LIB UNDO keep the editor's revision history in step with the archive
			ScriptsLibrary.setHistoryLinkage(new RevisionVault(settings.resolveScriptHistoryVaultPath()));
		} catch (RuntimeException e) {
			// history is optional: never blocks a LIB command
		}
		return new ScriptsLibrary(settings.getScriptsLibraryPath());
	}

	static Path resolve(ConsoleSettings settings, String reference) throws BroadSQLException {
		return new ScriptResolver(settings.getScriptsLibraryPath()).resolveInLibrary(reference);
	}

	/** The listed Script the reference names; fails clearly if the library does not contain it. */
	static String existingKey(ConsoleSettings settings, ScriptsLibrary library, String reference) throws BroadSQLException {
		String key = library.keyOf(resolve(settings, reference));
		if (!library.hasKey(key)) {
			throw new BroadSQLException("The Scripts Library does not contain '" + key + "'");
		}
		return key;
	}
}
