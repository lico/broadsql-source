package com.upandcoding.broadsql.controller.shell.commands.core.show;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Displays the last SQL statement run in the session: {@code SHOW QUERY}. Takes no arguments.
 * Prints "No query in memory" if none has been executed yet, collapsed onto a single line
 * otherwise (see {@link CommandUtils#toSingleLine(String)}), convenient for reading and
 * copy-paste even if the query was originally typed or pasted across several lines. This is the
 * same statement replayed by the {@code /} macro; the {@code //} macro (handled in
 * {@code CommandInterpreter}) is a true alias - it looks up this same registered command instance
 * and calls this exact {@link #execute(String)}, rather than keeping its own copy of the display
 * logic.
 */
public class CommandShowQuery extends Command {

	public CommandShowQuery() {
		super("SHOW QUERY", "SH QU", "SHQU");
	}

    @Override
    public void execute(String query) throws BroadSQLException {
        if (StringUtils.isBlank(this.lastSQLQuery)) {
            console.println("No query in memory");
        } else {
            console.println(CommandUtils.toSingleLine(this.lastSQLQuery));
        }
    }

    @Override
    public String getDescription() {
        return ("Display the current SQL query stored in memory");
    }

	@Override
    public String getArguments() {
	    return "";
    }

	@Override
    public String getExamples() {
	    return "SHOW QUERY;";
    }
}
