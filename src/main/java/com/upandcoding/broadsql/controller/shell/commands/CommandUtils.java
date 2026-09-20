package com.upandcoding.broadsql.controller.shell.commands;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

public class CommandUtils {

	private static final Logger log = LoggerFactory.getLogger(CommandUtils.class);

	/**
	 * This function splits a string but does not split the part of the string
	 * that is enclosed in quotes (")
	 * Can be used by commands to work with the data entered by the user 
	 * Example with splitting caracter being space:
	 * input: A B "C D" E
	 * output {"A","B","C D", "E"}
	 *  
	 */
	public static String[] splitPreserveQuotes(String str, String car) {
		String[] result = str.split(car + "(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
		return result;
	}

	/**
	 * The message every command that resolves a connection ID for use (not for the config screen)
	 * must show when that ID names an inactive connection - so it is never reported as simply
	 * "missing"/"not defined" like a truly unknown ID would be. Callers check
	 * {@link DatabaseDefinitionsVault#contains(String)} first (which only ever sees active
	 * connections); if that's {@code false}, they check
	 * {@link DatabaseDefinitionsVault#isInactiveConnection(String)} and use this message when it's
	 * {@code true}, falling back to their own existing "not found" message otherwise.
	 */
	public static String inactiveConnectionMessage(String id) {
		return "Connection '" + id + BroadSQLErrorMessages.ERR_CONN_03_SUFFIX;
	}

	/**
	 * The current connection's environment, for LIB/SCRIPT environment scoping (see
	 * {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}, section 4) - {@code ""} (never {@code null}) when there
	 * is no active connection, the connection id isn't found in the vault, or the connection has no
	 * environment set. {@code EntryMetadata.appliesToEnvironment("")} then correctly matches only
	 * {@code NONE}/{@code ALL}-tagged entries, degrading safely with no special-casing needed by callers.
	 */
	public static String currentEnvironment(String platform, DatabaseDefinitionsVault databaseConnectionsVault) {
		if (StringUtils.isNotBlank(platform) && databaseConnectionsVault != null && databaseConnectionsVault.contains(platform)) {
			DatabaseDefinition connection = databaseConnectionsVault.getDatabaseConnection(platform);
			if (connection != null && StringUtils.isNotBlank(connection.getEnvironment())) {
				return connection.getEnvironment();
			}
		}
		return "";
	}

	/**
	 * The current connection's instance, for LIB/SCRIPT instance scoping - the same mechanism as
	 * {@link #currentEnvironment}, independent dimension. {@code ""} (never {@code null}) when there is
	 * no active connection, the connection id isn't found in the vault, or the connection has no
	 * instance set.
	 */
	public static String currentInstance(String platform, DatabaseDefinitionsVault databaseConnectionsVault) {
		if (StringUtils.isNotBlank(platform) && databaseConnectionsVault != null && databaseConnectionsVault.contains(platform)) {
			DatabaseDefinition connection = databaseConnectionsVault.getDatabaseConnection(platform);
			if (connection != null && StringUtils.isNotBlank(connection.getDatabaseGroup())) {
				return connection.getDatabaseGroup();
			}
		}
		return "";
	}

	/**
	 * Resolves {@code connectionId} to an active connection for the login-script line commands
	 * ({@code ADD}/{@code EDIT}/{@code DEL}/{@code MOVE LOGIN SCRIPT LINE}), reporting the standard
	 * error on the console (a distinct one for an inactive ID, per {@link #inactiveConnectionMessage})
	 * and returning {@code false} when it's not a usable ID - callers must return immediately when this
	 * returns {@code false}.
	 */
	public static boolean requireActiveConnection(String connectionId, ShellConsole console, DatabaseDefinitionsVault databaseConnectionsVault) throws BroadSQLException {
		if (StringUtils.isBlank(connectionId)) {
			console.error(BroadSQLErrorMessages.ERR_CONN_01);
			return false;
		}
		if (!databaseConnectionsVault.contains(connectionId)) {
			if (databaseConnectionsVault.isInactiveConnection(connectionId)) {
				console.error(inactiveConnectionMessage(connectionId));
			} else {
				console.error(BroadSQLErrorMessages.ERR_CONN_01);
			}
			return false;
		}
		return true;
	}// requireActiveConnection

	/**
	 * Parses a login script line number ({@code <lineNo>}, 1-based) for {@code EDIT}/{@code DEL}/
	 * {@code MOVE LOGIN SCRIPT LINE}, reporting {@link BroadSQLErrorMessages#ERR_LOGINSCRIPT_02} and
	 * returning {@code null} if it isn't a positive integer - callers must return immediately when this
	 * returns {@code null}.
	 */
	public static Integer parseLineNumber(String raw, ShellConsole console) {
		try {
			int n = Integer.parseInt(raw.trim());
			if (n < 1) {
				console.error(BroadSQLErrorMessages.ERR_LOGINSCRIPT_02);
				return null;
			}
			return n;
		} catch (NumberFormatException nfe) {
			console.error(BroadSQLErrorMessages.ERR_LOGINSCRIPT_02);
			return null;
		}
	}// parseLineNumber

	/**
	 * Checks whether arguments list is not null
	 */
	public static boolean isValidArgs(String[] args) {
		boolean result = ((args != null) && (args.length > 0) && (StringUtils.isNotBlank(args[0])));
		return result;
	}

	/**
	 * Collapses a query onto a single line - every run of whitespace (including embedded newlines
	 * from a multi-line paste captured as one piece of input) becomes a single space, and the result
	 * is trimmed. Used to display the last executed query (the {@code //} shortcut, {@code SHOW
	 * QUERY}) in a form convenient for reading and copy-paste, regardless of how it was originally
	 * typed or pasted.
	 */
	public static String toSingleLine(String text) {
		if (text == null) {
			return null;
		}
		return text.trim().replaceAll("\\s+", " ");
	}

	/**
	 * Opens {@code file} in Notepad and blocks until the user closes it - shared by every
	 * "edit in Notepad, then read back" command ({@code EDIT}, {@code LIB EDIT}, {@code SCRIPT EDIT}).
	 *
	 * <p>Does <b>not</b> simply {@code waitFor()} the launched {@code notepad.exe} process: on current
	 * Windows 11 builds, {@code notepad.exe} is a thin launcher for the modern, packaged, single-instance
	 * Notepad app (under {@code WindowsApps}) - it hands off to that app (starting it, or just adding a
	 * tab to an already-running instance) and exits on its own within a few seconds, regardless of
	 * whether the user is even done editing yet. Confirmed empirically (see
	 * {@code docs/TECHNICAL_CHANGE.md}, 03/09/2026): the launcher process exited after ~5s with no user
	 * interaction, while a separate {@code Notepad.exe} process (the real editor window, title showing
	 * the temp file's name) kept running - reading the file back immediately after the launcher's
	 * {@code waitFor()} returned, as every {@code *EDIT} command previously did, silently captured
	 * whatever was on disk at that moment, not what the user actually saved.
	 *
	 * <p>Instead, a single long-lived PowerShell process (not one process per poll) polls for a Notepad
	 * window whose title contains {@code file}'s own name - both the classic and the modern packaged
	 * Notepad always include the open file's name in the window title - and only returns once no such
	 * window is found anymore. This also works unchanged against the classic, always-blocking
	 * {@code notepad.exe}: by the time its own launcher process would have returned, the matching window
	 * is already gone, so the poll loop exits immediately. Known limitation: if the user has several
	 * Notepad tabs open and switches away from this one without closing it, the reported window title
	 * (whichever tab is currently in the foreground) stops matching and this returns early, reading back
	 * whatever is on disk at that point - still strictly better than the previous behavior, which failed
	 * unconditionally, and unlikely in the ordinary one-file-at-a-time workflow these commands are built
	 * for.
	 */
	public static void openInNotepadAndWaitForClose(File file) throws IOException, InterruptedException {
		new ProcessBuilder("notepad.exe", file.getAbsolutePath()).start();

		String escapedFileName = file.getName().replace("'", "''");
		String script = "$name = [regex]::Escape('" + escapedFileName + "'); "
				+ "$deadline = (Get-Date).AddSeconds(15); "
				+ "while ((Get-Date) -lt $deadline -and -not (Get-Process -Name notepad -ErrorAction SilentlyContinue "
				+ "| Where-Object { $_.MainWindowTitle -match $name })) { Start-Sleep -Milliseconds 300 }; "
				+ "while (Get-Process -Name notepad -ErrorAction SilentlyContinue "
				+ "| Where-Object { $_.MainWindowTitle -match $name }) { Start-Sleep -Milliseconds 300 }";
		new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).inheritIO().start().waitFor();
	}

