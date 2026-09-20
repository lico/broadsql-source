package com.upandcoding.broadsql.controller.shell.commands.core.io;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowAutoCommit;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowDbInfo;

/**
 * Opens a connection to a database: {@code CONNECT <id>}.
 *
 * <p>{@code id} is mandatory and must be a connection defined in the Connections Definition File
 * (CDF) - the command fails otherwise. The connection is tested first; if the test fails, the
 * command reports the failure and the current connection is left untouched.
 *
 * <p>On success, any currently open connection is closed automatically before the new one is
 * opened, and the prompt changes to reflect the new connection. Unless connecting to the CDF itself,
 * the session is added to the query log file if logging is enabled by default. {@code SHOW DBINFO}
 * and {@code SHOW AUTOCOMMIT} are run automatically afterwards to summarize the new connection.
 *
 * <p>If the connection's database type has no JDBC driver class available on the classpath, the test
 * fails with a clear error naming the missing driver class rather than a raw exception. Copy the
 * matching driver {@code .jar} file into the {@code drivers/} or {@code lib/} folder and restart
 * BroadSQL, then try again.
 */
public class CommandConnect extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandConnect.class);

	public CommandConnect() {
		super("CONNECT", "OPEN", "CONN", "CON");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String newPlatform = null;
		if (CommandUtils.isValidArgs(args)) {
			newPlatform = args[0].trim();
			if (StringUtils.isNotBlank(newPlatform)) {
				if (!getDatabaseConnectionsVault().contains(newPlatform)) {
					if (getDatabaseConnectionsVault().isInactiveConnection(newPlatform)) {
						throw new BroadSQLException(CommandUtils.inactiveConnectionMessage(newPlatform));
					}
					throw new BroadSQLException("Connection '" + newPlatform + "' not defined");
				} else {
					StringBuffer result = this.sqlDatabase.testConnectionToExistingPlatform(newPlatform);
					if (result == null || result.length() <= 0 || result.toString().trim().equals("")) {
						console.error("Connection FAILED");
					} else {
						// Closing current database before
						if (sqlDatabase.isConnected()) {
							sqlDatabase.close();
						}

						// Opening the new database
						this.setPlatform(newPlatform);
						this.getSession().openDatabase(newPlatform);
						sqlDatabase.setToScreen(true);
						sqlDatabase.setMaxRowsOnScreen(consoleSettings.getMaxRowsOnScreen());
						if (sqlDatabase.isConnected()) {
							sqlDatabase.setCmdLineConsole(console);
							//consoleLogger.setPlatform(newPlatform);
							console.setPrompt(this.platform + "> ");
							if (consoleSettings.isLogDefaultActivated() && !SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(sqlDatabase.getPlatform().getId())) {
								this.printToLogFile = true;
							} else {
								this.printToLogFile = false;
							}
							console.setPrintToLogFile(printToLogFile);
							console.println("");
							// Show DB infos
							getConsoleCommandInterpreter().setPlatform(newPlatform);
							getConsoleCommandInterpreter().setQuery(new CommandShowDbInfo().getKeywords()[0]);
							getConsoleCommandInterpreter().executeCommand();
							// Show Autocommit infos
							getConsoleCommandInterpreter().setQuery(new CommandShowAutoCommit().getKeywords()[0]);
							getConsoleCommandInterpreter().executeCommand();
						} else {
							throw new BroadSQLException("Unable to connect to '" + newPlatform + "'");
						}
					}
				}
			} else {
				console.error("You must specify a connection ID");
			}
		} else {
			console.error("You must specify a connection ID");
		}
	}

	@Override
	public String getDescription() {
		return ("Opens a connection to a database");
	}

	@Override
	public String getArguments() {
		return "a valid connection ID (mandatory)";
	}

	@Override
	public String getExamples() {
		return "CONNECT db01;";
	}
}
