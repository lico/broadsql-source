package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.metadata.PrimaryKeyMetadata;

/**
 * Lists the primary key columns of a table: {@code SHOW PK <tableName>}.
 *
 * <p>{@code tableName} is mandatory and may be schema-qualified (e.g. {@code SHOW PK
 * PUBLIC.CUSTOMER}). Unlike {@code DESCR}, the name is used exactly as typed - it is not retried in
 * upper or lower case - so it must match the table's actual name. No wildcards are supported. When
 * no schema is given, only the connection's current schema is searched, as with {@code DESCR}.
 *
 * <p>Fails with an error if no table name is given, or if the table doesn't exist.
 */
public class CommandShowPrimaryKeys extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandShowPrimaryKeys.class);

	public CommandShowPrimaryKeys() {
		super("SHOW PK", "SHOW PRIMARY KEYS", "SH PK", "SHPK");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String tableNamePattern = null;
		String schemaNamePattern = "";
		if (CommandUtils.isValidArgs(args)) {
			tableNamePattern = args[0].trim();
			if (StringUtils.isNotBlank(tableNamePattern)) {

				if (tableNamePattern.contains(".")) {
					schemaNamePattern = StringUtils.substringBeforeLast(tableNamePattern, ".");
					if (StringUtils.isBlank(schemaNamePattern)) {
						schemaNamePattern = null;
					}
					tableNamePattern = StringUtils.substringAfterLast(tableNamePattern, ".");
				}

				if (StringUtils.isNotBlank(tableNamePattern)) {
					if (sqlDatabase.existsTable(schemaNamePattern, tableNamePattern)) {

						try {
							TreeSet<PrimaryKeyMetadata> primaryKeys = sqlDatabase.getPrimaryKeys(null, schemaNamePattern, tableNamePattern, null);
							shellConsolePrinter.printPrimaryKeys(primaryKeys);

						} catch (UnsupportedOperationException uoe) {
							console.error(new BroadSQLException(uoe));
							console.println("");
						}
					} else {
						String fullName = tableNamePattern;
						if (StringUtils.isNotBlank(schemaNamePattern)) {
							fullName = schemaNamePattern + "." + tableNamePattern;
						}
						console.error("Table name '" + fullName + " ' does not exist");
					}
				} else {
					console.error(BroadSQLErrorMessages.ERR_GAL_01);
				}
			} else {
				console.error(BroadSQLErrorMessages.ERR_GAL_01);
			}
		} else {
			console.error(BroadSQLErrorMessages.ERR_GAL_01);
		}
	}

	@Override
	public String getDescription() {
		return ("Shows the primary keys for a given table name");
	}

	@Override
	public String getArguments() {
		return "valid table name (mandatory)";
	}

	@Override
	public String getExamples() {
		return "SHOW KEYS CUSTOMER;";
	}
}