	/**
	 * Returns an array of command's arguments
	 * Arguments are separated by space signs, except those space signs included in quotes(")
	 */
	public static String[] getArgumentsFromQuery(String query, String[] keywords) {
		String[] args = null; 
		String qte = "\"";
		//System.out.println("Query=" + query);
		String endQuery = "";
		if (StringUtils.isNotBlank(query) && keywords != null && keywords.length > 0) {
			//query = query.toUpperCase();
			query = query.trim();
			for (String kword : keywords) {
				String wordSep = "";
				if (query.equalsIgnoreCase(kword)) {
					endQuery = "";
					break;
				} else {
					if (StringUtils.startsWithIgnoreCase(query, kword)) {
						if ("@".equalsIgnoreCase(kword)) {
							wordSep = "";
						} else {
							wordSep = " ";
						}
						/*
						System.out.println("query: " + query);
						System.out.println("kword: " + kword);
						*/
						endQuery = StringUtils.removeStartIgnoreCase(query, kword + wordSep);
						if (StringUtils.isNotBlank(endQuery)) {
							endQuery = endQuery.trim();
						}
						//System.out.println("endQuery=" + endQuery);
						//endQuery = StringUtils.substringAfter(query, kword + wordSep);
						break;
					}
				}
			}
		}

		if (StringUtils.isNotBlank(endQuery)) {
			args = splitPreserveQuotes(endQuery, " ");
			if (args != null && args.length > 0) {
				for (int i = 0; i < args.length; i++) {
					String arg = args[i];
					if (arg.startsWith(qte)) {
						arg = StringUtils.substringAfter(arg, qte);
					}
					if (arg.endsWith(qte)) {
						arg = StringUtils.substringBeforeLast(arg, qte);
					}
					args[i] = arg;
				}
			}
		}
		return args;
	}

