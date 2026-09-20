package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

/**
 * Lists tables matching a pattern: {@code SHOW TABLES [[schema.]namePattern]}.
 *
 * <p>Both the schema and table parts (separated by a dot, schema optional) are always treated as a
 * "contains" search - each is internally wrapped in {@code %...%} - so {@code SHOW TABLES CUSTOMER;}
 * finds {@code CUSTOMER}, {@code CUSTOMERS} and {@code OLD_CUSTOMER} alike, with no need to type the
 * {@code %} yourself. Whether the match is case-sensitive depends on the connected database.
 *
 * <p>If the table part is omitted entirely, all tables are listed. If the schema part is omitted,
 * the search is restricted to the connection's <em>current</em> schema, not every schema.
 */
public class CommandShowTables extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandShowTables.class);

	public CommandShowTables() {
		super("SHOW TABLES", "SH TA", "SHTA");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String tableNameFilter = null;
		if (CommandUtils.isValidArgs(args)) {
			tableNameFilter = args[0].trim();
		}
		try {

			String schemaNamePattern = CommandUtils.getSchemaName(tableNameFilter);
			String tableNamePattern = CommandUtils.getTableName(tableNameFilter);
			String catalogName = null;

			TreeSet<TableMetadata> tables = sqlDatabase.getTables(catalogName, schemaNamePattern, tableNamePattern);
			shellConsolePrinter.printTableList(tables);

		} catch (Exception se) {
			throw new BroadSQLException(se);
		}

	}

	@Override
	public String getDescription() {
		return ("Display all tables which schema and name match the entered pattern");
	}

	@Override
	public String getArguments() {
		return "<tableName> (optional) a schema and name separated by a dot (.). Schema name is optional";
	}

	@Override
	public String getExamples() {
		return "SHOW TABLES;\n\tSHOW TABLES CUSTOMER;\n\tSHOW TABLES CUSTOM%;\n\tSHOW TABLES PUBLIC.CUSTOMER;\n\tSHOW TABLES PUB%.CUSTOMER;\n\tSHOW TABLES PUB%.%STOME%;";
	}
}
