package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.output.ConsolePrinter;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;

/**
 * Everything the editor's {@code Run} needs to reach the canonical {@code ScriptExecutor} (SPRINT
 * 0917-01, spec section 22 - "the GUI is another client of the execution architecture, not an
 * alternative execution architecture"; renamed from {@code ScriptExecutionContext} by SPRINT 1909S so it
 * cannot be confused with the interpreter's {@code ScriptContextStack}). Carries no execution logic
 * itself - a plain snapshot of the collaborators the {@code EDIT}/{@code LIB EDIT}
 * commands already have on every invocation, refreshed on every such call (a connection can change
 * between calls - {@code CONNECT}/{@code DISCONNECT} - so a stale snapshot from the workspace's first
 * creation would be wrong).
 */
public final class ScriptRunContext {

	private final DatabaseConnection sqlDatabase;
	private final CommandInterpreter consoleCommandInterpreter;
	private final DatabaseDefinitionsVault databaseConnectionsVault;
	private final ConsolePrinter shellConsolePrinter;
	private final ConsoleSettings consoleSettings;
	private final String platform;

	public ScriptRunContext(DatabaseConnection sqlDatabase, CommandInterpreter consoleCommandInterpreter,
			DatabaseDefinitionsVault databaseConnectionsVault, ConsolePrinter shellConsolePrinter, ConsoleSettings consoleSettings, String platform) {
		this.sqlDatabase = sqlDatabase;
		this.consoleCommandInterpreter = consoleCommandInterpreter;
		this.databaseConnectionsVault = databaseConnectionsVault;
		this.shellConsolePrinter = shellConsolePrinter;
		this.consoleSettings = consoleSettings;
		this.platform = platform;
	}

	/** An empty context - no active connection, no vault. Every {@code Run} check using this context degrades gracefully (see {@code ScriptRunCoordinator}). */
	public static ScriptRunContext empty() {
		return new ScriptRunContext(null, null, null, null, null, null);
	}

	public DatabaseConnection sqlDatabase() {
		return sqlDatabase;
	}

	public CommandInterpreter consoleCommandInterpreter() {
		return consoleCommandInterpreter;
	}

	public DatabaseDefinitionsVault databaseConnectionsVault() {
		return databaseConnectionsVault;
	}

	public ConsolePrinter shellConsolePrinter() {
		return shellConsolePrinter;
	}

	public ConsoleSettings consoleSettings() {
		return consoleSettings;
	}

	public String platform() {
		return platform;
	}

	/**
	 * {@code true} only when there is a real, live database connection to execute against - the sole
	 * gate for {@code Run}/{@code Run Selection}; every other Script Library capability works
	 * regardless. {@code DatabaseConnection.isConnected()} failing outright (its own checked exception)
	 * is treated the same as "not connected," not propagated - this is a plain capability check, not an
	 * execution attempt.
	 */
	public boolean hasActiveConnection() {
		if (sqlDatabase == null) {
			return false;
		}
		try {
			return sqlDatabase.isConnected();
		} catch (com.upandcoding.broadsql.controller.errors.BroadSQLException e) {
			return false;
		}
	}
}