	/**
	 * From an input like SCHEMA.TABLENAME returns the schema name
	 */
	public static String getSchemaName(String tableName) {
		String schema = null;
		if (StringUtils.isNotBlank(tableName) && tableName.contains(".")) {
			schema = StringUtils.substringBeforeLast(tableName, ".");
			if (StringUtils.isNotBlank(schema)) {
				schema = schema.trim();
			}
		}
		return (schema);
	}

	/**
	 * From an input like SCHEMA.TABLENAME returns the table name
	 */
	public static String getTableName(String tableName) {
		String table = tableName;
		if (StringUtils.isNotBlank(tableName)) {
			if (tableName.contains(".")) {
				table = StringUtils.substringAfterLast(tableName, ".");
				if (StringUtils.isNotBlank(table)) {
					table = table.trim();
				}
			}
		}
		return (table);
	}

	/**
	 * Determines whether a query is a SELECT query
	 */
	public static boolean isSelectStatement(String query) {
		boolean result = false;
		if (StringUtils.isNotBlank(query)) {
			result = query.trim().toLowerCase().startsWith("select");
		}
		return (result);
	}

	/*
	 * Determines whether a query is NOT for an update (select, show, with, etc.)
	 */
	public static boolean isNotUpdateStatement(String query) {
		//String[] cmdNames = { "select", "call", "script", "runscript", "show", "with", "explain" };
		String[] cmdNames = { "select", "call", "script", "show", "with", "explain" };
		boolean result = false;
		if (StringUtils.isNotBlank(query)) {
			for (String cmdName : cmdNames) {
				if (query.toLowerCase().startsWith(cmdName.toLowerCase() + " ")) {
					result = true;
					break;
				}
			}
		}
		return (result);
	}

