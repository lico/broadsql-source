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
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;

@ComponentScan(basePackages = { "com.upandcoding.broadsql" })
@Configuration
@Import({ SpringPropertiesConfig.class
})
@PropertySources(@PropertySource("file:${bsql.settings}"))
public class SpringMainConfig {

	/**
	 * SPRINT XT02A corrective pass (docs/TECHNICAL_CHANGE.md, 16/09/2026): deliberately back to
	 * Spring's default, strict placeholder behavior - an earlier version of this method called
	 * {@code setIgnoreUnresolvablePlaceholders(true)} globally to let {@code apiproxyusername}/
	 * {@code apiproxypassword} hold a literal {@code ${ENV:NAME}} reference without crashing startup,
	 * but that weakened placeholder validation for every other INI key application-wide - a genuine
	 * typo/misconfiguration elsewhere would then silently survive instead of failing loudly at
	 * startup, exactly like it always used to. The narrower fix lives in {@link ConsoleSettings}
	 * instead: {@code apiproxyusername}/{@code apiproxypassword} are no longer bound via {@code @Value}
	 * at all (so Spring's placeholder resolver never sees their raw text in the first place) - they are
	 * read directly from the INI file, bypassing Spring entirely, in
	 * {@link ConsoleSettings#getApiProxyUsername()}/{@link ConsoleSettings#getApiProxyPassword()}. Every
	 * other {@code @Value}-bound key in this application keeps exactly the strict validation it always
	 * had.
	 */
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

	/**
	 * SPRINT XT02 (Universal API Client) - the API/HTTP client catalog, sharing the same physical CDF
	 * file and master password as {@link #getDatabaseConnectionsVault()} (docs/SPRINT XT02 - Universal
	 * API Client.md, section 13.2) rather than a second encrypted store. Its password is set alongside
	 * the database vault's own wherever that happens (see {@code CommandSetMasterPassword}/
	 * {@code CommandSetConnectionPassword}).
	 */
	@Bean(name = "apiDefinitionsVault")
	public ApiDefinitionsVault getApiDefinitionsVault() {
		ApiDefinitionsVault apiVault = new ApiDefinitionsVault();
		apiVault.setFileName(getShellConsoleSettings().getProtectedPlatformsFileName());
		return apiVault;
	}

	/**
	 * The current connection. Its shutdown on EXIT belongs to {@code CommandInterpreter.closeConnectionOnExit()};
	 * {@code destroyMethod = "close"} (what Spring also inferred when it was not declared) is only a backstop for an
	 * abnormal end of {@code CommandInterpreter.run()}, when {@code BroadSQL.main} closes the context without the
	 * interpreter having closed the connection. After a normal EXIT it finds the connection already closed and
	 * does nothing, {@link DatabaseConnection#close(boolean)} being idempotent.
	 */
	@Bean(name = "sqlDatabase", destroyMethod = "close")
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
		commandsList.setApiDefinitionsVault(getApiDefinitionsVault());
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
