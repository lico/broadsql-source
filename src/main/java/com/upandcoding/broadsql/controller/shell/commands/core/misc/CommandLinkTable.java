/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.upandcoding.broadsql.controller.shell.commands.core.misc;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandConnect;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowAutoCommit;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowDbInfo;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Links a table from another configured connection into the current database:
 * {@code LINK TABLE <connectionId> <tableName>}.
 *
 * <p>Both arguments are mandatory. {@code connectionId} must be an existing, reachable connection
 * (checked before linking); {@code tableName} is used, unmodified, both as the table being linked in
 * the remote database and as the name it gets in the current database - there is currently no way to
 * link it under a different local name.
 *
 * <p>Only works when the target connection's configured database type is H2, since it relies on H2's
 * {@code CREATE LINKED TABLE} statement (see the H2 documentation for the linked-table grammar).
 * Reports an error if the connection is undefined, unreachable, or not of a supported type. This
 * creates a live link only - it does not copy the remote table's data into the current database.
 */
public class CommandLinkTable extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandLinkTable.class);

	public CommandLinkTable() {
		super("LINK TABLE", "LINKT");
	}

	@Override
	public boolean isHidden() {
		return false;
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String targetPlatform = null;
		String targetTable = null;
		String tableNamePattern = null;
		String schemaNamePattern = "";

		if (CommandUtils.isValidArgs(args)) {
			targetPlatform = args[0].trim();
			targetTable = args[1].trim();
			if (StringUtils.isNotBlank(targetPlatform) && StringUtils.isNotBlank(targetTable)) {

				if (!getDatabaseConnectionsVault().contains(targetPlatform)) {
					if (getDatabaseConnectionsVault().isInactiveConnection(targetPlatform)) {
						throw new BroadSQLException(CommandUtils.inactiveConnectionMessage(targetPlatform));
					}
					throw new BroadSQLException("Connection '" + targetPlatform + "' not defined");

				} else {
					StringBuffer result = this.sqlDatabase.testConnectionToExistingPlatform(targetPlatform);
					if (result == null || result.length() <= 0 || result.toString().trim().equals("")) {
						console.error("Connection FAILED");
					} else {

						// Determine schema / table names
						tableNamePattern = targetTable;
						if (tableNamePattern.contains(".")) {
							schemaNamePattern = StringUtils.substringBeforeLast(tableNamePattern, ".");
							if (StringUtils.isBlank(schemaNamePattern)) {
								schemaNamePattern = null;
							}
							tableNamePattern = StringUtils.substringAfterLast(tableNamePattern, ".");
						}

						// Determine target
						DatabaseDefinition targetDb = getDatabaseConnectionsVault().getDatabaseConnection(targetPlatform);
						if (SpringPropertiesConfig.DBTYPE_H2.equalsIgnoreCase(targetDb.getDbType())) {
							String pwd = targetDb.getUserPassword();
							log.debug("Encrypted: {}", targetDb.getEncrypted());
							try {
								String sql = "CREATE LINKED TABLE " + targetTable + " ('" + targetDb.getDbDriver() + "', '" + targetDb.getUrl() + "', '" + targetDb.getUserName() + "', '" + pwd + "', '" + targetTable + "')";
								sqlDatabase.executeUpdateQuery(sql);
							} catch (BroadSQLException e) {
								console.error(e.getLocalizedMessage());
							}
						} else {
							console.error("Operation not supported for database type: '" + targetDb.getDbType() + "'");
						}

						/*
						// Opening the new database
						this.setPlatform(targetPlatform);
						this.getSession().openDatabase(targetPlatform);
						sqlDatabase.setToScreen(true);
						sqlDatabase.setMaxRowsOnScreen(consoleSettings.getMaxRowsOnScreen());
						if (sqlDatabase.isConnected()) {
							sqlDatabase.setCmdLineConsole(shellConsole);
							consoleLogger.setPlatform(targetPlatform);
							shellConsole.setPrompt(this.platform + "> ");
							if (consoleSettings.isLogDefaultActivated() && !SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(sqlDatabase.getPlatform().getId())) {
								this.printToLogFile = true;
							} else {
								this.printToLogFile = false;
							}
							shellConsole.setPrintToLogFile(printToLogFile);
							shellConsole.println("");
							// Show DB infos
							getConsoleCommandInterpreter().setPlatform(targetPlatform);
							getConsoleCommandInterpreter().setQuery(new CommandShowDbInfo().getKeywords()[0]);
							getConsoleCommandInterpreter().executeCommand();
							// Show Autocommit infos
							getConsoleCommandInterpreter().setQuery(new CommandShowAutoCommit().getKeywords()[0]);
							getConsoleCommandInterpreter().executeCommand();
						} else {
							throw new BroadSQLException("Unable to connect to '" + targetPlatform + "'");
						}
						*/
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
		return ("Create a link to a table from a remote database (not available for all databases)");
	}

	@Override
	public String getArguments() {
		return "connection (mandatory), table name (mandatory)";
	}

	@Override
	public String getExamples() {
		return "LINKT mydb01 CUSTOMERS";
	}
}
