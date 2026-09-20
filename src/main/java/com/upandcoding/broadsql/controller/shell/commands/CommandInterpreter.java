package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.Session;
import com.upandcoding.broadsql.controller.shell.output.ConsoleLogger;
import com.upandcoding.broadsql.controller.shell.output.ConsolePrinter;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;

import sun.misc.Signal;

public class CommandInterpreter {

	private final static Logger log = LoggerFactory.getLogger(CommandInterpreter.class);

	@Autowired
	ConsoleSettings shellConsoleSettings;

	@Autowired
	ShellConsole console;

	@Autowired
	ConsolePrinter shellConsolePrinter;

	@Autowired
	ConsoleLogger shellConsoleLogger;

	@Autowired
	DatabaseConnection sqlDatabase;

	@Autowired
	Session session;

	@Autowired
	DatabaseDefinitionsVault databaseConnectionsVault;

	@Autowired
	CommandList commands;

	/*
	 @Autowired
	 Session session;
	 */

	private String platform = null;
	// private String platformsFileName = null;
	// private char separator = ConsoleSettings.defaultSeparator;
	private boolean extractMode = false; // Should we extract to a file : true/false
	private String extractFileName = null; // File name where we extract results
	private boolean extractAppendToFile = false; // If extract to a file, should we overwrite or append. False means overwrite.
	private boolean showToScreen = true;
	private boolean listMode = false;
	private String query = null;
	private String lastSQLQuery = null;
	// private String qryFileName = null; // for executing queries stored in a file
	private static final String CONS_TERM = ConsoleSettings.CMD_EXIT;
	// private String rootFolder = ConsoleSettings.extractFolderName;
	// private String rootFileName = ConsoleSettings.extractDefaultFileName;
	// private boolean printToLogFile = false; // copies the console input/output to
	// a log file
	private boolean debugMode = false;

	// Thread currently executing a command, if any - the CTRL+C handler cancels it instead of
	// terminating the JVM. See docs/TODO.md ("Ameliorations de la GUI").
	private volatile Thread currentCommandThread;

	// Set by the CTRL+C handler when it fires while idle at the prompt (no command running) - lets
	// run() tell "CTRL+C at idle" apart from a genuine end-of-input on the blocking console read.
	private volatile boolean idleInterruptReceived;

	// private static SQLDatabase currentDatabase = null;
	// private ConsoleCmdLineGUI console;

	public CommandInterpreter() {
	}

	public CommandInterpreter(String sPlatform) throws BroadSQLException {
		if (StringUtils.isNotBlank(sPlatform)) {
			this.platform = sPlatform;
			if (console == null) {
				log.debug("Erreur console vide");
			}
			console.setPrompt(this.platform + "> ");
			console.setPrintToLogFile(shellConsoleSettings.isLogDefaultActivated());
		} else {
			throw new BroadSQLException("Selected platform is blank");
		}
	}

	public ShellConsole getConsole() {
		return console;
	}

	public void setConsole(ShellConsole console) {
		this.console = console;
	}

	public Session getSession() {
		return session;
	}

	public void setSession(Session session) {
		this.session = session;
	}

	public boolean isExtractMode() {
		return extractMode;
	}

	public void setExtractMode(boolean extractMode) {
		this.extractMode = extractMode;
	}

	public String getPlatform() {
		return platform;
	}

	public void setPlatform(String platform) {
		this.platform = platform;
	}

	public String getQuery() {
		return query;
	}

	public void setQuery(String query) {
		this.query = query;
	}

	public CommandList getCommands() {
		return commands;
	}

	public void setCommands(CommandList commands) {
		this.commands = commands;
	}

