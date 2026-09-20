package com.upandcoding.broadsql.controller.shell.commands.core.scripts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.CatalogFormat;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;
import com.upandcoding.broadsql.controller.shell.output.CatalogEntryRow;

/**
 * Lists the entries in the scripts catalog: {@code SCRIPT LIST [<searchTerm>|ALL|ARCHIVES]}.
 *
 * <p>Same shape as {@code LIB LIST} (see its Javadoc and
 * {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}, sections 3, 4, 4a) applied to the {@code Scripts} root
 * instead of {@code SqlLib}: instance/environment-scoped by default, {@code ALL}/{@code ARCHIVES}
 * keywords, grid output, minus the Params column, which is a {@code LIB RUN}-specific ({@code %N})
 * concept that doesn't apply to scripts.
 *
 * <p>Only shows top-level entries by default: an entry stored in a subfolder of {@code Scripts} is
 * left out of the grid unless {@code ListSubfolders=true} is set in {@code BroadSQL.ini} (see
 * {@code ConsoleSettings#isListSubfolders}), independent of the instance/environment scoping above.
 * Subfolders are still resolved correctly by every other command ({@code RUN}/{@code SHOW}/
 * {@code EDIT}/{@code DEL}/{@code FIND}/...) regardless of this setting; it only affects what this
 * grid displays.
 */
public class CommandScriptList extends Command {

	private static final String KEYWORD_ALL = "ALL";
	private static final String KEYWORD_ARCHIVES = "ARCHIVES";

	public CommandScriptList() {
		super("SCRIPT LIST", "SC LI", "SCLI");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		String arg = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		FileCatalog catalog = new FileCatalog(consoleSettings.getScriptsPath(), BroadSQLErrorMessages.ERR_SCRIPTS_01);

		if (KEYWORD_ARCHIVES.equalsIgnoreCase(arg)) {
			listArchives(catalog);
			return;
		}

		List<String> keys = new ArrayList<>(catalog.getList());
		if (keys.isEmpty()) {
			console.println("The scripts catalog is empty");
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

		String banner = "Scripts catalog entries";
		if (searchPattern != null) {
			banner += " matching pattern '" + searchPattern + "'";
		}
		if (showAll) {
			banner += " (every instance/environment)";
		} else {
			banner += " for instance " + (StringUtils.isBlank(currentInstance) ? EntryMetadata.INSTANCE_NONE : currentInstance)
					+ " / environment " + (StringUtils.isBlank(currentEnvironment) ? EntryMetadata.ENVIRONMENT_NONE : currentEnvironment)
					+ " (" + rows.size() + " of " + matchingSearch + " - SCRIPT LIST ALL for every instance/environment)";
		}
		if (hiddenSubfolderEntries > 0) {
			banner += " (" + hiddenSubfolderEntries + " sub-folder " + (hiddenSubfolderEntries == 1 ? "entry" : "entries")
					+ " not shown - set ListSubfolders=true in the INI file to include them)";
		}
		console.println(banner + ":");
		shellConsolePrinter.printCatalogList(rows, false);
	}

	private void listArchives(FileCatalog catalog) {
		List<FileCatalog.ArchivedEntry> archived = catalog.getArchivedEntries();
		if (archived.isEmpty()) {
			console.println("The scripts archive is empty");
			return;
		}
		List<CatalogEntryRow> rows = new ArrayList<>();
		for (FileCatalog.ArchivedEntry entry : archived) {
			rows.add(new CatalogEntryRow(entry.getOriginalRelativePath(), null, null, null, null, null, null, CatalogFormat.formatArchiveTimestamp(entry.getTimestamp()), null));
		}
		console.println("Archived scripts catalog entries (" + rows.size() + "):");
		shellConsolePrinter.printCatalogList(rows, false);
	}

	private CatalogEntryRow toRow(FileCatalog catalog, String key, EntryMetadata metadata) {
		return new CatalogEntryRow(key, String.join(",", metadata.getAliases()), metadata.getDescription(),
				metadata.instanceDisplayValue(), metadata.environmentDisplayValue(), metadata.getTagsDisplayValue(), metadata.getStatus(),
				CatalogFormat.formatModified(catalog.getLastModifiedMillis(key)), null);
	}

	@Override
	public String getDescription() {
		return ("Display list of files in the scripts catalog, scoped to the current connection's instance and environment by default");
	}

	@Override
	public String getArguments() {
		return "<searchTerm> (optional) a search term, or the literal ALL (every instance/environment) / ARCHIVES (soft-deleted entries)";
	}

	@Override
	public String getExamples() {
		return "SCRIPT LIST DAILY;\n\tSCRIPT LIST ALL;\n\tSCRIPT LIST ARCHIVES;";
	}
}
