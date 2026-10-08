package com.upandcoding.broadsql.controller.shell.commands.core.set;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;

/**
 * Hidden diagnostic command that toggles whether console output is also written to the log file:
 * {@code SET TRACE}, no arguments.
 *
 * <p>Not listed in {@code HELP} output. Each call flips the current mode and reports the resulting
 * state.
 */
public class CommandSetTraceMode extends Command {

	public CommandSetTraceMode() {
		super("SET TRACE");
	}

    @Override
    public boolean isHidden() {
        return (true);
    }

    @Override
    public void execute(String query) throws BroadSQLException {
        if (this.printToLogFile) {
            console.print("Toggle trace mode OFF\n");
            this.printToLogFile = false;
            console.setPrintToLogFile(false);
        } else {
            this.printToLogFile = true;
            console.print("Toggle trace mode ON\n");
            console.setPrintToLogFile(true);
        }
    }

    @Override
    public String getDescription() {
        return ("Toggle output to log files ON or OFF");
    }

	@Override
    public String getArguments() {
	    return "";
    }

	@Override
    public String getExamples() {
	    return "SET TRACE;";
    }
}
