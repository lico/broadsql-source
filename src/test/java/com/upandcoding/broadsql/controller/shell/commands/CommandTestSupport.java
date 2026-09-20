package com.upandcoding.broadsql.controller.shell.commands;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandDefault;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.controller.shell.output.ConsolePrinter;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;

/**
 * Builds a {@link Command} wired with a {@link CapturingShellConsole} and (optionally) a
 * {@link DatabaseConnection}, without a Spring {@code ApplicationContext} - nothing under test here
 * depends on Spring's own wiring behavior, only on the collaborators a command actually reads (see
 * {@code docs/TESTS_STRATEGY.md}, "Test harness"). Lives in this exact package because
 * {@link CommandLoader}'s fields have no public setters - only package-private access.
 */
public final class CommandTestSupport {

	private CommandTestSupport() {
	}

	public static <T extends Command> T create(Class<T> commandClass, DatabaseConnection db, CapturingShellConsole console) {
		return create(commandClass, db, console, TestDatabaseConnections.defaultConsoleSettings());
	}

	public static <T extends Command> T create(Class<T> commandClass, CapturingShellConsole console) {
		return create(commandClass, null, console);
	}

	public static <T extends Command> T create(Class<T> commandClass, DatabaseConnection db, CapturingShellConsole console, ConsoleSettings consoleSettings) {
		try {
			T cmd = commandClass.getDeclaredConstructor().newInstance();
			ConsolePrinter printer = new ConsolePrinter();
			printer.shellConsole = console;

			cmd.setConsole(console);
			cmd.setShellConsolePrinter(printer);
			cmd.setSqlDatabase(db);
			cmd.setConsoleSettings(consoleSettings);
			cmd.setListMode(false);
			cmd.setExtractMode(false);
			// SELECT-shaped results (executeSelectQuery -> QueryExtractorToScreen) print through
			// DatabaseConnection's own cmdLineConsole, not through shellConsolePrinter - see
			// DatabaseConnection.executeSelectQuery().
			if (db != null) {
				db.setCmdLineConsole(console);
			}
			return cmd;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to instantiate " + commandClass.getName(), e);
		}
	}

	/**
	 * A {@link CommandInterpreter} wired with just enough of the command-registry chain
	 * ({@code CommandList} + {@code CommandLoader}) for {@code CommandHelp} to run - the only command
	 * that currently reaches through {@code getConsoleCommandInterpreter().getCommands()}.
	 */
	public static CommandInterpreter createCommandInterpreter(ConsoleSettings consoleSettings, CapturingShellConsole console) {
		CommandLoader loader = new CommandLoader();
		loader.shellConsoleSettings = consoleSettings;
		loader.console = console;

		CommandList commandList = new CommandList();
		commandList.setConsole(console);
		commandList.setConsoleCommandLoader(loader);
		commandList.setShellConsoleSettings(consoleSettings);
		commandList.setShellConsolePrinter(new ConsolePrinter());

		CommandInterpreter interpreter = new CommandInterpreter();
		interpreter.setCommands(commandList);
		return interpreter;
	}

	/**
	 * A {@link CommandInterpreter} able to actually dispatch and run a query end to end
	 * ({@code executeCommand()}) - the fuller wiring {@code CommandExternalFile}'s script-statement
	 * loop (and any other command that runs another query through the interpreter rather than calling
	 * {@code sqlDatabase} directly) needs, on top of {@link #createCommandInterpreter(ConsoleSettings, CapturingShellConsole)}:
	 * {@code sqlDatabase} wired, and {@code CommandDefault} registered as the fallback command so plain
	 * SQL statements (not matching any keyword) still run.
	 */
	public static CommandInterpreter createCommandInterpreter(ConsoleSettings consoleSettings, CapturingShellConsole console, DatabaseConnection db) {
		CommandInterpreter interpreter = createCommandInterpreter(consoleSettings, console);
		interpreter.shellConsoleSettings = consoleSettings;
		interpreter.console = console;
		interpreter.shellConsolePrinter = interpreter.getCommands().getShellConsolePrinter();
		interpreter.sqlDatabase = db;
		interpreter.getCommands().put(ConsoleSettings.defaultCommandName, create(CommandDefault.class, db, console, consoleSettings));
		return interpreter;
	}

	/**
	 * Wires {@code cmd} with a real, file-backed vault (see
	 * {@link TestDatabaseConnections#newFileBackedVault}) containing one active connection
	 * {@code platformId} with environment {@code environment}, and sets it as the command's current
	 * connection - what LIB/SCRIPT environment-scoping tests need
	 * ({@code CommandUtils.currentEnvironment(platform, databaseConnectionsVault)}). Leaves the
	 * connection's instance unset - see {@link #wireInstanceAndEnvironment} for both dimensions at once.
	 */
	public static void wireEnvironment(Command cmd, String platformId, String environment) throws BroadSQLException {
		wireInstanceAndEnvironment(cmd, platformId, null, environment);
	}

	/**
	 * Same as {@link #wireEnvironment}, also setting the connection's instance - what LIB/SCRIPT
	 * instance-scoping tests need ({@code CommandUtils.currentInstance(platform, databaseConnectionsVault)}).
	 *
	 * <p>{@code environment}, if blank, is substituted with a placeholder ({@code "TEST"}) rather than
	 * passed through - Environment is mandatory on a saved Connection (docs/CONNECTION_MODEL.md), so
	 * {@code saveDatabaseDefinition} would otherwise reject it outright. Callers that pass {@code null}
	 * here are exclusively instance-scoping tests whose fixtures carry no {@code @environment} tag at
	 * all, so the substituted value has no bearing on what they actually exercise. Whatever Environment
	 * value ends up being used is also registered as a real Environment first if it isn't already (the
	 * built-in {@code LOCAL} is pre-seeded by {@link TestDatabaseConnections#newFileBackedVault}, but
	 * {@code "TEST"} and any other custom value a caller passes are not) - a Connection's Environment
	 * must reference one that actually exists. A non-blank {@code instance} is likewise registered as a
	 * real Database Group first, for the same reason.
	 */
	public static void wireInstanceAndEnvironment(Command cmd, String platformId, String instance, String environment) throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		if (StringUtils.isNotBlank(instance)) {
			vault.saveGroup(instance, instance);
		}
		String resolvedEnvironment = StringUtils.defaultIfBlank(environment, "TEST");
		if (!vault.getEnvironments().contains(resolvedEnvironment)) {
			vault.saveEnvironment(new EnvironmentDefinition(resolvedEnvironment, resolvedEnvironment, false, null, DatabaseDefinition.STATUS_ACTIVE));
		}
		DatabaseDefinition definition = new DatabaseDefinition(platformId);
		definition.setDbType("H2");
		definition.setUrl("jdbc:h2:mem:" + platformId);
		definition.setStatus(DatabaseDefinition.STATUS_ACTIVE);
		definition.setDatabaseGroup(instance);
		definition.setEnvironment(resolvedEnvironment);
		vault.saveDatabaseDefinition(definition);
		vault.load();
		cmd.setDatabaseConnectionsVault(vault);
		cmd.setPlatform(platformId);
	}
}
