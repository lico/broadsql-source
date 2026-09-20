package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.metadata.ColumnMetadata;

/**
 * Describes the structure of a table: {@code DESCR <tableName>}, listing each column's name, type,
 * size, nullability and default value.
 *
 * <p>{@code tableName} is mandatory and may be schema-qualified (e.g. {@code DESCR PUBLIC.CUSTOMER}).
 * When no schema is given, only the connection's current schema is searched, and only that schema's
 * columns are listed; a table with the same name in another schema is not shown. Qualify the name
 * (e.g. {@code DESCR OTHERSCHEMA.TOTO}) to describe a table in a different schema. It is looked up
 * case-insensitively: the name is tried as typed, then upper-cased, then lower-cased, and the first
 * variant that resolves to an existing table is used; if that variant isn't the one you typed, the
 * console reports which name was actually matched. There is no wildcard or partial-name support: the
 * (case-insensitive) table name must otherwise match exactly.
 *
 * <p>Fails with an error if no table name is given, or if none of the three case variants resolves
 * to an existing table.
 */
public class CommandDescr extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandDescr.class);

	public CommandDescr() {
		super("DESCR", "DESC", "DESCRIBE");
	}

	@Override
	public void execute(String qry) throws BroadSQLException {

		String[] args = parseArgs(qry);

		String schemaNamePattern = null;
		String tableNamePattern = null;
		if (CommandUtils.isValidArgs(args)) {
			tableNamePattern = args[0].trim();
		}

		if (StringUtils.isNotBlank(tableNamePattern)) {
			if (tableNamePattern.contains(".")) {
				schemaNamePattern = CommandUtils.getSchemaName(tableNamePattern);
				tableNamePattern = CommandUtils.getTableName(tableNamePattern);
				if (StringUtils.isBlank(schemaNamePattern)) {
					schemaNamePattern = null;
				}
			}

			boolean schemaWasExplicit = StringUtils.isNotBlank(schemaNamePattern);
			if (!schemaWasExplicit) {
				// No schema was given: resolve to the current schema explicitly, rather than letting a
				// blank pattern reach getColumns(), which the JDBC driver treats as "search every schema".
				schemaNamePattern = sqlDatabase.getCurrentSchema();
			}

			String schemaUpper = null;
			String schemaLower = null;
			if (StringUtils.isNotBlank(schemaNamePattern)) {
				schemaUpper = schemaNamePattern.toUpperCase();
				schemaLower = schemaNamePattern.toLowerCase();
			}
			String tableUpper = null;
			String tableLower = null;
			if (StringUtils.isNotBlank(tableNamePattern)) {
				tableUpper = tableNamePattern.toUpperCase();
				tableLower = tableNamePattern.toLowerCase();
			}

			String[] schemaPatterns = { schemaNamePattern, schemaUpper, schemaLower };
			String[] tablePatterns = { tableNamePattern, tableUpper, tableLower };

			boolean found = false;
			for (int i = 0; i < schemaPatterns.length; i++) {
				if (sqlDatabase.existsTable(schemaPatterns[i], tablePatterns[i])) {
					if (! StringUtils.equals(tableNamePattern, tablePatterns[i])) {
						console.writeln("Table '" + tableNamePattern + "' not found. Found table '" + tablePatterns[i] + "' instead.");
					}
					try {
						TreeSet<ColumnMetadata> columns = sqlDatabase.getColumns(null, schemaPatterns[i], tablePatterns[i], null);
						shellConsolePrinter.printColumns(columns);
					} catch (UnsupportedOperationException e) {
						console.error(new BroadSQLException(e));
						console.println("");
					}
					found = true;
					break;
				}
			}

			if (!found) {
				String fullName = tableNamePattern;
				if (schemaWasExplicit) {
					fullName = schemaNamePattern + "." + tableNamePattern;
				}
				console.error("Table name '" + fullName + " ' does not exist");
			}

			/*
			log.debug("schemaNamePattern: {}, tableNamePattern: {}", schemaNamePattern, tableNamePattern);
			if (sqlDatabase.existsTable(schemaNamePattern, tableNamePattern)) {
				try {
					TreeSet<ColumnMetadata> columns = sqlDatabase.getColumns(null, schemaNamePattern, tableNamePattern, null);
					shellConsolePrinter.printColumns(columns);
				} catch (UnsupportedOperationException uoe) {
					shellConsole.error(new BroadSQLException(uoe));
					shellConsole.println("");
				}
			} else {
				if (sqlDatabase.existsTable(schemaUpper, tableUpper.toUpperCase())) {
					try {
						TreeSet<ColumnMetadata> columns = sqlDatabase.getColumns(null, schemaUpper, tableUpper, null);
						shellConsolePrinter.printColumns(columns);
					} catch (UnsupportedOperationException uoe) {
						shellConsole.error(new BroadSQLException(uoe));
						shellConsole.println("");
					}
				} else {
				}
			}
			*/
		} else {
			console.error(BroadSQLErrorMessages.ERR_GAL_01);
		}
	}// execute

	@Override
	public String getDescription() {
		return ("Describe the structure of the table");
	}

	@Override
	public String getArguments() {
		return "<tableName> (mandatory) the name of a table";
	}

	@Override
	public String getExamples() {
		return "DESCR CUSTOMER;";
	}
}
