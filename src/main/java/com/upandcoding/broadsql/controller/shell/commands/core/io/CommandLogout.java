package com.upandcoding.broadsql.controller.shell.commands.core.io;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;

/**
 * Prompts for re-authentication: {@code LOGOUT}. Takes no arguments.
 *
 * <p>Re-runs the same master-password prompt shown at startup, without closing the current database
 * connection. Useful for locking the session without exiting BroadSQL.
 */
public class CommandLogout extends Command {

	public CommandLogout() {
		super("LOGOUT");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		try {
			this.getSession().authenticate(console);
		} catch (Exception ce) {
			ce.printStackTrace();
		}
	} 

	@Override
	public String getDescription() {
		return ("Prompts user for authentication");
	}

	@Override
	public String getArguments() {
		return "";
	}

	@Override
	public String getExamples() {
		return "LOGOUT;";
	}
}
