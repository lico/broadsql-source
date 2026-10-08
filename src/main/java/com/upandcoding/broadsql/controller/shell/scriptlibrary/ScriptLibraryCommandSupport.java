package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.nio.file.Files;
import java.nio.file.Path;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptResolver;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptTextIO;

/**
 * The one open/focus decision {@code EDIT} and {@code LIB EDIT} share (SPRINT
 * 0917-01, reshaped by SPRINT 1909S): the name is a Scripts Library path, resolved by
 * {@link ScriptResolver} like every other reference (no alias, basename or extension guessing), so it
 * either names a Script that exists (open/focus it), or nothing (offer to create it).
 */
public final class ScriptLibraryCommandSupport {

	private ScriptLibraryCommandSupport() {
	}

	/**
	 * @param requestedNameOrNull blank/{@code null} opens the workspace with nothing pre-opened;
	 *                            otherwise the name is resolved in the Scripts Library first.
	 */
	public static void openOrFocus(ScriptLibraryLauncher launcher, ScriptResolver resolver, ShellConsole console, String requestedNameOrNull,
			ScriptRunContext context) throws BroadSQLException {
		if (requestedNameOrNull == null || requestedNameOrNull.isBlank()) {
			launcher.openWorkspace(context);
			return;
		}
		String requested = requestedNameOrNull.trim();
		if (!resolver.libraryRootExists()) {
			// nothing can exist yet: the editor creates the (missing) Scripts Library folder with the first new Script
			launcher.openWorkspace(context);
			launcher.offerCreate(requested, context);
			return;
		}
		Path path = resolver.resolveInLibrary(requested);
		if (Files.isDirectory(path)) {
			throw new BroadSQLException("'" + requested + "' is a folder, not a script");
		}
		if (Files.isRegularFile(path)) {
			if (!ScriptTextIO.isTextFile(path)) {
				throw new BroadSQLException("'" + path + "' is not a text file and cannot be opened in the editor");
			}
			launcher.openAsset(requested, context); // the service resolves it again through ScriptResolver
		} else {
			launcher.openWorkspace(context);
			launcher.offerCreate(requested, context);
		}
	}
}
