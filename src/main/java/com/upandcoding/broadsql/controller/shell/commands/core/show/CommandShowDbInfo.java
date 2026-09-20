package com.upandcoding.broadsql.controller.shell.commands.core.show;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;

/**
 * Displays properties of the current connection: {@code SHOW DBINFOS}. Takes no arguments. Reports
 * the connection ID, the JDBC driver in use, and the database product name and version - for
 * Oracle, the version string already includes the product name, so it's shown alone.
 */
public class CommandShowDbInfo extends Command {

	public CommandShowDbInfo() {
		super("SHOW DBINFOS", "SH DBIN", "SHDBIN");
	}

    @Override
    public void execute(String query) throws BroadSQLException {
        if (sqlDatabase != null) {
        	console.println("");
			console.println("Connected to '" + platform + "' using JDBC driver '" + this.sqlDatabase.getDbDriver() + "'");
			if (sqlDatabase.getDbName().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_Oracle)) {
				console.println("Database: " + sqlDatabase.getDbVersion());
			} else {
				console.println("Database: " + sqlDatabase.getDbName() + " version " + this.sqlDatabase.getDbVersion());
			}
			console.println("");
        }
    }

    @Override
    public String getDescription() {
        return ("Displays current database properties");
    }

	@Override
    public String getArguments() {
	    return "";
    }

	@Override
    public String getExamples() {
	    return "SHOW DBINFOS;";
    }
}
