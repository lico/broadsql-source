package com.upandcoding.broadsql.controller.shell.commands;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.Session;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.controller.shell.output.ConsoleLogger;
import com.upandcoding.broadsql.controller.shell.output.ConsolePrinter;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;

public class CommandList extends HashMap<String, Command> {

	private static final Logger log = LoggerFactory.getLogger(CommandList.class);

	@Autowired
	Session session;

	@Autowired
	ShellConsole console;

	@Autowired
	CommandLoader consoleCommandLoader;

	@Autowired
	ConsoleSettings shellConsoleSettings;

	@Autowired
	ConsolePrinter shellConsolePrinter;

	@Autowired
	ConsoleLogger shellConsoleLogger;

	@Autowired
	DatabaseDefinitionsVault databaseConnectionsVault;

	@Autowired
	ApiDefinitionsVault apiDefinitionsVault;

	public ShellConsole getConsole() {
		return console;
	}

	public void setConsole(ShellConsole console) {
		this.console = console;
	}

	public CommandLoader getConsoleCommandLoader() {
		return consoleCommandLoader;
	}

	public void setConsoleCommandLoader(CommandLoader consoleCommandLoader) {
		this.consoleCommandLoader = consoleCommandLoader;
	}

	public ConsoleSettings getShellConsoleSettings() {
		return shellConsoleSettings;
	}

	public void setShellConsoleSettings(ConsoleSettings shellConsoleSettings) {
		this.shellConsoleSettings = shellConsoleSettings;
	}

	public ConsolePrinter getShellConsolePrinter() {
		return shellConsolePrinter;
	}

	public void setShellConsolePrinter(ConsolePrinter shellConsolePrinter) {
		this.shellConsolePrinter = shellConsolePrinter;
	}

	public ConsoleLogger getShellConsoleLogger() {
		return shellConsoleLogger;
	}

	public void setShellConsoleLogger(ConsoleLogger shellConsoleLogger) {
		this.shellConsoleLogger = shellConsoleLogger;
	}

	public Session getSession() {
		return session;
	}

	public void setSession(Session session) {
		this.session = session;
	}

	public DatabaseDefinitionsVault getDatabaseConnectionsVault() {
		return databaseConnectionsVault;
	}

	public void setDatabaseConnectionsVault(DatabaseDefinitionsVault databaseConnectionsVault) {
		this.databaseConnectionsVault = databaseConnectionsVault;
	}

	public ApiDefinitionsVault getApiDefinitionsVault() {
		return apiDefinitionsVault;
	}

	public void setApiDefinitionsVault(ApiDefinitionsVault apiDefinitionsVault) {
		this.apiDefinitionsVault = apiDefinitionsVault;
	}

	/**
	 * Retrieve the name of the command from the input query
	 * 
	 * @param uQuery
	 * @return
	 * @throws BroadSQLException
	 */
	public Command getCommandClassFromName(String uQuery) throws BroadSQLException {
		// #61: the longest matching keyword wins, so the result no longer depends on HashMap iteration order
		// when one keyword is a prefix of another (e.g. "SHOW" and "SHOW TABLES"). The same whole-word rule
		// as the commands' own argument parsing (CommandUtils.matchingKeyword), so the keyword that selects a
		// command is always the one its arguments are parsed after.
		if (uQuery == null) {
			return null;
		}
		String keyword = CommandUtils.matchingKeyword(uQuery, this.keySet().toArray(new String[0]));
		return keyword == null ? null : (Command) this.get(keyword);
	}

	/**
	 * Get a list of commands
	 * 
	 * @return
	 * @throws BroadSQLException
	 */
	protected void getConsoleCommands() throws BroadSQLException {
		HashMap<String, String> classMap = consoleCommandLoader.loadAvailableCommands();
		Set<String> classes = classMap.keySet();
		for (String className : classes) {
			try {
				Class classObject = Class.forName(className);
				try {
					Command cmd = (Command) classObject.getDeclaredConstructor().newInstance();
					if (cmd != null) {
						cmd.setConsole(console);
						cmd.setSession(session);
						cmd.setConsoleLogger(shellConsoleLogger);
						cmd.setConsoleSettings(shellConsoleSettings);
						cmd.setDatabaseConnectionsVault(databaseConnectionsVault);
						cmd.setApiDefinitionsVault(apiDefinitionsVault);
						cmd.setShellConsolePrinter(shellConsolePrinter);

						String[] keyWords = cmd.getKeywords();
						for (String keyWord : keyWords) {
							// #61: the loader already rejects conflicting classes; this catches any that slip through
							// (e.g. a hidden command) so a keyword is never silently taken over by a different command.
							Command previous = this.get(keyWord);
							if (previous != null && previous.getClass() != cmd.getClass()) {
								String msg = "Keyword '" + keyWord + "' of '" + cmd.getClass().getCanonicalName()
										+ "' conflicts with '" + previous.getClass().getCanonicalName() + "'; keeping the first";
								log.error(msg);
								consoleCommandLoader.recordError(msg);
								continue;
							}
							this.put(keyWord, cmd);
						}
					}
				} catch (InstantiationException ex) {
					console.error(new BroadSQLException(ex));
				} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException
						| NoSuchMethodException | SecurityException ex) {
					console.error(new BroadSQLException(ex));
				}

			} catch (ClassNotFoundException ex) {
				throw new BroadSQLException(ex);
			}
		}
	}

}
