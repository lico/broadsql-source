package com.upandcoding.broadsql.controller.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import com.upandcoding.broadsql.controller.StartupCommandLineParser;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.Session;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.commands.CommandLoader;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.controller.shell.output.ConsoleLogger;
import com.upandcoding.broadsql.controller.shell.output.ConsolePrinter;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;

@ComponentScan(basePackages = { "com.upandcoding.broadsql" })
@Configuration
@Import({ SpringPropertiesConfig.class
})
@PropertySources(@PropertySource("file:${bsql.settings}"))
public class SpringMainConfig {

	@Bean
	public static PropertySourcesPlaceholderConfigurer placeHolderConfigurer() {
		return new PropertySourcesPlaceholderConfigurer();
	}

	public static final String prompt = SpringPropertiesConfig.PROMPT_TEXT;

	@Value("${bsql.settings}")
	String iniFileName;

	@Bean(name = "databaseConnectionsVault")
	public DatabaseDefinitionsVault getDatabaseConnectionsVault() {
		DatabaseDefinitionsVault connections = new DatabaseDefinitionsVault();
		connections.setFileName(getShellConsoleSettings().getProtectedPlatformsFileName());
		return connections;
	}

	@Bean(name = "sqlDatabase")
	public DatabaseConnection getSqlDatabase() throws BroadSQLException {
		DatabaseConnection sqlDatabase = new DatabaseConnection();
		return sqlDatabase;
	}

	@Bean(name = "session")
	public Session getSession() throws BroadSQLException {
		Session session = new Session(getDatabaseConnectionsVault(), getShellConsoleSettings());
		return session;
	}

	@Bean(name = "shellConsole")
	public ShellConsole getShellConsole() {
		ShellConsole console = new ShellConsole();
		console.setPrompt(prompt);
		return console;
	}

	@Bean(name = "consoleCommandInterpreter")
	public CommandInterpreter getShellConsoleCommandInterpreter() throws BroadSQLException {
		CommandInterpreter consoleMgr = new CommandInterpreter();
		consoleMgr.setSession(getSession());
		return consoleMgr;
	}

	@Bean
	public CommandList getCommandsList() throws BroadSQLException {
		CommandList commandsList = new CommandList();
		commandsList.setConsole(getShellConsole());
		commandsList.setSession(getSession());
		commandsList.setConsoleCommandLoader(getShellConsoleCommandLoader());
		commandsList.setShellConsoleSettings(getShellConsoleSettings());
		commandsList.setShellConsolePrinter(getShellConsoleUtils());
		commandsList.setShellConsoleLogger(getShellConsoleLogger());
		commandsList.setDatabaseConnectionsVault(getDatabaseConnectionsVault());
		return commandsList;
	}

	@Bean(name = "consoleCommandLoader")
	public CommandLoader getShellConsoleCommandLoader() throws BroadSQLException {
		CommandLoader consoleMgr = new CommandLoader();
		return consoleMgr;
	}

	@Bean(name = "consoleLogger")
	public ConsoleLogger getShellConsoleLogger() throws BroadSQLException {
		ConsoleLogger consoleLogger = new ConsoleLogger();
		return consoleLogger;
	}

	@Bean(name = "consoleSettings")
	public ConsoleSettings getShellConsoleSettings() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setIniFileName(iniFileName);
		return settings;
	}

	@Bean(name = "consoleUtils")
	public ConsolePrinter getShellConsoleUtils() {
		ConsolePrinter consoleUtils = new ConsolePrinter();
		return consoleUtils;
	}

	@Bean(name = "cmdLineParser")
	public StartupCommandLineParser cmd() {
		StartupCommandLineParser cmd = new StartupCommandLineParser();
		cmd.addAuthorizedParameter("to", "<connection>\tSpecify a valid SQL connection identifier");
		return cmd;
	}

}
