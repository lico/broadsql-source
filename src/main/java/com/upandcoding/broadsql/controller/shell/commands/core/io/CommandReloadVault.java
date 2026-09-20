package com.upandcoding.broadsql.controller.shell.commands.core.io;

import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;

/**
 * Reloads database definitions from the Connections Definition File: {@code RELOAD VAULT}. Hidden
 * from {@code HELP} - not intended for routine use. Takes no arguments.
 *
 * <p>Re-reads the CDF's connection definitions into memory without restarting BroadSQL or
 * reopening the current connection. Useful after the CDF's underlying data was changed directly
 * (e.g. via SQL against the {@code CONNECTIONS} table) rather than through {@code CONFIG} or
 * {@code ADD}/{@code EDIT CONNECTION}.
 */
public class CommandReloadVault extends Command {
	
	private static final Logger log = LoggerFactory.getLogger(CommandReloadVault.class);

    static final HashMap<String, Character> names = new HashMap<String, Character>();

    public CommandReloadVault() {
        super("RELOAD VAULT", "RE VA", "REVA");
    }

    @Override
    public boolean isHidden() {
        return (true);
    }

    @Override
    public void execute(String query) throws BroadSQLException {
        getDatabaseConnectionsVault().load();
        console.println("... connections refreshed from CDF file");
    }

    @Override
    public String getDescription() {
        return ("Reload database definitions from the Connections Definition File");
    }

	@Override
    public String getArguments() {
	    return "";
    }

	@Override
    public String getExamples() {
	    return "RELOAD VAULT;";
    }
}
