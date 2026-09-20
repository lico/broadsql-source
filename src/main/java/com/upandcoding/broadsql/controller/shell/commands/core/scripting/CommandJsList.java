package com.upandcoding.broadsql.controller.shell.commands.core.scripting;

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
 * Lists the entries in the JS scripts catalog: {@code JS LIST [<searchTerm>|ALL]}.
 *
 * <p>Same shape as {@code SCRIPT LIST} (see its Javadoc and {@code docs/SQL_LIBRARY_AND_SCRIPTS.md},
 * sections 3, 4, 4a) applied to the {@code JsScripts} root instead of {@code Scripts}:
 * instance/environment-scoped by default, the {@code ALL} keyword lifts both dimensions, grid output.
 */
public class CommandJsList extends Command {

	private static final String KEYWORD_ALL = "ALL";

	public CommandJsList() {
		super("JS LIST", "JS LI", "JSLI");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		String arg = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		FileCatalog catalog = new FileCatalog(consoleSettings.getJsScriptsPath(), BroadSQLErrorMessages.ERR_JSSCRIPTS_01);

		List<String> keys = new ArrayList<>(catalog.getList());
		if (keys.isEmpty()) {
			console.println("The JS scripts catalog is empty");
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
			EntryMetadata metadata = catalog.getEntryMetadata(key);
			if (!showAll && (!metadata.appliesToInstance(currentInstance) || !metadata.appliesToEnvironment(currentEnvironment))) {
				continue;
			}
			rows.add(toRow(catalog, key, metadata));
		}

		String banner = "JS scripts catalog entries";
		if (searchPattern != null) {
			banner += " matching pattern '" + searchPattern + "'";
		}
		if (showAll) {
			banner += " (every instance/environment)";
		} else {
			banner += " for instance " + (StringUtils.isBlank(currentInstance) ? EntryMetadata.INSTANCE_NONE : currentInstance)
					+ " / environment " + (StringUtils.isBlank(currentEnvironment) ? EntryMetadata.ENVIRONMENT_NONE : currentEnvironment)
					+ " (" + rows.size() + " of " + matchingSearch + " - JS LIST ALL for every instance/environment)";
		}
		console.println(banner + ":");
		shellConsolePrinter.printCatalogList(rows, false);
	}

	private CatalogEntryRow toRow(FileCatalog catalog, String key, EntryMetadata metadata) {
		return new CatalogEntryRow(key, String.join(",", metadata.getAliases()), metadata.getDescription(),
				metadata.instanceDisplayValue(), metadata.environmentDisplayValue(), metadata.getTagsDisplayValue(), metadata.getStatus(),
				CatalogFormat.formatModified(catalog.getLastModifiedMillis(key)), null);
	}

	@Override
	public String getDescription() {
		return ("Display list of files in the JS scripts catalog, scoped to the current connection's instance and environment by default");
	}

	@Override
	public String getArguments() {
		return "<searchTerm> (optional) a search term, or the literal ALL (every instance/environment)";
	}

	@Override
	public String getExamples() {
		return "JS LIST;\n\tJS LIST migrate;\n\tJS LIST ALL;";
	}
}
