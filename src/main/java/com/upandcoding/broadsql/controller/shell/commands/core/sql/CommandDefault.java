/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.upandcoding.broadsql.controller.shell.commands.core.sql;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.script.ScriptStatements;
import com.upandcoding.broadsql.controller.shell.scripts.PreparedSql;
import com.upandcoding.broadsql.controller.shell.scripts.SqlReferences;
import com.upandcoding.broadsql.dao.DatabaseConnection;

/**
 * Fallback command: handles whatever is typed that does not match any other command's keyword. This
 * is how BroadSQL sends ordinary SQL straight to the connected database - it has no keyword of its
 * own and never appears in {@code HELP}.
 *
 * <p>{@code SELECT}, {@code CALL}, {@code SCRIPT}, {@code SHOW}, {@code WITH} and {@code EXPLAIN}
 * statements are run as queries, honoring the current list mode, column separator, and
 * extraction/export settings. The literal statements {@code commit} and {@code rollback}
 * (case-insensitive) commit or roll back the current transaction directly. Anything else - insert,
 * update, delete, DDL, or any other statement the connected database accepts - is sent as an update
 * statement.
 *
 * <p>If the statement fails, the transaction is rolled back and the error is shown; BroadSQL itself
 * keeps running and returns to the prompt. Blank input is a no-op.
 */
public class CommandDefault extends Command {

	private final static Logger log = LoggerFactory.getLogger(CommandDefault.class);

	public CommandDefault() {
		super(ConsoleSettings.defaultCommandName);
	}

	@Override
	public boolean isHidden() {
		return (true);
	}

	public void execute(String query) throws BroadSQLException {
		if (extractMode) {
			sqlDatabase.setToScreen(false);
		} else {
			sqlDatabase.setToScreen(true);
		}
		
		/*
		log.debug("Extractmode: {}", extractMode);
		log.debug("Query: {}", query);
		log.debug("toScreen? {}", sqlDatabase.isToScreen());
		*/
		
		// SPRINT 0110A: ${name} references become typed binds (spec section 9.2); a malformed or undefined reference,
		// or raw ? refused, fails the statement before anything is sent to the database, without rollback.
		PreparedSql prepared = null;
		if (StringUtils.isNotBlank(query)) {
			final DatabaseConnection db = sqlDatabase;
			try {
				prepared = SqlReferences.prepare(query.trim(), ScriptStatements.variables(this), db::isPgJdbc);
			} catch (BroadSQLException referenceError) {
				// reported like any error of this command, which never throws (login scripts rely on it)
				console.error(referenceError, false);
				query = null;
			}
			if (query != null) {
				ScriptStatements.echoBoundValues(this, prepared);
			}
		}
		try {
			if (StringUtils.isNotBlank(query)) {
				query = query.trim();
				if (CommandUtils.isNotUpdateStatement(query)) {
					sqlDatabase.setListMode(listMode);
					sqlDatabase.setSep(this.getSeparator());
					if (prepared != null) {
						sqlDatabase.executeSelectQuery(prepared);
					} else {
						sqlDatabase.executeSelectQuery(query);
					}
				} else {
					if (query.toLowerCase().equalsIgnoreCase("commit")) {
						// commit
						sqlDatabase.commit();
					} else if (query.toLowerCase().equalsIgnoreCase("rollback")) {
						// rollback
						sqlDatabase.rollback();
					} else if (prepared != null) {
						sqlDatabase.executeUpdateQuery(prepared);
					} else {
						// Any other query
						sqlDatabase.executeUpdateQuery(query);
					}
				}
			}
		} catch (BroadSQLException se) {
			// SPRINT 0110A: the rollback is unchanged, and now reported when it discarded pending work (spec 15.3)
			ScriptStatements.reportSqlError(sqlDatabase, console, se);
		}
		// SPRINT 0110A: the blank separator line is routine output, hidden by OUTPUT QUIET
		console.routineln("");
		
		if (extractMode) {
			extractMode = false;
			sqlDatabase.setToScreen(true);
			sqlDatabase.setSep('\t');
		}
	}

	@Override
	public String getDescription() {
		return ("Send the command to the database");
	}

	@Override
    public String getArguments() {
	    return "";
    }

	@Override
    public String getExamples() {
	    return "";
    }
}
