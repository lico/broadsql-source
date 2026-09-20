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
 * Full-text, case-insensitive content search across the JS scripts catalog: {@code JS FIND <term>}.
 * Same shape as {@code SCRIPT FIND} (see its Javadoc), applied to the {@code JsScripts} catalog:
 * always searches the whole catalog regardless of instance or environment, and shows the matching
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

		FileCatalog catalog = new FileCatalog(consoleSettings.getJsScriptsPath(), BroadSQLErrorMessages.ERR_JSSCRIPTS_01);
		List<String> keys = new ArrayList<>(catalog.getList());
		Collections.sort(keys);

		List<CatalogEntryRow> rows = new ArrayList<>();
		List<String> matchingLines = new ArrayList<>();
		for (String key : keys) {
			String matchingLine = firstMatchingLine(catalog.getRawContent(key), term);
			if (matchingLine != null) {
				EntryMetadata metadata = catalog.getEntryMetadata(key);
				rows.add(new CatalogEntryRow(key, String.join(",", metadata.getAliases()), metadata.getDescription(),
						metadata.instanceDisplayValue(), metadata.environmentDisplayValue(), metadata.getTagsDisplayValue(), metadata.getStatus(),
						CatalogFormat.formatModified(catalog.getLastModifiedMillis(key)), null));
				matchingLines.add(matchingLine.trim());
			}
		}

		if (rows.isEmpty()) {
			console.println("No JS script matches '" + term + "'");
			return;
		}

		console.println("JS scripts catalog entries matching '" + term + "' (" + rows.size() + "):");
		shellConsolePrinter.printCatalogList(rows, false);
		console.println("Matching lines:");
		for (int i = 0; i < rows.size(); i++) {
			console.println("  " + rows.get(i).getFile() + ": " + matchingLines.get(i));
		}
		console.println("");
	}

	private static String firstMatchingLine(String content, String term) {
		if (content == null) {
			return null;
		}
		String upperTerm = term.toUpperCase();
		for (String line : content.split("\\r?\\n", -1)) {
			if (line.toUpperCase().contains(upperTerm)) {
				return line;
			}
		}
		return null;
	}

	@Override
	public String getDescription() {
		return ("Full-text, case-insensitive search across the whole JS scripts catalog (content, not just file names), regardless of instance or environment");
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
