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
 * Full-text, case-insensitive content search across the Scripts Library: {@code LIB FIND <term>}.
 *
 * <p>A strict superset of {@code LIB LIST <term>} (which only matches the path): every Script whose
 * content contains {@code term} anywhere (including its metadata header, so description/tags
 * incidentally match too) is shown as a grid row. Unlike {@code LIB LIST}, this always searches the whole
 * library regardless of Database Group or environment. This is a search: it finds Scripts, it does not
 * resolve a name, so it is the one place a partial term is meaningful.
 */
public class CommandLibFind extends Command {

	public CommandLibFind() {
		super("LIB FIND", "LI FI", "LIFI");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		String term = CommandUtils.isValidArgs(args) ? args[0].trim() : null;
		if (StringUtils.isBlank(term)) {
			console.println("You must provide a search term");
			return;
		}

		ScriptsLibrary library = LibraryScripts.open(consoleSettings);
		List<String> keys = new ArrayList<>(library.getList());
		Collections.sort(keys);

		List<CatalogEntryRow> rows = new ArrayList<>();
		for (String key : keys) {
			if (contentMatches(library.getRawContent(key), term)) {
				rows.add(toRow(library, key, library.getEntryMetadata(key)));
			}
		}
		if (rows.isEmpty()) {
			console.println("No script in the Scripts Library matches '" + term + "'");
			return;
		}
		console.println("Scripts matching '" + term + "' (" + rows.size() + "):");
		shellConsolePrinter.printCatalogList(rows);
	}

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

	private CatalogEntryRow toRow(ScriptsLibrary library, String key, EntryMetadata metadata) {
		return new CatalogEntryRow(key, null, metadata.getDescription(),
				metadata.instanceDisplayValue(), metadata.environmentDisplayValue(), metadata.getTagsDisplayValue(), metadata.getStatus(),
				CatalogFormat.formatModified(library.getLastModifiedMillis(key)), null);
	}

	@Override
	public String getDescription() {
		return "Full-text, case-insensitive search across the whole Scripts Library (content, not just paths), regardless of Database Group or environment";
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
