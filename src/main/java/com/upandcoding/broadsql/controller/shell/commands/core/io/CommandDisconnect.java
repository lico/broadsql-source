package com.upandcoding.broadsql.controller.shell.commands.core.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ShellPromptBuilder;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;

/**
 * Closes the current connection: {@code DISCONNECT}, {@code BYE} or {@code CLOSE}. Takes no
 * arguments.
 *
 * <p>This does not leave BroadSQL disconnected: it tests connectivity to the Connections Definition
 * File ({@code $CDF}) and, if reachable, switches back to it exactly as {@code CONNECT $CDF} would
 * (closing whatever was open). If {@code $CDF} itself is not reachable, an error is reported and the
 * current connection is left untouched.
 *
 * <p>{@code DISCONNECT API;} (SPRINT XT02-7B) clears only the active API session context (see
 * {@link ApiSessionContextHolder}) and never touches the database connection above.
 */
public class CommandDisconnect extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandDisconnect.class);

	public CommandDisconnect() {
		super("DISCONNECT", "BYE", "CLOSE");
	}

    @Override
    public void execute(String query) throws BroadSQLException {
        String[] args = parseArgs(query);
        if (args != null && args.length >= 1 && "API".equalsIgnoreCase(args[0].trim())) {
            ApiSessionContextHolder.clear();
            console.setPrompt(ShellPromptBuilder.build(this.platform));
            return;
        }
        if (!getDatabaseConnectionsVault().contains(SpringPropertiesConfig.CDF_ID)) {
        	throw new BroadSQLException("Connection '" + SpringPropertiesConfig.CDF_ID + "' not defined");
        } else {
            StringBuffer result = this.sqlDatabase.testConnectionToExistingPlatform(SpringPropertiesConfig.CDF_ID);
            if (result == null || result.length() <= 0 || result.toString().trim().equals("")) {
                console.error("Connection to '" + SpringPropertiesConfig.CDF_ID + "' FAILED");
            } else {
                // Opening the CDF database : this will automatically close the current connection
                try {
                	String cmdQuery = new CommandConnect().getKeywords()[0] + " " + SpringPropertiesConfig.CDF_ID;
                	getConsoleCommandInterpreter().setQuery(cmdQuery);
					getConsoleCommandInterpreter().executeCommand();
					this.setPlatform(SpringPropertiesConfig.CDF_ID);
				} catch (BroadSQLException se) {
					console.error(se);
					console.println("");
				}
            }
        }
    }


    @Override
    public String getDescription() {
        return ("Closes the current connection, or the active API session context with DISCONNECT API");
    }

	@Override
	public String getDetailedDescription() {
		return "DISCONNECT (or BYE, or CLOSE) reconnects to the Connections Definition File, closing whatever "
				+ "database connection was open. DISCONNECT API clears only the active API session context "
				+ "established by CONNECT API, without affecting the database connection at all. If there is no "
				+ "active API context, DISCONNECT API is a no-op.";
	}

	@Override
    public String getArguments() {
	    return "none; or API to clear the active API session context";
    }

	@Override
    public String getExamples() {
	    return "DISCONNECT;\n\tBYE;\n\tDISCONNECT API;";
    }
}
