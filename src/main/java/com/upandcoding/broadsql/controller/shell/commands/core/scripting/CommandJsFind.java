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
import com.upandcoding.broadsql.controller.shell.output.CatalogEntryRow;

/**
 * Full-text, case-insensitive content search across the JS scripts catalog: {@code JS FIND <term>}.
 * Same shape as {@code LIB FIND}, applied to the {@code JsScripts} catalog:
 * always searches the whole catalog regardless of Database Group or environment, and shows the matching
 * line under each result row.
 */
public class CommandJsFind extends Command {

	public CommandJsFind() {
		super("JS FIND", "JS FI", "JSFI");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		String term = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		if (StringUtils.isBlank(term)) {
			console.println("You must provide a search term");
			return;
		}

		JsScriptCatalog catalog = new JsScriptCatalog(consoleSettings.getJsScriptsPath(), BroadSQLErrorMessages.ERR_JSSCRIPTS_01);
		List<String> keys = new ArrayList<>(catalog.getList());
		Collections.sort(keys);

		List<CatalogEntryRow> rows = new ArrayList<>();
		for (String key : keys) {
			if (contentMatches(catalog.getRawContent(key), term)) {
				EntryMetadata metadata = catalog.getEntryMetadata(key);
				rows.add(new CatalogEntryRow(key, String.join(",", metadata.getAliases()), metadata.getDescription(),
						metadata.instanceDisplayValue(), metadata.environmentDisplayValue(), metadata.getTagsDisplayValue(), metadata.getStatus(),
						CatalogFormat.formatModified(catalog.getLastModifiedMillis(key)), null));
			}
		}

		if (rows.isEmpty()) {
			console.println("No JS script matches '" + term + "'");
			return;
		}

		console.println("JS scripts catalog entries matching '" + term + "' (" + rows.size() + "):");
		shellConsolePrinter.printCatalogList(rows);
	}

	/**
	 * Whether any line of {@code content} contains {@code term}, case-insensitively. The match itself is
	 * deliberately as broad as before (metadata, description, tags and body); only the output changed:
	 * the matching lines are no longer printed under the grid.
	 */
	private static boolean contentMatches(String content, String term) {
		if (content == null) {
			return false;
		}
		String upperTerm = term.toUpperCase();
		for (String line : content.split("\\r?\\n", -1)) {
			if (line.toUpperCase().contains(upperTerm)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String getDescription() {
		return ("Full-text, case-insensitive search across the whole JS scripts catalog (content, not just file names), regardless of Database Group or environment");
	}

	@Override
	public String getArguments() {
		return "<term> (mandatory) text to search for";
	}

	@Override
	public String getExamples() {
		return "JS FIND migrate";
	}
}
