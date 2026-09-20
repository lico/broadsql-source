package com.upandcoding.broadsql.controller.shell.commands.core.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.CatalogFormat;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;
import com.upandcoding.broadsql.controller.shell.output.CatalogEntryRow;

/**
 * Lists the queries saved in the SQL library: {@code LIB LIST [<searchTerm>|ALL|ARCHIVES]}.
 *
 * <p>With no argument, shows a grid of every entry whose {@code @instance} <b>and</b> {@code @environment}
 * metadata both match the current connection's instance/environment, are tagged {@code ALL}, or carry no
 * such tag at all (the {@code NONE} sentinel: untagged entries are never hidden on either dimension).
 * {@code <searchTerm>} filters further by a case-insensitive substring of the file's relative path, same
 * as before. The literal keyword {@code ALL} lifts both scoping dimensions, showing every entry
 * regardless of instance or environment; {@code ARCHIVES} lists soft-deleted entries instead (see
 * {@code LIB DEL}/{@code LIB UNDO}/{@code LIB RESTORE}).
 *
 * <p>Only shows top-level entries by default: an entry stored in a subfolder of {@code SqlLib} is
 * left out of the grid unless {@code ListSubfolders=true} is set in {@code BroadSQL.ini} (see
 * {@code ConsoleSettings#isListSubfolders}), independent of the instance/environment scoping above.
 * Subfolders are still resolved correctly by every other command ({@code RUN}/{@code SHOW}/
 * {@code EDIT}/{@code DEL}/{@code FIND}/...) regardless of this setting; it only affects what this
 * grid displays.
 *
 * <p>See {@code docs/SQL_LIBRARY_AND_SCRIPTS.md} (sections 3, 4, 4a) for the full design.
 */
public class CommandLibList extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandLibList.class);

	private static final String KEYWORD_ALL = "ALL";
	private static final String KEYWORD_ARCHIVES = "ARCHIVES";

	public CommandLibList() {
		super("LIB LIST", "LI LI", "LILI");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		String arg = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		if (StringUtils.isBlank(consoleSettings.getLibraryPath())) {
			console.println("No SQL library folder specified in the INI file");
			return;
		}

		FileCatalog catalog = new FileCatalog(consoleSettings.getLibraryPath());

		if (KEYWORD_ARCHIVES.equalsIgnoreCase(arg)) {
			listArchives(catalog);
			return;
		}

		List<String> keys = new ArrayList<>(catalog.getList());
		if (keys.isEmpty()) {
			console.println("The SQL library is empty");
			return;
		}
		int hiddenSubfolderEntries = 0;
		if (!consoleSettings.isListSubfolders()) {
			List<String> topLevelOnly = new ArrayList<>();
			for (String key : keys) {
				if (key.indexOf('/') >= 0) {
					hiddenSubfolderEntries++;
				} else {
					topLevelOnly.add(key);
				}
			}
			keys = topLevelOnly;
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
			EntryMetadata metadata = catalog.getEntryMetadata(key);
			if (!showAll && (!metadata.appliesToInstance(currentInstance) || !metadata.appliesToEnvironment(currentEnvironment))) {
				continue;
			}
			rows.add(toRow(catalog, key, metadata));
		}

		String banner = "Library entries";
		if (searchPattern != null) {
			banner += " matching pattern '" + searchPattern + "'";
		}
		if (showAll) {
			banner += " (every instance/environment)";
		} else {
			banner += " for instance " + (StringUtils.isBlank(currentInstance) ? EntryMetadata.INSTANCE_NONE : currentInstance)
					+ " / environment " + (StringUtils.isBlank(currentEnvironment) ? EntryMetadata.ENVIRONMENT_NONE : currentEnvironment)
					+ " (" + rows.size() + " of " + matchingSearch + " - LIB LIST ALL for every instance/environment)";
		}
		if (hiddenSubfolderEntries > 0) {
			banner += " (" + hiddenSubfolderEntries + " sub-folder " + (hiddenSubfolderEntries == 1 ? "entry" : "entries")
					+ " not shown - set ListSubfolders=true in the INI file to include them)";
		}
		console.println(banner + ":");
		shellConsolePrinter.printCatalogList(rows, true);
	}

	private void listArchives(FileCatalog catalog) {
		List<FileCatalog.ArchivedEntry> archived = catalog.getArchivedEntries();
		if (archived.isEmpty()) {
			console.println("The library archive is empty");
			return;
		}
		List<CatalogEntryRow> rows = new ArrayList<>();
		for (FileCatalog.ArchivedEntry entry : archived) {
			rows.add(new CatalogEntryRow(entry.getOriginalRelativePath(), null, null, null, null, null, null, CatalogFormat.formatArchiveTimestamp(entry.getTimestamp()), null));
		}
		console.println("Archived library entries (" + rows.size() + "):");
		shellConsolePrinter.printCatalogList(rows, false);
	}

	private CatalogEntryRow toRow(FileCatalog catalog, String key, EntryMetadata metadata) {
		Integer paramCount = null;
		try {
			paramCount = catalog.getParamNumbers(key).size();
		} catch (BroadSQLException e) {
			// Content unreadable for some reason: leave the Params column blank rather than fail the
			// whole listing over one entry.
			log.warn("Could not read params for library entry '{}'", key, e);
		}
		return new CatalogEntryRow(key, String.join(",", metadata.getAliases()), metadata.getDescription(),
				metadata.instanceDisplayValue(), metadata.environmentDisplayValue(), metadata.getTagsDisplayValue(), metadata.getStatus(),
				CatalogFormat.formatModified(catalog.getLastModifiedMillis(key)), paramCount);
	}

	@Override
	public String getDescription() {
		return ("Display list of files in the SQL library, scoped to the current connection's instance and environment by default");
	}

	@Override
	public String getArguments() {
		return "<searchTerm> (optional) a search term, or the literal ALL (every instance/environment) / ARCHIVES (soft-deleted entries)";
	}

	@Override
	public String getExamples() {
		return "LIB LIST COUNTRY;\n\tLIB LIST ALL;\n\tLIB LIST ARCHIVES;";
	}
}