	/**
	 * Execute a command actually
	 * 
	 * @throws BroadSQLException
	 */
	public void executeCommand() throws BroadSQLException {

		if (StringUtils.isNotBlank(this.query)) {

			// Platform this command started with - compared against the platform once the command
			// has run, at the bottom of this method, to detect a CONNECT (typed directly, or nested
			// inside an @file/LIB RUN script) and re-run the new platform's login script. See
			// docs/TODO.md item 7.
			String platformBeforeExecution = this.platform;

			// Check the connection is still open. If it was silently dropped (e.g. an Oracle session
			// killed by an IDLE_TIME profile limit, or a dropped idle TCP connection) and gets
			// transparently reconnected here, the new physical session starts without whatever
			// USERS_SCRIPT had applied (e.g. ALTER SESSION) - re-run it right away, before the
			// command that triggered this check executes. See docs/TODO.md item 7.
			if (sqlDatabase == null || !sqlDatabase.isConnected()) {
				console.println("Connection to '" + this.platform + "' was lost, reconnecting...");
				sqlDatabase.connect();
				runLoginScriptsPreservingState();
			}

			// Retrieve the command from the input query
			String uQuery = this.query.toUpperCase();
			//System.out.println("executing: " + this.query);
			Command command = commands.getCommandClassFromName(uQuery);
			/*
			if (command==null) {
				System.out.println("no command found");
			} else {
				System.out.println("Command: " + command.getKeywords()[0]);
			}
			*/

			// If no command matches, then use default command (CommandDefault)
			if (command == null && shellConsoleSettings.processUnknownCommands == true) {
				command = commands.get(ConsoleSettings.defaultCommandName);
			}

			if (command != null) {
				try {

					// ANTE EXECUTION of the command
					// Set the command attributes

					/*
					command.setShellConsole(console);
					command.setSession(session);
					command.setConsoleLogger(shellConsoleLogger);
					command.setConsoleSettings(shellConsoleSettings);
					command.setDatabaseConnectionsVault(databaseConnectionsVault);
					command.setShellConsolePrinter(shellConsolePrinter);
					*/

					command.setConsoleCommandInterpreter(this);

					command.setSqlDatabase(sqlDatabase);
					command.setPlatform(this.platform);
					command.setDebugMode(this.debugMode);
					console.setPrintToLogFile(command.isPrintToLogFile());
					if (SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(sqlDatabase.getPlatform().getId())) {
						console.setPrintToLogFile(false);
					} else {
						console.setPrintToLogFile(shellConsoleSettings.isLogDefaultActivated());
					}
					command.setListMode(listMode);
					command.setPlatformsFileName(shellConsoleSettings.getProtectedPlatformsFileName());
					command.setExtractMode(this.extractMode);
					if (this.extractMode) {
						command.setExtractFileName(this.extractFileName);
						command.setExtractAppendToFile(this.extractAppendToFile);
					} else {
						command.setExtractFileName(null);
					}
					command.setSeparator(shellConsoleSettings.getDefaultSeparator());
					command.setLastSQLQuery(lastSQLQuery);
					console.setWindowsTitle(this.platform, this.query, false);

					// Replace macros in the query with actual values
					this.query = CommandUtils.substituteMacros(this.query);

					// ACTUAL EXECUTION of the command, on a dedicated thread so that CTRL+C can cancel
					// just this command (without killing BroadSQL or closing the connection).
					// See docs/TODO.md ("Ameliorations de la GUI").
					final Command commandToRun = command;
					final AtomicReference<BroadSQLException> executionError = new AtomicReference<>();
					CommandCancellation.reset();
					Thread worker = new Thread(() -> {
						try {
							commandToRun.execute(this.query);
						} catch (BroadSQLException ex) {
							executionError.set(ex);
						} catch (RuntimeException ex) {
							executionError.set(new BroadSQLException(ex));
						}
					}, "BroadSQL-command");
					currentCommandThread = worker;
					worker.start();
					try {
						worker.join();
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
					} finally {
						currentCommandThread = null;
					}

					BroadSQLException error = executionError.get();
					if (CommandCancellation.isRequested()) {
						// The user cancelled the command with CTRL+C. Some commands print their own
						// message when they catch the resulting CommandInterruptedException (e.g.
						// "ERROR: command interrupted"), others let it propagate to us - either way,
						// clean up here and return to the prompt without closing the connection.
						String partialFile = sqlDatabase.getFileName();
						if (partialFile != null) {
							new File(partialFile).delete();
						}
						sqlDatabase.setFileName(null);
						this.extractMode = false;
						this.extractFileName = null;
						return;
					}
					if (error != null) {
						throw error;
					}

					// POST EXECUTION of the command
					sqlDatabase = command.getSqlDatabase();
					this.platform = command.getPlatform();
					console.setWindowsTitle(this.platform, "", true);
					this.debugMode = command.isDebugMode();
					command.setPrintToLogFile(shellConsoleSettings.isLogDefaultActivated());
					this.listMode = command.isListMode();
					this.extractFileName = command.getExtractFileName();
					this.extractMode = command.isExtractMode();
					if (this.extractMode) {
						// After user specified the file for data export
						this.showToScreen = false;
						if (this.extractFileName != null) {
							sqlDatabase.setFileName(extractFileName);
						}
					} else {
						// After user executed actual export to external file
						this.showToScreen = true;
						sqlDatabase.setFileName(null);
					}
					this.extractAppendToFile = false; // by default we don't append
					shellConsoleSettings.setDefaultSeparator(command.getSeparator());
					this.lastSQLQuery = command.getLastSQLQuery();

					// This command switched platform (CONNECT, typed directly or run from inside an
					// @file/LIB RUN script) - re-run the new platform's login script here, once, in
					// the one place every command execution passes through, instead of pattern-
					// matching the raw typed command as before. See docs/TODO.md item 7.
					if (!Objects.equals(platformBeforeExecution, this.platform) && sqlDatabase.isConnected()) {
						runLoginScriptsPreservingState();
					}

				} catch (IllegalArgumentException | SecurityException ie) {
					throw new BroadSQLException(ie);
				}
			} else {
				console.error("Unknown Command: " + query);
			}
		}
	}

