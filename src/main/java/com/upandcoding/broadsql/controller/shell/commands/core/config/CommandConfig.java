package com.upandcoding.broadsql.controller.shell.commands.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.swing.JSettingsFrame;
import com.sun.jna.Platform;

/**
 * Opens the connection-management GUI: {@code CONFIG}.
 *
 * <p>Windows only: fails with an error on any other operating system. Also fails if the current
 * connection is not {@code $CDF} (the encrypted Connections Definition File): connections can only
 * be managed while connected to it.
 *
 * <p>Takes no arguments; the GUI itself lets you view, add, edit and remove database connections.
 * A connection's "Login Scripts" tab manages the SQL statements run automatically whenever that
 * connection connects; the top-level "Database Groups" tab manages the CDF's Database Group list
 * used by every connection's Database Group field, and the "Environments" tab manages the CDF's
 * Environment list the same way.
 *
 * <p>The "Connections" tab has two distinct views, switched from the "Connections" menu: "View
 * active connections" (the default) and "View inactive connections". An inactive connection is
 * shown greyed out and cannot be created, edited, saved, duplicated or soft deleted from that view.
 * It can only be reactivated or hard deleted (permanently), each with a confirmation prompt. Trying
 * to save a new connection under an ID that already belongs to an inactive connection offers to
 * reactivate that connection instead of creating a duplicate ID, warning that doing so discards
 * whatever was just entered in the form.
 *
 * <p>A connection's Type dropdown lists every recognized database type, greying out and suffixing
 * with "(driver not found)" any whose JDBC driver class isn't found in {@code drivers/} or
 * {@code lib/}; such a type can still be picked, but a connection using it will fail to connect until
 * the matching driver jar is added.
 */
public class CommandConfig extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandConfig.class);

	public CommandConfig() {
		super("CONFIG");
	}

	@Override
	public void execute(String qry) throws BroadSQLException {

		if (Platform.isWindows()) {
			if ("$CDF".equalsIgnoreCase(this.getPlatform())) {
				JSettingsFrame app = new JSettingsFrame();
				app.setCmdLineConsole(console);
				app.setConsoleLogger(consoleLogger);
				app.setConsoleSettings(consoleSettings);
				app.setDatabaseConnectionsCollection(getDatabaseConnectionsVault());
				app.setConsoleUtils(shellConsolePrinter);
				app.setConnection(this.sqlDatabase);
				app.initApp();
				app.setVisible(true);
				
			} else {
				throw new BroadSQLException("Settings can only modified when connected to the CDF database");
			}
		} else {
			throw new BroadSQLException("This command is only available for Windows OS");
		}

	}

	@Override
	public String getDescription() {
		return ("Displays the GUI for maintaining connections (Windows only)");
	}

	@Override
	public String getArguments() {
		return "";
	}

	@Override
	public String getExamples() {
		return "CONFIG;";
	}
}
