package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.metadata.MetadataException;
import com.upandcoding.broadsql.dao.model.metadata.IndexMetadata;

/**
 * Finds indexes by index name, table or column: {@code FIND INDEX <text>}.
 *
 * <p>Lists every index of the current schema whose name, table or one of whose columns contains the text.
 * Matching ignores case, and, as with {@code FIND COLUMN}, {@code %} matches any sequence of characters and
 * {@code _} any single character. One row per indexed column, with its position ({@code #}) and
 * {@code UNIQUE} or {@code NON-UNIQUE}; every column of a matching composite index is listed, even when only
 * one of them matched. Table statistics are never shown as indexes.
 *
 * <p>Uses the same metadata as {@code SHOW INDEXES}, read for each table of the current schema, so a large
 * schema takes a moment. Fails with an error if no text is given or if the JDBC driver cannot provide index
 * metadata.
 */
public class CommandFindIndexes extends Command {

	public CommandFindIndexes() {
		super("FIND INDEX", "FIND INDEXES", "FI IX", "FIIX");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		String text = CommandUtils.isValidArgs(args) ? args[0].trim() : null;
		if (StringUtils.isBlank(text)) {
			console.error("You must provide a text to search for");
			return;
		}
		if (sqlDatabase == null) {
			console.error("Not connected to a database");
			return;
		}
		try {
			List<IndexMetadata> indexes = sqlDatabase.getMetadataService().findIndexes(text);
			if (indexes.isEmpty()) {
				console.info("No index matches '" + text + "' in the current schema.");
				return;
			}
			List<List<String>> rows = new ArrayList<>();
			for (IndexMetadata index : indexes) {
				rows.add(Arrays.asList(index.getIndexName(), index.getTable(), String.valueOf(index.getOrdinalPosition()), index.getColumn(),
						CommandShowIndexes.uniqueness(index)));
			}
			shellConsolePrinter.printTable(List.of("INDEX", "TABLE", "#", "COLUMN", "UNIQUE"), rows);
		} catch (MetadataException e) {
			console.error(e.getMessage());
		}
	}

	@Override
	public String getDescription() {
		return "Finds indexes whose name, table or columns contain the input text";
	}

	@Override
	public String getArguments() {
		return "<text> (mandatory) part of an index, table or column name";
	}

	@Override
	public String getExamples() {
		return "FIND INDEX ORDERS;";
	}
}
