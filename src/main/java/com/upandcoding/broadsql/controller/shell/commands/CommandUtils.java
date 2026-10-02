package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.listsource.ListSource;
import com.upandcoding.broadsql.controller.shell.commands.listsource.ListSourceResolver;
import com.upandcoding.broadsql.controller.shell.commands.listsource.ListSourceMacroScanner;
import com.upandcoding.broadsql.controller.shell.commands.listsource.SqlListLiteral;
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
	 * {@code releases/documentation/library_and_scripts.md}, section 4) - {@code ""} (never {@code null}) when there
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
	 * Whether {@code query} starts with the command keyword {@code keyword} as a whole command word
	 * (case-insensitively): the query is the keyword itself, or the keyword is followed by whitespace. A
	 * symbolic keyword (one not ending with a letter or digit, such as {@code @}) needs no boundary
	 * ({@code @script}). So {@code DESCRIBE X} does not start with {@code DESCR}, and {@code FIND INDEXES X}
	 * does not start with {@code FIND INDEX}.
	 */
	public static boolean startsWithKeyword(String query, String keyword) {
		return query != null && keywordMatchEnd(query.trim(), keyword) >= 0;
	}

	/**
	 * SPRINT 0110A: where the command keyword {@code keyword} ends in {@code trimmedQuery} (an already trimmed
	 * query), or {@code -1} when the query does not start with it as a whole command word (see
	 * {@link #startsWithKeyword}). The words of a multi-word keyword ({@code SHOW SCRIPT VARIABLES},
	 * {@code ON ERROR}, {@code LIB RUN}) may be separated by any amount of whitespace, as script statements and
	 * typed lines may contain (spec section 6.2); the keyword itself is written with single spaces.
	 */
	public static int keywordMatchEnd(String trimmedQuery, String keyword) {
		if (trimmedQuery == null || StringUtils.isBlank(keyword)) {
			return -1;
		}
		String kw = keyword.trim();
		String q = trimmedQuery;
		String[] words = kw.split("\\s+");
		int pos = 0;
		for (int w = 0; w < words.length; w++) {
			if (w > 0) {
				int ws = pos;
				while (ws < q.length() && Character.isWhitespace(q.charAt(ws))) {
					ws++;
				}
				if (ws == pos) {
					return -1;
				}
				pos = ws;
			}
			if (!q.regionMatches(true, pos, words[w], 0, words[w].length())) {
				return -1;
			}
			pos += words[w].length();
		}
		if (pos == q.length() || !Character.isLetterOrDigit(kw.charAt(kw.length() - 1))) {
			return pos;
		}
		return Character.isWhitespace(q.charAt(pos)) ? pos : -1;
	}

	/**
	 * The keyword of {@code keywords} that {@code query} starts with ({@link #startsWithKeyword}), the
	 * longest one when several do (so an alias that extends another, {@code DESCRIBE} after {@code DESCR},
	 * wins whatever the declaration order), or {@code null} when none does. The one keyword-matching rule of
	 * both argument parsing ({@link #getArgumentsFromQuery}) and command dispatch
	 * ({@code CommandList.getCommandClassFromName}).
	 */
	public static String matchingKeyword(String query, String[] keywords) {
		String best = null;
		if (keywords != null) {
			for (String keyword : keywords) {
				if (startsWithKeyword(query, keyword) && (best == null || keyword.trim().length() > best.trim().length())) {
					best = keyword;
				}
			}
		}
		return best;
	}

	/**
	 * One argument as {@link #getArgumentsFromQuery} reads it back: wrapped in {@code "} when it contains
	 * whitespace, unchanged otherwise. The one quoting rule shared by TAB completion and the BroadSQL Editor's
	 * Send to CLI, so both produce input the argument parser accepts.
	 */
	public static String quoteArgumentIfNeeded(String argument) {
		return argument.chars().anyMatch(Character::isWhitespace) ? "\"" + argument + "\"" : argument;
	}

	/**
	 * Returns an array of command's arguments: what follows the command keyword ({@link #matchingKeyword}),
	 * separated by space signs, except those space signs included in quotes(")
	 */
	public static String[] getArgumentsFromQuery(String query, String[] keywords) {
		String[] args = null; 
		String qte = "\"";
		String endQuery = "";
		if (StringUtils.isNotBlank(query) && keywords != null && keywords.length > 0) {
			query = query.trim();
			String keyword = matchingKeyword(query, keywords);
			if (keyword != null) {
				endQuery = query.substring(keywordMatchEnd(query, keyword)).trim();
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
			// CONNECTIONS.ID is a case-insensitive key (WORLD and world are the same connection) that an inactive
			// connection still occupies: every check below uses the ID as it is stored
			String existing = databaseConnectionsVault.findConnectionIdIgnoreCase(id);
			if (newConnection) {
				// Checked before the first prompt, so nothing typed is lost to a length or duplicate error at save time
				DatabaseDefinitionsVault.assertConnectionIdLength(id);
				if (existing != null && databaseConnectionsVault.contains(existing)) {
					throw new BroadSQLException(DatabaseDefinitionsVault.connectionIdTakenMessage(id, existing));
				}
				if (existing != null) {
					// ID collision with an inactive connection: offer to reactivate it instead of creating a duplicate ID
					offerReactivation(existing, console, databaseConnectionsVault);
					return;
				}
			} else if (existing == null) {
				throw new BroadSQLException(BroadSQLErrorMessages.ERR_CONN_01);
			} else if (!databaseConnectionsVault.contains(existing)) {
				// EDIT CONNECTION on an inactive ID: modification of an inactive connection is only
				// available from the config screen (view + reactivate/hard delete) or REACTIVATE CONNECTION,
				// never through EDIT CONNECTION directly.
				throw new BroadSQLException(inactiveConnectionMessage(existing));
			}

			DatabaseDefinition def = null;
			if (newConnection) {
				def = new DatabaseDefinition(id);
			} else {
				// A detached draft: the live definition (held by the vault, and by the session when it is the current
				// connection) changes only through a successful save and reload, never through a cancelled or failed edit
				def = databaseConnectionsVault.getDatabaseConnection(existing).copy();
				def.setStatus(DatabaseDefinition.STATUS_ACTIVE);
			}
			runConnectionFieldWizard(def, newConnection, console, databaseConnectionsVault, sqlDatabase);

		} else {
			console.error(BroadSQLErrorMessages.ERR_CONN_01);
		}
		console.println("");
	}

	/** The inactive-ID collision of ADD and DUPLICATE CONNECTION: reactivate the inactive connection {@code id}, or abort. */
	private static void offerReactivation(String id, ShellConsole console, DatabaseDefinitionsVault databaseConnectionsVault) throws BroadSQLException {
		console.println("An inactive connection already exists for ID '" + id + "'.");
		String confirm = console.inputField(new DatabaseDefinition(id), "Reactivate it (y), or choose a different ID (n)?", "", false, false, true, null);
		if (confirm != null && (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes"))) {
			databaseConnectionsVault.reactivateDatabaseDefinition(id);
			console.println("Connection '" + id + "' reactivated");
		} else {
			console.println("Operation aborted. Choose a different ID.");
		}
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

		DatabaseDefinitionsVault.assertConnectionIdLength(newId);
		String existing = databaseConnectionsVault.findConnectionIdIgnoreCase(newId);
		if (existing != null && databaseConnectionsVault.contains(existing)) {
			throw new BroadSQLException(DatabaseDefinitionsVault.connectionIdTakenMessage(newId, existing));
		} else if (existing != null) {
			offerReactivation(existing, console, databaseConnectionsVault);
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
				console.success("Database connection '" + def.getId() + "' successfully saved");
			} else if (confirm.equalsIgnoreCase("n")) {
				repeat = true;
			} else {
				console.println("Operation aborted");
				repeat = false;
			}
		}
	}// runConnectionFieldWizard

	/**
	 * Replaces every {@code <@...>} pattern in {@code query} with a parenthesized, comma-joined SQL list
	 * literal built from the named {@link ListSource} - a text file (the original, unprefixed form, e.g.
	 * {@code <@c:\temp\ids.txt>}), the clipboard ({@code <@clipboard>}), a CSV column
	 * ({@code <@csv:<path>:<column>>}) or an Excel column ({@code <@excel:<path>:<sheet>:<column>>}) - see
	 * {@link ListSourceResolver} for how the token is dispatched and {@link SqlListLiteral} for the exact
	 * quoting/escaping/empty-list rules applied uniformly to every source. SPRINT 2409K: each macro and
	 * the exact {@code >} closing it are located by {@link ListSourceMacroScanner} (openers inside string
	 * literals, quoted identifiers and comments are ignored; the closing {@code >} is the first one after
	 * that macro's own {@code <@}, on the same line), and every macro is replaced at its own position,
	 * exactly once, so ordinary SQL {@code >} comparisons anywhere in the statement never act as a
	 * delimiter and substituted values are never re-scanned.
	 */
	public static String substituteMacros(String query) throws BroadSQLException {
		if (query == null) {
			return null;
		}
		List<ListSourceMacroScanner.Macro> macros = ListSourceMacroScanner.scan(query);
		if (macros.isEmpty()) {
			return query;
		}
		StringBuilder result = new StringBuilder(query.length());
		int copied = 0;
		for (ListSourceMacroScanner.Macro macro : macros) {
			ListSource source = ListSourceResolver.resolve(macro.getToken());
			List<String> values = source.values();
			result.append(query, copied, macro.getStart());
			result.append(SqlListLiteral.render(values, "<@" + macro.getToken() + ">"));
			copied = macro.getEnd();
		}
		result.append(query, copied, query.length());
		return result.toString();
	}
}
