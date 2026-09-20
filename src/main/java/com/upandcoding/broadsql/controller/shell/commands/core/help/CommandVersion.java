package com.upandcoding.broadsql.controller.shell.commands.core.help;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.output.ConsoleUtils;

/**
 * Displays the current BroadSQL version: {@code VERSION} (or {@code VERS}).
 */
public class CommandVersion extends Command {

	public CommandVersion() {
		super("VERSION", "VERS");
	}

    @Override
    public void execute(String query) throws BroadSQLException {
        console.writeln(ConsoleUtils.getTitleAndVersion());
        console.println("");
    }

    @Override
    public String getDescription() {
        return ("Display current version of " + SpringPropertiesConfig.APP_TITLE);
    }

	@Override
    public String getArguments() {
	    return "";
    }

	@Override
    public String getExamples() {
	    return "VERSION;";
    }
}
