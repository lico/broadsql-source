package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.upandcoding.broadsql.dao.metadata.MetadataException;
import com.upandcoding.broadsql.dao.metadata.MetadataService;
import com.upandcoding.broadsql.dao.model.metadata.IndexMetadata;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

/**
 * Lists the indexes of a table: {@code SHOW INDEXES <table>}.
 *
 * <p>One row per column of each index: the index name, the column position within the index
 * ({@code #}), the column, and {@code UNIQUE} or {@code NON-UNIQUE}. The rows of a composite index follow
 * each other in index order. Indexes created for primary keys and unique constraints are included, since
 * they are real indexes. Table statistics, which some JDBC drivers return together with indexes, are not
 * shown. For an index on an expression, the column shows the expression text when the driver reports it.
 *
 * <p>Table names are resolved as for {@code SHOW FK}. Reports that the table has no indexes when it
 * has none. Fails with an error if no table name is given, if the table does not exist or is ambiguous, or
 * if the JDBC driver cannot provide index metadata.
 */
public class CommandShowIndexes extends TableMetadataCommand {

	public CommandShowIndexes() {
		super("SHOW INDEXES", "SHOW INDEX", "SH IX", "SHIX");
	}

	@Override
	protected void show(MetadataService metadata, TableMetadata table) throws MetadataException {
		List<IndexMetadata> indexes = metadata.indexes(table);
		if (indexes.isEmpty()) {
			console.info("Table " + displayName(table) + " has no indexes.");
			return;
		}
		List<List<String>> rows = new ArrayList<>();
		for (IndexMetadata index : indexes) {
			rows.add(Arrays.asList(index.getIndexName(), String.valueOf(index.getOrdinalPosition()), index.getColumn(), uniqueness(index)));
		}
		shellConsolePrinter.printTable(List.of("INDEX", "#", "COLUMN", "UNIQUE"), rows);
	}

	static String uniqueness(IndexMetadata index) {
		return index.isUnique() ? "UNIQUE" : "NON-UNIQUE";
	}

	@Override
	public String getDescription() {
		return "Shows the indexes of a table, their columns and whether they are unique";
	}

	@Override
	public String getArguments() {
		return "<table> (mandatory) a table name, optionally qualified with its schema";
	}

	@Override
	public String getExamples() {
		return "SHOW INDEXES ORDERS;";
	}
}
