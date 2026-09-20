package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.TreeSet;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.metadata.ColumnMetadata;

/**
 * Finds columns by name across the whole database: {@code FIND COLUMN <columnName>}.
 *
 * <p>{@code columnName} is mandatory and is matched as a substring (the search text is used exactly
 * as typed, not case-normalized here - matching follows the database driver's own pattern-matching
 * rules). The search is not restricted to a schema or table: every column in the database whose name
 * contains the given text is listed, with its table.
 *
 * <p>Fails with an error if no search text is given.
 *
 * <p><b>{@code SHOW COLUMN}/{@code SH COL}/{@code SHCOL} still work</b>: {@code FIND COLUMN} is the
 * same command under a new, recommended name - the first of a planned {@code FIND} family (locating an
 * entity by a fact belonging to a different category, e.g. a table by one of its column names) that
 * reads better as {@code FIND} than {@code SHOW}. No behavior change, and the older keywords are not
 * going away - same precedent as {@code PULL} superseding {@code EXPORT}/{@code DUMP} without removing
 * them. New scripts should prefer {@code FIND COLUMN}.
 */
public class CommandShowColumn extends Command {

	public CommandShowColumn() {
		super("FIND COLUMN", "FI COL", "FICOL", "SHOW COLUMN", "SH COL", "SHCOL");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (CommandUtils.isValidArgs(args)) {
			String columnNamePattern = "%" + args[0].trim() + "%";

			String schemaPattern = null;
			String tableNamePattern = null;

			try {
				TreeSet<ColumnMetadata> columns = sqlDatabase.getColumns(null, schemaPattern, tableNamePattern, columnNamePattern);
				shellConsolePrinter.printColumns(columns);
			} catch (Exception se) {
				throw new BroadSQLException(se);
			}
		} else {
			console.error("Not enough arguments for command");
		}

	}

	@Override
	public String getDescription() {
		return ("Show tables having a column name that contain the input text");
	}

	@Override
	public String getArguments() {
		return "<columnName> (mandatory) the name of the column. Part of the name can be used.";
	}

	@Override
	public String getExamples() {
		return "FIND COLUMN COUNTRY_ID;";
	}
}
