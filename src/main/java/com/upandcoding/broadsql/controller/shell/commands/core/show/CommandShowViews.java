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
 * Lists views matching a pattern: {@code SHOW VIEWS [[schema.]namePattern]} - same matching rules
 * as {@code SHOW TABLES}, restricted to database objects of type VIEW.
 *
 * <p>Both the schema and view-name parts (separated by a dot, schema optional) are always treated
 * as a "contains" search - each is internally wrapped in {@code %...%}. If the view-name part is
 * omitted, all views are listed; if the schema part is omitted, the search is restricted to the
 * connection's <em>current</em> schema, not every schema.
 */
public class CommandShowViews extends Command {

	private final static Logger log = LoggerFactory.getLogger(CommandShowViews.class.getName());

	public CommandShowViews() {
		super("SHOW VIEWS", "SH VI", "SHVI");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String viewNameFilter = null;
		if (CommandUtils.isValidArgs(args)) {
			viewNameFilter = args[0].trim();
		}
		try {

			String schemaNamePattern = CommandUtils.getSchemaName(viewNameFilter);
			String tableNamePattern = CommandUtils.getTableName(viewNameFilter);
			String catalogName = null;

			TreeSet<TableMetadata> tables = sqlDatabase.getViews(catalogName, schemaNamePattern, tableNamePattern);

			shellConsolePrinter.printTableList(tables);
		} catch (Exception se) {
			throw new BroadSQLException(se);
		}

	}

	@Override
	public String getDescription() {
		return ("Display all views which schema and name match the entered pattern");
	}

	@Override
	public String getArguments() {
		return "<viewName> (optional) a schema and name separated by a dot(.). Schema name is optional";
	}

	@Override
	public String getExamples() {
		return "SHOW VIEWS;\n\tSHOW VIEWS CUSTOMER;\n\tSHOW VIEWS CUSTOM%;\n\tSHOW VIEWS PUBLIC.CUSTOMER;\n\tSHOW VIEWS PUB%.CUSTOMER;\n\tSHOW VIEWS PUB%.%STOME%;";
	}
}
