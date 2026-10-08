package com.upandcoding.broadsql.controller.shell.commands.core.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.CatalogFormat;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.output.CatalogEntryRow;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;

/**
 * Lists the Scripts in the Scripts Library: {@code LIB LIST [<searchTerm>|ALL|ARCHIVES]}.
 *
 * <p>Lists every Script in the library, including those in subfolders (the former
 * {@code ListSubfolders} setting no longer exists). With no argument, shows a grid of every Script whose
 * {@code @instance} and {@code @environment} metadata both match the current connection's Database
 * Group/environment, are tagged {@code ALL}, or carry no such tag at all (untagged Scripts are never
 * hidden on either dimension). {@code <searchTerm>} filters further by a case-insensitive substring of the
 * Script's relative path. The literal keyword {@code ALL} lifts both scoping dimensions; {@code ARCHIVES}
 * lists soft-deleted Scripts instead (see {@code LIB DEL}/{@code LIB UNDO}/{@code LIB RESTORE}).
 */
public class CommandLibList extends Command {

	private static final String KEYWORD_ALL = "ALL";
	private static final String KEYWORD_ARCHIVES = "ARCHIVES";

	public CommandLibList() {
		super("LIB LIST", "LI LI", "LILI");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		String arg = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		ScriptsLibrary library = LibraryScripts.open(consoleSettings);

		if (KEYWORD_ARCHIVES.equalsIgnoreCase(arg)) {
			listArchives(library);
			return;
		}

		List<String> keys = new ArrayList<>(library.getList());
		if (keys.isEmpty()) {
			console.println("The Scripts Library is empty");
			return;
		}
		Collections.sort(keys);

		boolean showAll = KEYWORD_ALL.equalsIgnoreCase(arg);
		String searchPattern = showAll ? null : arg;
		String currentInstance = CommandUtils.currentInstance(platform, getDatabaseConnectionsVault());
		String currentEnvironment = CommandUtils.currentEnvironment(platform, getDatabaseConnectionsVault());

		List<CatalogEntryRow> rows = new ArrayList<>();
		int matchingSearch = 0;
		for (String key : keys) {
			if (searchPattern != null && !key.toUpperCase().contains(searchPattern.toUpperCase())) {
				continue;
			}
			matchingSearch++;
			EntryMetadata metadata = library.getEntryMetadata(key);
			if (!showAll && (!metadata.appliesToInstance(currentInstance) || !metadata.appliesToEnvironment(currentEnvironment))) {
				continue;
			}
			rows.add(toRow(library, key, metadata));
		}

		String banner = "Scripts Library";
		if (searchPattern != null) {
			banner += " scripts matching pattern '" + searchPattern + "'";
		}
		if (showAll) {
			banner += " (every Database Group/environment)";
		} else {
			banner += " for Database Group " + (StringUtils.isBlank(currentInstance) ? EntryMetadata.INSTANCE_NONE : currentInstance)
					+ " / environment " + (StringUtils.isBlank(currentEnvironment) ? EntryMetadata.ENVIRONMENT_NONE : currentEnvironment)
					+ " (" + rows.size() + " of " + matchingSearch + " - LIB LIST ALL for every Database Group/environment)";
		}
		console.println(banner + ":");
		shellConsolePrinter.printCatalogList(rows);
	}

	private void listArchives(ScriptsLibrary library) {
		List<ScriptsLibrary.ArchivedEntry> archived = library.getArchivedEntries();
		if (archived.isEmpty()) {
			console.println("The Scripts Library archive is empty");
			return;
		}
		List<CatalogEntryRow> rows = new ArrayList<>();
		for (ScriptsLibrary.ArchivedEntry entry : archived) {
			rows.add(new CatalogEntryRow(entry.getOriginalRelativePath(), null, null, null, null, null, null, CatalogFormat.formatArchiveTimestamp(entry.getTimestamp()), null));
		}
		console.println("Archived Scripts (" + rows.size() + "):");
		shellConsolePrinter.printCatalogList(rows);
	}

	private CatalogEntryRow toRow(ScriptsLibrary library, String key, EntryMetadata metadata) {
		return new CatalogEntryRow(key, null, metadata.getDescription(),
				metadata.instanceDisplayValue(), metadata.environmentDisplayValue(), metadata.getTagsDisplayValue(), metadata.getStatus(),
				CatalogFormat.formatModified(library.getLastModifiedMillis(key)), null);
	}

	@Override
	public String getDescription() {
		return "Lists the Scripts in the Scripts Library (subfolders included), scoped to the current connection's Database Group and environment by default";
	}

	@Override
	public String getArguments() {
		return "<searchTerm> (optional) a search term, or the literal ALL (every Database Group/environment) / ARCHIVES (soft-deleted Scripts)";
	}

	@Override
	public String getExamples() {
		return "LIB LIST COUNTRY;\n\tLIB LIST ALL;\n\tLIB LIST ARCHIVES;";
	}
}
