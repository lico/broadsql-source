package com.upandcoding.broadsql.controller.shell.commands;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.Session;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.controller.shell.output.ConsoleLogger;
import com.upandcoding.broadsql.controller.shell.output.ConsolePrinter;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;

/*
 * Note (2018.02.22): Command class can not used autowired fields.
 * Autowired fields are correctly inherited by the descendants of the abstract class.
 * The reason why autowired fields are not used is because that commands are not created by SPRING.
 * Commands are created with constructor (new).
 */
public abstract class Command {

	protected ConsolePrinter shellConsolePrinter;

	protected DatabaseConnection sqlDatabase;

	protected ShellConsole console;

	Session session;

	DatabaseDefinitionsVault databaseConnectionsVault;

	CommandInterpreter consoleCommandInterpreter;

	protected ConsoleLogger consoleLogger; // consoleLogger;

	protected ConsoleSettings consoleSettings; // consoleSettings;

	protected String platform = null;
	protected boolean extractMode = false;
	protected String extractFileName = null;
	protected boolean extractAppendToFile = false;
	protected boolean printToLogFile = false; // writes the console input/output to a log file
	public boolean debugMode = false;
	public String platformsFileName;
	private char separator = '\t';
	protected String lastSQLQuery = null;
	protected boolean hidden = false;

	public boolean listMode = false;

	protected String[] keywords;

	protected Command() {
	}

	// Lets most commands declare their keywords in one line (super("SHOW TABLES", "SH TA", "SHTA"))
	// instead of a static field plus a getKeywords() override. Kept alongside the no-arg
	// constructor for custom extension commands (loaded from user JARs, see CommandLoader) that
	// still declare their own keywords field and override getKeywords() themselves.
	protected Command(String... keywords) {
		this.keywords = keywords;
	}

	public ConsolePrinter getShellConsolePrinter() {
		return shellConsolePrinter;
	}

	public void setShellConsolePrinter(ConsolePrinter shellConsolePrinter) {
		this.shellConsolePrinter = shellConsolePrinter;
	}

	public DatabaseConnection getSqlDatabase() {
		return sqlDatabase;
	}

	public void setSqlDatabase(DatabaseConnection sqlDatabase) {
		this.sqlDatabase = sqlDatabase;
	}

	public ShellConsole getConsole() {
		return console;
	}

	public void setConsole(ShellConsole shellConsole) {
		this.console = shellConsole;
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

	public CommandInterpreter getConsoleCommandInterpreter() {
		return consoleCommandInterpreter;
	}

	public void setConsoleCommandInterpreter(CommandInterpreter consoleCommandInterpreter) {
		this.consoleCommandInterpreter = consoleCommandInterpreter;
	}

	public ConsoleLogger getConsoleLogger() {
		return consoleLogger;
	}

	public void setConsoleLogger(ConsoleLogger consoleLogger) {
		this.consoleLogger = consoleLogger;
	}

	public ConsoleSettings getConsoleSettings() {
		return consoleSettings;
	}

	public void setConsoleSettings(ConsoleSettings consoleSettings) {
		this.consoleSettings = consoleSettings;
	}

	public String getPlatform() {
		return platform;
	}

	public void setPlatform(String platform) {
		this.platform = platform;
	}

	public boolean isExtractMode() {
		return extractMode;
	}

	public void setExtractMode(boolean extractMode) {
		this.extractMode = extractMode;
	}

	public String getExtractFileName() {
		return extractFileName;
	}

	public void setExtractFileName(String extractFileName) {
		this.extractFileName = extractFileName;
	}

	public boolean isExtractAppendToFile() {
		return extractAppendToFile;
	}

	public void setExtractAppendToFile(boolean extractAppendToFile) {
		this.extractAppendToFile = extractAppendToFile;
	}

	public boolean isPrintToLogFile() {
		return printToLogFile;
	}

	public void setPrintToLogFile(boolean printToLogFile) {
		this.printToLogFile = printToLogFile;
	}

	public boolean isDebugMode() {
		return debugMode;
	}

	public void setDebugMode(boolean debugMode) {
		this.debugMode = debugMode;
	}

	public String getPlatformsFileName() {
		return platformsFileName;
	}

	public void setPlatformsFileName(String platformsFileName) {
		this.platformsFileName = platformsFileName;
	}

	public char getSeparator() {
		return separator;
	}

	public void setSeparator(char separator) {
		this.separator = separator;
	}

	public String getLastSQLQuery() {
		return lastSQLQuery;
	}

	public void setLastSQLQuery(String lastSQLQuery) {
		this.lastSQLQuery = lastSQLQuery;
	}

	public boolean isHidden() {
		return hidden;
	}

	public void setHidden(boolean hidden) {
		this.hidden = hidden;
	}

	public boolean isListMode() {
		return listMode;
	}

	public void setListMode(boolean listMode) {
		this.listMode = listMode;
	}

	public String[] getKeywords() {
		return keywords;
	}

	// Equivalent to CommandUtils.getArgumentsFromQuery(query, getKeywords()), without having to
	// pass the command's own keywords back in at every call site.
	protected String[] parseArgs(String query) {
		return CommandUtils.getArgumentsFromQuery(query, keywords);
	}

	public abstract String getDescription();

	/**
	 * The full-length description shown by {@code HELP <command>} - defaults to
	 * {@link #getDescription()}. Override only when a command's one-line summary (used everywhere
	 * else, including the plain {@code HELP} command list, which must stay one line per entry) isn't
	 * enough to document the command - see {@code CommandPull}.
	 */
	public String getDetailedDescription() {
		return getDescription();
	}

	public abstract String getArguments();

	public abstract String getExamples();

	public String getSynonyms() {
		String result = "";
		if (this.getKeywords() != null && this.getKeywords().length > 1) {
			for (int i = 1; i < this.getKeywords().length; i++) {
				if (i > 1) {
					result = result + ", ";
				}
				result = result + this.getKeywords()[i];
			}
		}
		return result;
	}

	public void displayHelp() {
		console.print(getDescription());
	}

	public String displayDetailedHelp() {
		final String sep = "    ";
		StringBuilder bs = new StringBuilder();
		bs.append(this.getKeywords()[0] + " command:\n");
		bs.append(sep + getDetailedDescription() + "\n");
		bs.append(sep + "Synonyms: " + getSynonyms() + "\n");
		if (StringUtils.isNotBlank(getArguments())) {
			bs.append(sep + "Arguments: " + getArguments() + "\n");
		} else {
			bs.append(sep + "Arguments: none\n");
		}
		if (StringUtils.isNotBlank(getExamples())) {
			bs.append(sep + "Examples: " + getExamples() + "\n");
		}
		return (bs.toString());
	}

	public abstract void execute(String query) throws BroadSQLException;

}