	/**
	 * Attach a hook to capture CTRL+C events Objective is to properly terminate
	 * open db connections
	 */
	public void attachShutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				try {
					if (sqlDatabase != null && sqlDatabase.isConnected()) {
						sqlDatabase.close();
					}
				} catch (BroadSQLException se) {
					se.printStackTrace();
				}
			}
		});
	}

	/**
	 * Installs a CTRL+C (SIGINT) handler. If a command is currently executing, it is cancelled: its
	 * current Statement is cancelled at the JDBC level and a cooperative flag is set for its
	 * extraction loop to notice - deliberately NOT Thread.interrupt(), which would risk closing the
	 * driver's own interruptible file channels (observed corrupting an H2 connection). If no command
	 * is executing (idle at the prompt), CTRL+C does nothing - EXIT remains the only way to quit. See
	 * docs/TODO.md ("Ameliorations de la GUI") and docs/TECHNICAL_CHANGE.md.
	 */
	private void attachInterruptHandler() {
		Signal.handle(new Signal("INT"), signal -> {
			if (currentCommandThread != null) {
				sqlDatabase.cancelCurrentStatement();
				CommandCancellation.request();
			} else {
				idleInterruptReceived = true;
			}
		});
	}

	/**
	 * Execute current user's connection script
	 *
	 * @throws BroadSQLException
	 */
	private void executeUserScripts() throws BroadSQLException {
		List<String> userCmds = session.getCurrentUserLoginScript(platform, sqlDatabase);
		if (userCmds != null && !userCmds.isEmpty()) {
			// console.println("Running user's login commands ...");
			for (String command : userCmds) {
				console.println(command);
				this.query = command;
				executeCommand();
			}
			// console.println("... user's login commands done");
		}
	}

	/**
	 * Runs {@link #executeUserScripts()} from inside an in-progress {@code executeCommand()} call
	 * (see the two call sites below) - unlike the top-level loop in {@link #run()}, which only ever
	 * calls {@code executeUserScripts()} between commands, these two call sites are nested one level
	 * inside the very {@code executeCommand()} invocation whose command triggered them. Each script
	 * line is itself dispatched through {@code executeCommand()}, which mutates several interpreter
	 * fields consumed later in - or right after - the outer invocation (see docs/TODO.md item 7).
	 * Snapshotting and restoring them here makes the login-script detour invisible to the command
	 * that triggered it, exactly as if it had never happened - only the platform itself (deliberately
	 * not restored) is allowed to carry over, since a platform change is the very thing that can
	 * legitimately trigger this method.
	 *
	 * @throws BroadSQLException
	 */
	private void runLoginScriptsPreservingState() throws BroadSQLException {
		String savedQuery = this.query;
		boolean savedDebugMode = this.debugMode;
		boolean savedListMode = this.listMode;
		boolean savedExtractMode = this.extractMode;
		String savedExtractFileName = this.extractFileName;
		boolean savedExtractAppendToFile = this.extractAppendToFile;
		String savedLastSQLQuery = this.lastSQLQuery;
		try {
			executeUserScripts();
		} finally {
			this.query = savedQuery;
			this.debugMode = savedDebugMode;
			this.listMode = savedListMode;
			this.extractMode = savedExtractMode;
			this.extractFileName = savedExtractFileName;
			this.extractAppendToFile = savedExtractAppendToFile;
			this.lastSQLQuery = savedLastSQLQuery;
		}
	}

	/**
	 * Reads input from the console until a line that ends with a semicolon (;) is
	 * entered In that case, all the entered lines are assembled into a query and
	 * sent to the function executeCommand(String query) for execution
	 * 
	 * @author UpAndCoding.com, 5 May 2011
	 */
	public void run() throws BroadSQLException {
		String line;
		boolean cmdExecuted;

		attachShutDownHook();
		attachInterruptHandler();

		// Load avalaible commands
		//System.out.println("Nbr classes: " + this.size());
		commands.getConsoleCommands();

		// Test the DB and in case of problem, revert platform to CDF_ID
		try {
			StringBuffer bsTest = sqlDatabase.testConnectionToExistingPlatform(platform);
		} catch (BroadSQLException se) {
			console.error("Unable to open '" + platform + "': " + se.getLocalizedMessage());
			platform = SpringPropertiesConfig.CDF_ID;
		}

		// Prepare objects
		sqlDatabase.setPlatformCode(platform);
		sqlDatabase.setToScreen(false);
		console.setPrompt(this.platform + "> ");
		sqlDatabase.setCmdLineConsole(console);

		// Open the database with current platform
		session.openDatabase(platform);

		console.println("Connected to " + platform + " using JDBC driver: " + sqlDatabase.getDbDriver());
		if (sqlDatabase.getDbName().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_Oracle)) {
			console.println(sqlDatabase.getDbVersion());
			console.println("");
		} else {
			console.println("Database: " + sqlDatabase.getDbName() + " " + sqlDatabase.getDbVersion());
			console.println("");
		}

		if (sqlDatabase.isConnected()) {

			// Sets default settings
			sqlDatabase.setMaxRowsOnScreen(shellConsoleSettings.getMaxRowsOnScreen());
			sqlDatabase.setSep(shellConsoleSettings.getDefaultSeparator());
			sqlDatabase.setToScreen(this.showToScreen);
			sqlDatabase.setMaxRowsOnScreen(shellConsoleSettings.getMaxRowsOnScreen());
			sqlDatabase.setAutoCommit(shellConsoleSettings.isAutoCommit());

			// Executes login scripts
			executeUserScripts();

			// GUI loop
			StringBuilder bs = new StringBuilder();
			while (true) {
				line = console.readLine();
				if (line == null) {
					// A blocking console read can return null either on genuine end-of-input
					// (piped/redirected stdin closed) or because CTRL+C was pressed while idle on
					// some platforms/terminals. Only the latter should NOT exit BroadSQL - EXIT is
					// the intended way to quit. See docs/TODO.md ("Ameliorations de la GUI").
					if (idleInterruptReceived) {
						idleInterruptReceived = false;
						continue;
					}
					break;
				}
				if (line.startsWith(CONS_TERM)) {
					break;
				}

				// Re-authenticated after end of activity period
				if (!session.isActive()) {
					session.authenticate(console);
				}

				// Write to the log for plaftorms other than CDF, except if log is de-activated
				if (shellConsoleSettings.isLogDefaultActivated()
						&& !SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(sqlDatabase.getPlatform().getId())) {
					try {
						shellConsoleLogger.writeln(console.getPrompt() + line);
					} catch (IOException ex) {
						shellConsoleSettings.setLogDefaultActivated(false);
						log.error(ex.getLocalizedMessage());
						console.error("Unable to write to log file");
					}
				}

				cmdExecuted = false;
				line = line.trim();

				if ("//".equals(line)) {
					// Alias for SHOW QUERY: display-only counterpart to / (below), shows the last saved
					// command collapsed onto a single line without executing it. Must be intercepted
					// here, before the "/" branch, since "//" would otherwise also match
					// line.startsWith("/") and be replayed instead of just shown. Delegates to the
					// registered SHOW QUERY command instance rather than keeping its own copy of the
					// display logic - see docs/TODO.md item 10.
					Command showQuery = commands.get("SHOW QUERY");
					if (showQuery != null) {
						showQuery.setLastSQLQuery(this.lastSQLQuery);
						showQuery.execute(line);
					}

				} else if ("/".equals(line) || (line.startsWith("/") && !line.startsWith("/*"))) {
					// If command is /, the re-execute last saved command
					if (this.lastSQLQuery != null && !"".equals(this.lastSQLQuery.trim())) {
						this.query = this.lastSQLQuery;
						executeCommand();
						cmdExecuted = true;
					} else {
						console.println("No command in memory\n");
					}

				} else if (line.endsWith("\\;")) {
					// Lines ending with \; are not considered as termination of command. Semicolon
					// is escaped
					if (!line.startsWith("--")) {
						bs.append(StringUtils.substringBefore(line, "\\;"));
						bs.append(";");
						bs.append(" ");
					}

				} else if (line.endsWith(";") && !line.endsWith("\\;")) {
					// Execution of the command. After a semicolon, the command is complete
					try {

						// If not a comment, the command is whatever before the final semicolon
						if (!line.startsWith("--")) {
							bs.append(StringUtils.substringBeforeLast(line, ";"));
							bs.append(" ");
						}

						// Get the actual query from the stringbuffer (contains multiple lines)
						this.query = bs.toString();
						String tmp = query.toUpperCase();
						if (this.query != null) {
							this.query = this.query.trim();
						}

						// Execute command actually
						executeCommand();

						// Save current query as last executed query
						// 2019-06-04 TODO: why only INSERT, UPDATE, DELETE? Should contain other
						// words as well?
						if (tmp.startsWith("SELECT") || tmp.startsWith("INSERT") || tmp.startsWith("UPDATE")
								|| tmp.startsWith("DELETE")) {
							lastSQLQuery = this.query;
						}
						// Re-running the login script after a CONNECT is handled inside
						// executeCommand() itself now (see docs/TODO.md item 7) - it used to be
						// pattern-matched here against the raw typed line, which missed a CONNECT
						// run from inside an @file/LIB RUN script.

					} catch (BroadSQLException exc) {
						console.error(exc.getLocalizedMessage());
						console.println("");
					}
					cmdExecuted = true;
					bs = new StringBuilder();
				} else {
					if (!line.startsWith("--")) {
						bs.append(line);
						bs.append(" ");
					}
				}

				if (cmdExecuted && query != null && !query.toUpperCase().startsWith("EXT")
						&& !query.toUpperCase().startsWith("EXP")) {
					this.extractMode = false;
					this.extractFileName = null;
				}
			}

			// CLosing the connection
			if (sqlDatabase.isConnected()) {
				sqlDatabase.close();
			}
			sqlDatabase = null;
		}
	}

}
