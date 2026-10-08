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
 * There are three top level tabs: "Connections", "Database Groups" (manages the CDF's Database Group
 * list used by every connection's Database Group field) and "Environments" (manages the CDF's
 * Environment list the same way). A connection's own "Login Scripts" tab manages the SQL statements
 * run automatically whenever that connection connects. A shared menu bar (File: Exit; Edit: New,
 * Save, Duplicate, Test, Deactivate, Reactivate, Delete permanently; View: Active, Inactive, All)
 * drives whichever tab is currently selected - New/Duplicate/Test/etc. create or act on a connection,
 * Database Group or Environment depending on which tab is showing. The same actions are also
 * available as buttons directly below each tab's own form.
 *
 * <p>All three tabs share one lifecycle model with a "Show:" switch for Active, Inactive and All.
 * An inactive record (connection, Database Group or Environment) is shown greyed out and cannot be
 * edited or saved, only Reactivated or permanently deleted ("Delete permanently"), each requiring
 * confirmation. Deactivating a record never runs the same validation as saving one, so an obsolete
 * connection whose Database Group/Environment pairing has since become invalid can still be
 * deactivated without first being edited into a valid state. Permanently deleting a Database Group
 * or Environment is only possible once it is inactive and no connection, active or inactive, still
 * references it; the error names exactly which connections are blocking it. The Connections tab is a
 * sortable table (Connection, Database Group, Environment, Status) supporting multiple selection for
 * bulk Deactivate/Reactivate/Delete permanently, plus a text search box filtering by connection,
 * Database Group or Environment. Trying to save a new connection under an ID that already belongs to
 * an inactive connection offers to reactivate that connection instead of creating a duplicate ID,
 * warning that doing so discards whatever was just entered in the form.
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
