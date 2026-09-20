package com.upandcoding.broadsql.controller.shell.commands.core.show;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;

/**
 * Reports the autocommit setting of the current connection: {@code SHOW AUTOCOMMIT}. Takes no
 * arguments. Displays a warning when autocommit is ON (updates cannot be rolled back), or a plain
 * message when it is OFF.
 */
public class CommandShowAutoCommit extends Command {

	public CommandShowAutoCommit() {
		super("AUTOCOMMIT", "SHOW AUTOCOMMIT", "SH AU", "SHAU");
	}

    @Override
    public void execute(String query) throws BroadSQLException {
        if (sqlDatabase != null) {
            if (sqlDatabase.isAutoCommit()) {
                console.println("Autocommit ON : all updates done without possibility of rollback", ShellConsole.MSG_WARN);
            } else {
                console.println("Autocommit OFF", ShellConsole.MSG_INFO);
            }
            console.println("");
        }
    }

    @Override
    public String getDescription() {
        return ("Displays autocommit setting for the current connection");
    }

	@Override
    public String getArguments() {
	    return "";
    }

	@Override
    public String getExamples() {
	    return "SHOW AUTOCOMMIT;";
    }
}
