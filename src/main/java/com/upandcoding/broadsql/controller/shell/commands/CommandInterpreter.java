package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.Session;
import com.upandcoding.broadsql.controller.shell.ShellPromptBuilder;
import com.upandcoding.broadsql.controller.shell.completion.JdbcMetadataCompletionCacheHolder;
import com.upandcoding.broadsql.controller.shell.completion.PendingStatementBufferHolder;
import com.upandcoding.broadsql.controller.shell.output.ConsoleLogger;
import com.upandcoding.broadsql.controller.shell.output.ConsolePrinter;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptContextStack;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptVariables;
import com.upandcoding.broadsql.controller.shell.scripts.StatementSplitter;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.LastCopyableResultHolder;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

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
	// Package-private (not private) so tests can seed it directly without a full executeCommand() round
	// trip - see TestCommandInterpreterCrossEnvironmentRerun.
	String lastSQLQuery = null;
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

	// SPRINT 1909S: the Scripts currently executing (ScriptExecutor pushes one frame per Script, so a
	// Script calling another Script nests correctly and cycles/over-deep chains are refused). LOAD
	// (SPRINT 0912B section 4.4) reads isRunningInsideScript() to decide whether it may prompt for an
	// interactive confirmation at all - a script's lines run through this exact same executeCommand()
	// path, so a prompt here would silently block waiting on a live keystroke instead of failing
	// clearly, defeating unattended/scripted use.
	private final ScriptContextStack scriptContext = new ScriptContextStack();

	public ScriptContextStack getScriptContext() {
		return scriptContext;
	}

	// SPRINT 0110A: the session's one SQL scripting variable namespace (LET, script arguments, ${name}), shared by
	// the prompt and every Script at every nesting level; never written to disk, never tied to a connection
	// (docs/06. work/SCRIPTING_VARIABLES_SPECIFICATION.md, section 7.4).
	private final ScriptVariables scriptVariables = new ScriptVariables();

	public ScriptVariables getScriptVariables() {
		return scriptVariables;
	}

	/** The session's current SQL connection (the one commands receive). */
	public DatabaseConnection getSqlDatabase() {
		return sqlDatabase;
	}

	public boolean isRunningInsideScript() {
		return scriptContext.isInsideScript();
	}

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
			console.setPrompt(ShellPromptBuilder.build(this.platform));
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
					// SPRINT 0110A: routine output follows the running Script's OUTPUT mode, on whichever console
					// prints it (the Editor redirects them all to its capture)
					installRoutineOutputSuppressor(console);
					installRoutineOutputSuppressor(command.getConsole());
					installRoutineOutputSuppressor(sqlDatabase.getCmdLineConsole());
					console.setWindowsTitle(this.platform, this.query, false);

					// Replace macros in the query with actual values
					this.query = CommandUtils.substituteMacros(this.query);

					// ACTUAL EXECUTION of the command, on a dedicated thread so that CTRL+C can cancel
					// just this command (without killing BroadSQL or closing the connection).
					// See docs/TODO.md ("Ameliorations de la GUI").
					final Command commandToRun = command;
					final AtomicReference<BroadSQLException> executionError = new AtomicReference<>();
					CommandCancellation.reset();
					// SPRINT 0110A: the worker inherits the diagnostic context (the Run ID of a Script run), so
					// application-log entries written while the statement runs can be correlated
					final Map<String, String> diagnosticContext = MDC.getCopyOfContextMap();
					Thread worker = new Thread(() -> {
						if (diagnosticContext != null) {
							MDC.setContextMap(diagnosticContext);
						}
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
					if (!Objects.equals(platformBeforeExecution, this.platform)) {
						// SPRINT 0917-02, section 26: a connection change invalidates TAB completion's
						// JDBC metadata cache for the platform just left - never let stale schema/table/
						// column names from a previous connection surface after switching away from it.
						JdbcMetadataCompletionCacheHolder.get().invalidate(platformBeforeExecution);
						if (sqlDatabase.isConnected()) {
							runLoginScriptsPreservingState();
						}
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
	 * SPRINT XT02B, section 15: splits {@code bufferedText} into individual statements the same
	 * quote/comment-aware way a {@code .bsql} script file is split ({@link StatementSplitter}, shared
	 * with {@link com.upandcoding.broadsql.controller.shell.scripts.ScriptExecutor}), then executes
	 * each in turn via {@link #executeCommand()} - so a single interactive line containing more than
	 * one {@code ;}-terminated statement (e.g. {@code SELECT 1; SELECT 2;}) runs both, in order.
	 *
	 * <p>Per explicit product decision, this <b>stops at the first statement that fails</b> - unlike a
	 * script file ({@code CommandExternalFile}), which keeps going and reports each failure in turn as
	 * it works through the rest of the file. Only the splitting logic is shared between the two
	 * callers, never the error policy: a statement typed by hand on one line is far more likely to
	 * contain a single, once-off typo than a maintained script file is, and continuing past a failed
	 * {@code UPDATE}/{@code DELETE} into further statements that assumed it succeeded would be the
	 * wrong default here.
	 *
	 * <p>Package-private rather than {@code private} so tests can drive it directly without needing a
	 * real blocking {@link #run()} console-read loop - production code only ever reaches it from
	 * {@link #run()}'s own line-accumulation loop, once a line ends with an unescaped {@code ;}.
	 */
	void executeMultiStatementLine(String bufferedText) {
		// SPRINT 0110A: CTRL+C during a statement of this line ends the line (spec section 13); a statement's
		// failure is also what it states explicitly (a script call is failed when its run is not SUCCESS, spec 18.5)
		CommandCancellation.resetRun();
		for (String stmt : StatementSplitter.split(bufferedText)) {
			this.query = stmt;
			String tmp = this.query.toUpperCase();
			// Most commands (CommandDefault, the raw-SQL fallback; most API commands such as
			// CommandRun) deliberately catch their own errors and report them via console.error(...)
			// rather than letting a BroadSQLException propagate - "BroadSQL keeps running" is
			// CommandDefault's own documented single-statement intent. A thrown-exception check alone
			// therefore cannot detect every failure here; console.wasErrorReported() is the generic
			// signal, reset before and checked after each statement, alongside the try/catch below for
			// whatever genuinely does throw (e.g. CommandInterpreter's own IllegalArgumentException/
			// SecurityException wrapping).
			console.resetErrorReported();
			ScriptContextStack.StatementState statement = scriptContext.beginStatement();
			try {
				executeCommand();
				Boolean stated = statement.getExplicitFailed();
				boolean failed = stated != null ? stated : console.wasErrorReported();

				// Save current query as last executed query
				// 2019-06-04 TODO: why only INSERT, UPDATE, DELETE? Should contain other words as well?
				if (tmp.startsWith("SELECT") || tmp.startsWith("INSERT") || tmp.startsWith("UPDATE") || tmp.startsWith("DELETE")) {
					lastSQLQuery = this.query;
					// SPRINT XT02B acceptance correction, item 6: mark this as the most recent copyable
					// result, so COPY RESULT can tell it apart from a possibly-newer RUN result - see
					// LastCopyableResultHolder's own Javadoc. Only a statement that actually succeeded may
					// become the copyable result: a failed one (which reports via console.error rather than
					// throwing, see above) must leave the previous successful result in place.
					if (!failed) {
						LastCopyableResultHolder.recordSql(this.query);
					}
				}
				// SPRINT 0917-02, section 26: a coarse cache clear after successful DDL is enough
				// (correctness over saving a few JDBC metadata calls) - only reached once console.wasErrorReported()
				// below has already confirmed this statement actually succeeded.
				if (!failed && (tmp.startsWith("CREATE") || tmp.startsWith("ALTER") || tmp.startsWith("DROP"))) {
					JdbcMetadataCompletionCacheHolder.get().invalidate(this.platform);
				}
				// Re-running the login script after a CONNECT is handled inside executeCommand() itself
				// now (see docs/TODO.md item 7) - it used to be pattern-matched here against the raw
				// typed line, which missed a CONNECT run from inside an @file/LIB RUN script.

				if (CommandCancellation.isRequested() || CommandCancellation.isRunCancelled()) {
					break; // cancelled with CTRL+C: the rest of this line is not executed
				}
				if (failed) {
					break; // the statement reported its own error without throwing - stop here too
				}
			} catch (BroadSQLException exc) {
				console.error(exc.getLocalizedMessage());
				console.println("");
				break; // stop at the first failing statement - the rest of this line is skipped
			}
		}
	}

	/**
	 * Handles a console line that is either bare {@code /} or starts with {@code /} (excluding
	 * {@code /*} comments and the {@code //} alias, both already intercepted by the caller) - dispatches
	 * to a plain rerun on the current connection, or, if exactly one token follows the {@code /}, to
	 * {@link #executeOnEnvironment(String)} for a cross-environment quick rerun.
	 *
	 * <p>Before this fix, a single prefix check (matching {@code /} or anything starting with it) meant
	 * a line like {@code / QA} fell into the plain-rerun case and silently discarded {@code QA} -
	 * see docs/TODO.md, "Cross-environment interactive investigation". Now exactly one token after
	 * {@code /} is required to name a target environment; anything else (extra tokens) is rejected with
	 * a clear error rather than being silently ignored.
	 *
	 * <p>Package-private rather than {@code private} so tests can drive it directly without needing a
	 * real blocking {@link #run()} console-read loop (see
	 * {@code TestCommandInterpreterCrossEnvironmentRerun}) - production code only ever reaches it from
	 * {@link #run()}.
	 *
	 * @return {@code true} if a rerun was actually attempted (mirrors {@code cmdExecuted} in
	 *         {@link #run()}'s loop) - {@code false} for a bare error message ("No command in memory",
	 *         invalid syntax) where nothing was executed
	 */
	boolean handleSlashRerun(String line) throws BroadSQLException {
		if ("/".equals(line)) {
			// Bare "/": re-run the last saved command on the current connection - unchanged behavior.
			if (this.lastSQLQuery != null && !"".equals(this.lastSQLQuery.trim())) {
				this.query = this.lastSQLQuery;
				executeCommand();
				return true;
			}
			console.println("No command in memory\n");
			return false;
		}

		String rest = line.substring(1).trim();
		String[] tokens = rest.isEmpty() ? new String[0] : rest.split("\\s+");
		if (tokens.length != 1) {
			console.error("Invalid syntax. Usage: / (rerun on current connection) or / <environment> "
					+ "(rerun on another environment's connection in the current Database Group)");
			return false;
		}
		if (this.lastSQLQuery == null || "".equals(this.lastSQLQuery.trim())) {
			console.println("No command in memory\n");
			return false;
		}
		this.query = this.lastSQLQuery;
		executeOnEnvironment(tokens[0]);
		return true;
	}

	/**
	 * "/ &lt;environment&gt;": executes {@link #lastSQLQuery} against another Environment's connection in
	 * the current connection's Database Group - resolved via {@code databaseConnectionsVault} (see
	 * {@code DatabaseDefinitionsVault#resolveConnectionForGroupAndEnvironment}) - transiently. A brand
	 * new {@link DatabaseConnection} is opened just for this one execution and closed immediately after;
	 * the session's actual current connection ({@link #platform}, the singleton {@code sqlDatabase}
	 * bean, {@code Session}'s own state) is never touched, so the active connection is exactly the same
	 * before and after this call. See docs/TECHNICAL_CHANGE.md ("Cross-environment quick rerun").
	 *
	 * <p>Errors (current connection unknown, no Database Group, unknown Environment, no/ambiguous
	 * connection for the (Database Group, Environment) pair, or the transient execution itself failing)
	 * are reported to the console and otherwise swallowed here - exactly like the bare {@code /} case
	 * above, this is invoked from the console read loop, not from {@link #executeCommand()}.
	 */
	private void executeOnEnvironment(String environmentId) {
		DatabaseDefinition current = databaseConnectionsVault.getDatabaseConnection(this.platform);
		if (current == null) {
			console.error("Current connection '" + this.platform + "' does not exist");
			return;
		}
		String group = current.getDatabaseGroup();
		if (StringUtils.isBlank(group)) {
			console.error("Current connection '" + this.platform + "' has no Database Group defined - cannot resolve "
					+ "environment '" + environmentId + "' relative to it");
			return;
		}

		DatabaseDefinition target;
		try {
			target = databaseConnectionsVault.resolveConnectionForGroupAndEnvironment(group, environmentId);
		} catch (BroadSQLException e) {
			console.error(e.getLocalizedMessage());
			return;
		}

		console.println("Running last query on " + target.getId() + " [" + target.getEnvironment() + "]...");
		console.println("");

		DatabaseConnection transientDb = new DatabaseConnection(shellConsoleSettings, databaseConnectionsVault);
		try {
			transientDb.setPlatformCode(target.getId());
			transientDb.setCmdLineConsole(console);
			transientDb.connect();
			transientDb.setMaxRowsOnScreen(shellConsoleSettings.getMaxRowsOnScreen());
			transientDb.setListMode(this.listMode);
			transientDb.setSep(shellConsoleSettings.getDefaultSeparator());

			String rerunQuery = this.lastSQLQuery;
			// SPRINT 0110A: ${name} references are resolved with the current values and bound on the transient
			// connection (spec section 18.6)
			com.upandcoding.broadsql.controller.shell.scripts.PreparedSql prepared = com.upandcoding.broadsql.controller.shell.scripts.SqlReferences
					.prepare(rerunQuery, scriptVariables, transientDb::isPgJdbc);
			if (CommandUtils.isNotUpdateStatement(rerunQuery)) {
				if (prepared != null) {
					transientDb.executeSelectQuery(prepared);
				} else {
					transientDb.executeSelectQuery(rerunQuery);
				}
			} else if (prepared != null) {
				transientDb.executeUpdateQuery(prepared);
			} else {
				transientDb.executeUpdateQuery(rerunQuery);
			}
		} catch (BroadSQLException e) {
			console.error(e, false);
		} finally {
			try {
				if (transientDb.isConnected()) {
					transientDb.close(false);
				}
			} catch (BroadSQLException e) {
				console.error(e, false);
			}
		}
		console.println("");
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
				} finally {
					// SPRINT XT02B, section 1.2/7: fallback JLine/terminal cleanup for a non-EXIT
					// termination (e.g. Ctrl+C) - after the SQL connection above, matching BroadSQL.main's
					// own "console last" ordering; safe even if BroadSQL.main's own console.close() already
					// ran, since it is explicitly idempotent.
					if (console != null) {
						console.close();
					}
				}
			}
		});
	}

	/**
	 * SPRINT 2209C (GitHub #150): Esc abandons the whole statement being entered, not only the line JLine
	 * is editing. JLine only ever holds the current line; the earlier lines of an unterminated statement
	 * are in {@code run()}'s own buffer, so they are dropped here, right after the read in which Esc was
	 * pressed - the line just read is what the user typed after Esc and is processed normally.
	 *
	 * @return {@code pending} unchanged, or a new empty buffer if Esc was pressed during the last read
	 */
	static StringBuilder discardPendingStatementIfInputCancelled(ShellConsole console, StringBuilder pending) {
		return console.consumeInputCancellation() ? new StringBuilder() : pending;
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
		Signal.handle(new Signal("INT"), signal -> handleInterrupt());
	}

	/**
	 * What CTRL+C does. SPRINT 0110A: while a Script run is in progress - during a statement, or between two of its
	 * statements, when no command thread is running - the whole top-level run is cancelled (spec section 13), not
	 * only the current statement. Package-private so tests can press CTRL+C.
	 */
	void handleInterrupt() {
		if (currentCommandThread != null || scriptContext.isRunActive()) {
			if (sqlDatabase != null) {
				sqlDatabase.cancelCurrentStatement();
			}
			CommandCancellation.request();
		} else {
			idleInterruptReceived = true;
		}
	}

	/** SPRINT 0110A: routine output on {@code target} follows the running Script's {@code OUTPUT} mode. */
	private void installRoutineOutputSuppressor(ShellConsole target) {
		if (target != null) {
			target.setRoutineOutputSuppressor(scriptContext::isQuiet);
		}
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
		console.setPrompt(ShellPromptBuilder.build(this.platform));
		sqlDatabase.setCmdLineConsole(console);

		// Open the database with current platform
		session.openDatabase(platform);

		console.success("Connected to " + platform + " using JDBC driver: " + sqlDatabase.getDbDriver());
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
				// SPRINT 0917-02, section 18: JLine's completer only ever sees the single line currently
				// being edited - exposing everything already accumulated for the statement in progress
				// (if any) is what lets TAB completion work correctly on a genuinely multi-line SQL
				// statement. See PendingStatementBufferHolder's own javadoc for why this is needed at all.
				PendingStatementBufferHolder.set(bs.toString());
				line = console.readCommandLine(StringUtils.isNotBlank(bs.toString()));
				bs = discardPendingStatementIfInputCancelled(console, bs);
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
					cmdExecuted = handleSlashRerun(line);

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

					// If not a comment, the command is whatever before the final semicolon
					if (!line.startsWith("--")) {
						bs.append(StringUtils.substringBeforeLast(line, ";"));
						bs.append(" ");
					}

					executeMultiStatementLine(bs.toString());
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

			closeConnectionOnExit();
		}
	}

	/**
	 * EXIT (or end of input): closes the current connection through {@link DatabaseConnection#close()} (pending
	 * work rolled back and reported, "Disconnected" printed), then drops this interpreter's reference so the
	 * CTRL+C hook does not close it again. This is the normal owner of the connection's shutdown; the Spring
	 * context's destroy callback on the same {@code sqlDatabase} bean, run later by {@code BroadSQL.main}, is
	 * only a backstop and finds the connection already closed (see {@code SpringMainConfig.getSqlDatabase()}).
	 */
	void closeConnectionOnExit() throws BroadSQLException {
		if (sqlDatabase.isConnected()) {
			sqlDatabase.close();
		}
		sqlDatabase = null;
	}

}
