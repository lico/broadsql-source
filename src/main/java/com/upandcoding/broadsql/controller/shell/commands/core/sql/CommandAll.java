package com.upandcoding.broadsql.controller.shell.commands.core.sql;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Displays every row of a table: {@code ALL <tableName>}.
 *
 * <p>{@code tableName} is mandatory and is used exactly as typed - it can be schema-qualified (e.g.
 * {@code ALL PUBLIC.CUSTOMER}) since it is simply passed through as {@code SELECT * FROM tableName}.
 * The command fails with a generic argument error if no table name is given; a non-existent table
 * surfaces as a plain database error rather than a dedicated message.
 *
 * <p>Output follows the current display settings (screen, list mode, extraction) exactly like a
 * hand-typed {@code SELECT *} would - there is no row limit or pagination applied by this command
 * itself.
 */
public class CommandAll extends Command {

	public CommandAll() {
		super("ALL");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String tableName = null;
		if (CommandUtils.isValidArgs(args)) {
			tableName = args[0].trim();

			if (StringUtils.isNotBlank(tableName)) {
				String qry = "SELECT * FROM " + tableName;

				try {

					try {
						sqlDatabase.executeSelectQuery(qry);
					} catch (BroadSQLException se) {
						console.error(se);
					}
				} catch (UnsupportedOperationException uoe) {
					console.error(new BroadSQLException(uoe));
				}
				console.println("");
			} else {
				console.error(BroadSQLErrorMessages.ERR_GAL_01);
			}
		} else {
			console.error(BroadSQLErrorMessages.ERR_GAL_01);
		}
	}

	@Override
	public String getDescription() {
		return ("Displays all rows from a table");
	}

	@Override
	public String getArguments() {
		return "<tableName> (mandatory) valid table name";
	}

	@Override
	public String getExamples() {
		return " ALL CUSTOMER;";
	}
}
