package com.upandcoding.broadsql.controller.shell.commands.core.sql;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Counts the rows of a table: {@code CNT <tableName>}.
 *
 * <p>{@code tableName} is mandatory and is used exactly as typed - it can be schema-qualified (e.g.
 * {@code CNT PUBLIC.CUSTOMER}) since it is simply passed through as
 * {@code SELECT COUNT(*) FROM tableName}. The command fails with a generic argument error if no
 * table name is given; a non-existent table surfaces as a plain database error rather than a
 * dedicated message.
 */
public class CommandCnt extends Command {

	public CommandCnt() {
		super("CNT");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String tableName = null;
		if (CommandUtils.isValidArgs(args)) {
			tableName = args[0].trim();

			if (StringUtils.isNotBlank(tableName)) {
				String qry = "SELECT COUNT(*) FROM " + tableName;

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
		return ("Displays result of a SELECT COUNT(*) for a table");
	}

	@Override
	public String getArguments() {
		return "<tableName> (mandatory) valid table name";
	}

	@Override
	public String getExamples() {
		return " CNT CUSTOMER;";
	}
}
