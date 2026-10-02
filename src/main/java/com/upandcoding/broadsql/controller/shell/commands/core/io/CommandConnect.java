package com.upandcoding.broadsql.controller.shell.commands.core.io;

import java.util.List;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ShellPromptBuilder;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowAutoCommit;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowDbInfo;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

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
 * <p>{@code CONNECT <id>} always means a physical Connection, never an Environment: to switch by
 * Environment within the current Database Group, use {@code ENV <environment>} ({@link CommandEnv}),
 * which ends in the same {@link #connectToConnection(String)} path.
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

	/** For {@link CommandEnv}, which reuses {@link #connectToConnection(String)} under its own keywords. */
	protected CommandConnect(String... keywords) {
		super(keywords);
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		if (args != null && args.length >= 1 && "API".equalsIgnoreCase(args[0].trim())) {
			executeConnectApi(args);
			return;
		}

		String newPlatform = null;
		if (CommandUtils.isValidArgs(args)) {
			newPlatform = args[0].trim();
			if (StringUtils.isNotBlank(newPlatform)) {
				connectToConnection(newPlatform);
			} else {
				console.error("You must specify a connection ID");
			}
		} else {
			console.error("You must specify a connection ID");
		}
	}

	/**
	 * The one physical Connection switch: validates {@code newPlatform} against the CDF, tests it, then
	 * closes the current connection and opens this one (prompt, logging, {@code SHOW DBINFO}/{@code SHOW
	 * AUTOCOMMIT}; the interpreter then re-runs the login script because the platform changed).
	 *
	 * <p>SPRINT 2309T (#159): {@code ENV <environment>} ({@link CommandEnv}) resolves an Environment to a
	 * physical Connection and then calls exactly this method, so switching by Environment can never skip a
	 * check, a safeguard or a session-initialization step that {@code CONNECT <connection>} performs.
	 */
	protected void connectToConnection(String newPlatform) throws BroadSQLException {
		if (!getDatabaseConnectionsVault().contains(newPlatform)) {
			if (getDatabaseConnectionsVault().isInactiveConnection(newPlatform)) {
				throw new BroadSQLException(CommandUtils.inactiveConnectionMessage(newPlatform));
			}
			throw new BroadSQLException("Connection '" + newPlatform + "' not defined");
		}
		StringBuffer result = this.sqlDatabase.testConnectionToExistingPlatform(newPlatform);
		if (result == null || result.length() <= 0 || result.toString().trim().equals("")) {
			console.error("Connection FAILED");
			return;
		}
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
			console.setPrompt(ShellPromptBuilder.build(this.platform));
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

	/**
	 * {@code CONNECT API <api>:<environment>;} (SPRINT XT02-7B) - establishes an active API session
	 * context, entirely independent of the SQL database connection above: it never touches
	 * {@code sqlDatabase}, {@code platform}, or any of the SQL-connect machinery, so an active database
	 * connection (or the lack of one) survives this call untouched, and a later {@code CONNECT <id>}
	 * likewise leaves this API context untouched (see {@link ApiSessionContextHolder}).
	 */
	private void executeConnectApi(String[] args) throws BroadSQLException {
		if (args.length != 2) {
			console.error("Usage: CONNECT API <api>:<environment>;");
			return;
		}
		String target = args[1].trim();
		int colon = target.indexOf(':');
		if (colon < 0) {
			console.error("Malformed API target '" + target + "'. Expected <api>:<environment>.");
			return;
		}
		String apiId = target.substring(0, colon).trim().toUpperCase();
		String environmentName = target.substring(colon + 1).trim();
		if (apiId.isEmpty()) {
			console.error("Missing API identifier in '" + target + "'. Expected <api>:<environment>.");
			return;
		}
		if (environmentName.isEmpty()) {
			console.error("Missing environment name in '" + target + "'. Expected <api>:<environment>.");
			return;
		}

		if (!getApiDefinitionsVault().contains(apiId)) {
			ApiDefinition apiRow = getApiDefinitionsVault().findApiById(apiId);
			if (apiRow != null) {
				console.error("API '" + apiId + "' is inactive. Reactivate it first (CONFIG API), or use SHOW ALL APIS to list active APIs.");
			} else {
				console.error("API '" + apiId + "' not found. Use SHOW ALL APIS to list imported APIs.");
			}
			return;
		}

		ApiEnvironment environment = getApiDefinitionsVault().getEnvironmentsForApi(apiId).stream()
				.filter(e -> environmentName.equals(e.getName())).findFirst().orElse(null);
		if (environment == null) {
			console.error("Environment '" + environmentName + "' not found for API '" + apiId + "'. Use SHOW API ENVIRONMENTS " + apiId + " to find it.");
			return;
		}
		if (!DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(environment.getStatusId())) {
			console.error("Environment '" + environmentName + "' for API '" + apiId + "' is inactive. Reactivate it first (CONFIG API), or use SHOW API ENVIRONMENTS "
					+ apiId + " to check its status.");
			return;
		}

		ApiSessionContextHolder.set(new ApiSessionContext(apiId, environment.getName()));
		console.setPrompt(ShellPromptBuilder.build(this.platform));
		console.println("API " + apiId + " connected using environment " + environment.getName() + ".");
	}

	@Override
	public String getDescription() {
		return ("Opens a connection to a database, or an API session context with CONNECT API");
	}

	@Override
	public String getDetailedDescription() {
		return "CONNECT <id> opens a physical connection to a database defined in the Connections Definition File. "
				+ "Aliases: OPEN, CON, CONN. To switch to another Environment of the current Database Group instead, use "
				+ "ENV <environment>. "
				+ "CONNECT API <api>:<environment> establishes an active API session context instead - the API "
				+ "and environment RUN and SHOW ENDPOINTS use when none is repeated on the command line. The two "
				+ "contexts are entirely independent: connecting to an API never closes or replaces the current "
				+ "database connection, and connecting to a database never clears an active API context. The "
				+ "prompt shows both when both are active, for example $CDF [API DESK:PROD]>. Use DISCONNECT API "
				+ "to clear only the API context.";
	}

	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.CONNECTION);
	}

	@Override
	public String getArguments() {
		return "a valid connection ID (mandatory); or API <api>:<environment> to establish an API session context";
	}

	@Override
	public String getExamples() {
		return "CONNECT db01; CONNECT API DESK_DEFINITION:PROD;";
	}
}
