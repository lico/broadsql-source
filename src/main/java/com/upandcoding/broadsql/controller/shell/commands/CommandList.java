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

	/**
	 * Retrieve the name of the command from the input query
	 * 
	 * @param uQuery
	 * @return
	 * @throws BroadSQLException
	 */
	public Command getCommandClassFromName(String uQuery) throws BroadSQLException {
		Command tClass = null;
		//System.out.println("Nbr classes: " + this.size());
		/*
				if (this == null || this.isEmpty()) {
					getConsoleCommands();
				}
				*/
		for (String cmdName : this.keySet()) {
			if ((cmdName.trim().equalsIgnoreCase(uQuery.trim())) || ("@".equalsIgnoreCase(cmdName)
					&& StringUtils.isNotBlank(uQuery) && uQuery.startsWith(cmdName))) {
				tClass = (Command) this.get(cmdName);
				break;
			} else {
				String cmdName2 = cmdName + " ";
				if (uQuery.startsWith(cmdName2)) {
					tClass = (Command) this.get(cmdName);
					break;
				}
			}
		}
		return (tClass);

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
						cmd.setShellConsolePrinter(shellConsolePrinter);

						String[] keyWords = cmd.getKeywords();
						for (String keyWord : keyWords) {
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
