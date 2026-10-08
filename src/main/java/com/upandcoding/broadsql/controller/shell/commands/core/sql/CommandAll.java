package com.upandcoding.broadsql.controller.shell.commands.core.sql;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Displays every row of a table: {@code ALL <tableName>}.
 *
 * <p>{@code tableName} is mandatory and can be schema-qualified ({@code ALL PUBLIC.CUSTOMER}). A name
 * containing spaces or other special characters is written in double quotes ({@code ALL "Sales Data"}).
 * BroadSQL first finds the table the name designates, the same way as {@code DESCR}: the name as typed,
 * then in upper case, then in lower case. It then reads exactly that table, with its name quoted for the
 * connected database, so {@code ALL "Customer"} never reads a different table named {@code CUSTOMER}.
 * A name that no lookup finds is sent to the database as typed when it is a plain name (a synonym or an
 * object the database resolves by itself still works); otherwise the command reports that the table does
 * not exist. The command fails with a generic argument error if no table name is given.
 *
 * <p>Output follows the current display settings (screen, list mode, extraction) exactly like a
 * hand-typed {@code SELECT *} would. This command applies no row limit or pagination of its own.
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
				String qry;
				try {
					qry = "SELECT * FROM " + sqlDatabase.sqlTableReference(tableName);
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

	/** The first argument is a table of the current connection: TAB completes it like every other table position. */
	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.TABLE);
	}
}
