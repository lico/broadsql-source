package com.upandcoding.broadsql.controller.shell.commands.core.sql;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Counts the rows of a table: {@code CNT <tableName>}.
 *
 * <p>{@code tableName} is mandatory and can be schema-qualified ({@code CNT PUBLIC.CUSTOMER}). A name
 * containing spaces or other special characters is written in double quotes ({@code CNT "Sales Data"}).
 * The table is found exactly as {@code ALL} finds it (the name as typed, then in upper case, then in
 * lower case), and the count runs on that table, with its name quoted for the connected database. A name
 * that no lookup finds is sent to the database as typed when it is a plain name; otherwise the command
 * reports that the table does not exist. The command fails with a generic argument error if no table
 * name is given.
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
				String qry;
				try {
					qry = "SELECT COUNT(*) FROM " + sqlDatabase.sqlTableReference(tableName);
				} catch (BroadSQLException e) {
					console.error(e.getMessage());
					return;
				}

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

	/** The first argument is a table of the current connection: TAB completes it like every other table position. */
	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.TABLE);
	}
}
