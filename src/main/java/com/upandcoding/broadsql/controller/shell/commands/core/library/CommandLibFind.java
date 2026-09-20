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
 * Full-text, case-insensitive content search across the SQL library: {@code LIB FIND <term>}.
 *
 * <p>A strict superset of {@code LIB LIST <term>} (which only matches the file name): every entry
 * whose content contains {@code term} anywhere (including its metadata header, so description/tags
 * incidentally match too) is shown as a grid row plus the actual matching line underneath, so it's
 * possible to tell why an entry matched without opening it. Unlike {@code LIB LIST}, this always
 * searches the whole library regardless of instance or environment: it exists specifically to help
 * find the one entry you're thinking of. See {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}, section 5.
 */
public class CommandLibFind extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandLibFind.class);

	public CommandLibFind() {
		super("LIB FIND", "LI FI", "LIFI");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		String term = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		if (StringUtils.isBlank(consoleSettings.getLibraryPath())) {
			console.println("No SQL library folder specified in the INI file");
			return;
		}
		if (StringUtils.isBlank(term)) {
			console.println("You must provide a search term");
			return;
		}

		FileCatalog catalog = new FileCatalog(consoleSettings.getLibraryPath());
		List<String> keys = new ArrayList<>(catalog.getList());
		Collections.sort(keys);

		List<CatalogEntryRow> rows = new ArrayList<>();
		List<String> matchingLines = new ArrayList<>();
		for (String key : keys) {
			String matchingLine = firstMatchingLine(catalog.getRawContent(key), term);
			if (matchingLine != null) {
				rows.add(toRow(catalog, key, catalog.getEntryMetadata(key)));
				matchingLines.add(matchingLine.trim());
			}
		}

		if (rows.isEmpty()) {
			console.println("No library entry matches '" + term + "'");
			return;
		}

		console.println("Library entries matching '" + term + "' (" + rows.size() + "):");
		shellConsolePrinter.printCatalogList(rows, true);
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

	private CatalogEntryRow toRow(FileCatalog catalog, String key, EntryMetadata metadata) {
		Integer paramCount = null;
		try {
			paramCount = catalog.getParamNumbers(key).size();
		} catch (BroadSQLException e) {
			log.warn("Could not read params for library entry '{}'", key, e);
		}
		return new CatalogEntryRow(key, String.join(",", metadata.getAliases()), metadata.getDescription(),
				metadata.instanceDisplayValue(), metadata.environmentDisplayValue(), metadata.getTagsDisplayValue(), metadata.getStatus(),
				CatalogFormat.formatModified(catalog.getLastModifiedMillis(key)), paramCount);
	}

	@Override
	public String getDescription() {
		return ("Full-text, case-insensitive search across the whole SQL library (content, not just file names), regardless of instance or environment");
	}

	@Override
	public String getArguments() {
		return "<term> (mandatory) text to search for";
	}

	@Override
	public String getExamples() {
		return "LIB FIND revenue";
	}
}