	/*
	 * Used by ADD CONNECTION et EDIT CONNECTION
	 */
	public static void addOrEditPlatform(String id, boolean newConnection, ShellConsole console, DatabaseDefinitionsVault databaseConnectionsVault, DatabaseConnection sqlDatabase) throws BroadSQLException {
		if (StringUtils.isNotBlank(id)) {
			if (databaseConnectionsVault.contains(id)) {
				if (newConnection) {
					throw new BroadSQLException(BroadSQLErrorMessages.ERR_CONN_02);
				}
			} else if (databaseConnectionsVault.isInactiveConnection(id)) {
				if (newConnection) {
					// ID collision with an inactive connection (not an active one, otherwise ERR_CONN_02
					// above would have fired): offer to reactivate it instead of creating a duplicate ID -
					// asked before the wizard even starts, so nothing typed is ever thrown away silently.
					console.println("An inactive connection already exists for ID '" + id + "'.");
					String confirm = console.inputField(new DatabaseDefinition(id), "Reactivate it (y), or choose a different ID (n)?", "", false, false, true, null);
					if (confirm != null && (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes"))) {
						databaseConnectionsVault.reactivateDatabaseDefinition(id);
						console.println("Connection '" + id + "' reactivated");
					} else {
						console.println("Operation aborted. Choose a different ID.");
					}
					return;
				}
				// EDIT CONNECTION on an inactive ID: modification of an inactive connection is only
				// available from the config screen (view + reactivate/hard delete) or REACTIVATE CONNECTION,
				// never through EDIT CONNECTION directly.
				throw new BroadSQLException(inactiveConnectionMessage(id));
			} else {
				if (!newConnection) {
					throw new BroadSQLException(BroadSQLErrorMessages.ERR_CONN_01);
				}
			}

			DatabaseDefinition def = null;
			if (newConnection) {
				def = new DatabaseDefinition(id);
			} else {
				def = databaseConnectionsVault.getDatabaseConnection(id);
				def.setStatus(DatabaseDefinition.STATUS_ACTIVE);
			}
			runConnectionFieldWizard(def, newConnection, console, databaseConnectionsVault, sqlDatabase);

		} else {
			console.error(BroadSQLErrorMessages.ERR_CONN_01);
		}
		console.println("");
	}

	/**
	 * Duplicates an existing database connection under a new ID: used by {@code DUPLICATE CONNECTION
	 * <sourceId> <newId>}. {@code sourceId} must name an active connection ({@code newId} follows the
	 * exact same collision rules as {@code ADD CONNECTION} - already-active is refused, an already-inactive
	 * ID offers to reactivate instead, exactly like {@link #addOrEditPlatform}). Every field (Name, Type,
	 * URL, User Name, User Password, Database Group, Environment, Comment) is pre-filled from the source
	 * connection, then the same interactive wizard as {@code ADD}/{@code EDIT CONNECTION} runs so the user
	 * can accept or change any of them before saving.
	 */
	public static void duplicatePlatform(String sourceId, String newId, ShellConsole console, DatabaseDefinitionsVault databaseConnectionsVault, DatabaseConnection sqlDatabase) throws BroadSQLException {
		if (StringUtils.isBlank(sourceId) || StringUtils.isBlank(newId)) {
			console.error(BroadSQLErrorMessages.ERR_CONN_01);
			return;
		}

		if (!databaseConnectionsVault.contains(sourceId)) {
			if (databaseConnectionsVault.isInactiveConnection(sourceId)) {
				console.error(inactiveConnectionMessage(sourceId));
			} else {
				console.error(BroadSQLErrorMessages.ERR_CONN_01);
			}
			return;
		}

		if (databaseConnectionsVault.contains(newId)) {
			throw new BroadSQLException(BroadSQLErrorMessages.ERR_CONN_02);
		} else if (databaseConnectionsVault.isInactiveConnection(newId)) {
			console.println("An inactive connection already exists for ID '" + newId + "'.");
			String confirm = console.inputField(new DatabaseDefinition(newId), "Reactivate it (y), or choose a different ID (n)?", "", false, false, true, null);
			if (confirm != null && (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes"))) {
				databaseConnectionsVault.reactivateDatabaseDefinition(newId);
				console.println("Connection '" + newId + "' reactivated");
			} else {
				console.println("Operation aborted. Choose a different ID.");
			}
			return;
		}

		DatabaseDefinition source = databaseConnectionsVault.getDatabaseConnection(sourceId);
		DatabaseDefinition copy = new DatabaseDefinition(newId, source.getDbType(), source.getDbDriver(), source.getUrl(), source.getUserName(), source.getUserPassword(), source.getDbName());
		copy.setDatabaseGroup(source.getDatabaseGroup());
		copy.setEnvironment(source.getEnvironment());
		copy.setComment(source.getComment());

		runConnectionFieldWizard(copy, true, console, databaseConnectionsVault, sqlDatabase);
		console.println("");
	}// duplicatePlatform

	/**
	 * The field-collection-then-save loop shared by {@code ADD}/{@code EDIT}/{@code DUPLICATE
	 * CONNECTION}: prompts in turn for Name, Type, URL, User Name, User Password, Database Group,
	 * Environment and Comment, pre-filled from whatever {@code def} already carries, then a
	 * {@code [y/n/c]} confirmation - {@code y} saves and immediately test-connects (reporting success or
	 * failure without aborting the save), {@code n} restarts the wizard from the top, anything else
	 * aborts without saving.
	 */
	private static void runConnectionFieldWizard(DatabaseDefinition def, boolean newConnection, ShellConsole console, DatabaseDefinitionsVault databaseConnectionsVault, DatabaseConnection sqlDatabase) throws BroadSQLException {
		boolean repeat = true;
		while (repeat) {
			console.println("");
			if (newConnection) {
				console.println("Creating new database connection with ID '" + def.getId() + "'");
			} else {
				console.println("Editing database connection '" + def.getId() + "'");
			}
			String name = console.inputField(def, "Name", def.getDbName(), false, false, false, null);
			def.setDbName(name);
			Set<String> unavailableTypes = databaseConnectionsVault.getUnavailableTypes();
			if (!unavailableTypes.isEmpty()) {
				console.println("Note: no JDBC driver was found in drivers/lib for: " + String.join(", ", unavailableTypes)
						+ " - these types can still be selected, but a connection using them will fail until the matching driver jar is added.");
			}
			String type = console.inputField(def, "Type", def.getDbType(), false, false, false, databaseConnectionsVault.getDbTypes());
			def.setDbType(type);
			String url = console.inputField(def, "URL", def.getUrl(), false, false, false, null);
			def.setUrl(url);
			String uName = console.inputField(def, "User Name", def.getUserName(), true, false, false, null);
			def.setUserName(uName);
			String uPwd = console.inputField(def, "User Password", def.getUserPassword(), true, true, false, null);
			def.setUserPassword(uPwd);
			String group = console.inputField(def, "Database Group", def.getDatabaseGroup(), true, false, false, databaseConnectionsVault.getGroups());
			def.setDatabaseGroup(StringUtils.trimToNull(group));
			String environment = console.inputField(def, "Environment", def.getEnvironment(), false, false, false, databaseConnectionsVault.getEnvironments());
			def.setEnvironment(environment);
			String comment = console.inputField(def, "Comment", def.getComment(), true, false, false, null);
			def.setComment(comment);

			String confirm = console.inputField(def, "Save database connection [y/n/c]?", "", false, false, true, null);
			if (confirm.equalsIgnoreCase("y")) {
				//log.debug(def.toString());
				repeat = false;
				databaseConnectionsVault.saveDatabaseDefinition(def);
				databaseConnectionsVault.load();
				console.println("");
				try {
					sqlDatabase.testConnectionToExistingPlatform(def.getId());
					console.println("Test to connection: OK");
				} catch (Exception e) {
					console.println("Test to connection: FAILED");
					console.println("With error: " + e.getLocalizedMessage());
				}
				console.println("Database connection '" + def.getId() + "' successfully saved");
			} else if (confirm.equalsIgnoreCase("n")) {
				repeat = true;
			} else {
				console.println("Operation aborted");
				repeat = false;
			}
		}
	}// runConnectionFieldWizard

	/**
	 * Replace macro in commands with their corresponding SQL code Currently only
	 * one macro: <@fileName> used for SQL keyword IN
	 * 
	 * @param query
	 * @return
	 */
	public static String substituteMacros(String query) throws BroadSQLException {
		String result = query;
		/* List of values, stored in a text file
		 * command: <@ >
		 * Reads values in a file and returns a list like ('A','B','C')
		 */
		while (result.contains("<@") && result.contains(">")) {
			String fileName = StringUtils.substringBetween(result, "<@", ">").trim();
			StringBuilder codes = new StringBuilder();
			try {
				FileReader fr = new FileReader(fileName);
				BufferedReader br = new BufferedReader(fr);
				String line = null;
				codes.append("(");
				int i = 0;
				while ((line = br.readLine()) != null) {
					if (!"".equals(line.trim())) {
						line = "'" + line.trim() + "'";
						if (i > 0) {
							codes.append(",");
						}
						codes.append(line);
						i++;
					}
				}
				codes.append(")");
				fr.close();
			} catch (FileNotFoundException fnfe) {
				throw new BroadSQLException("File " + fileName + " not found");
			} catch (IOException ie) {
				throw new BroadSQLException("Error was encountered when opening file " + fileName);
			}
			String listOfCodes = codes.toString();
			result = StringUtils.replace(result, "<@" + fileName + ">", listOfCodes);

		}

		return (result);
	}
}
