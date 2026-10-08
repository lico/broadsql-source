package com.upandcoding.broadsql.controller;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.upandcoding.broadsql.controller.config.SpringMainConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.Session;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandCategoryCatalog;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.controller.shell.output.ConsoleUtils;
import com.upandcoding.broadsql.controller.shell.output.DisplayLayout;
import com.upandcoding.broadsql.controller.shell.reader.ApiCatalogService;
import com.upandcoding.broadsql.controller.shell.reader.JLineConsoleLineReader;
import com.upandcoding.broadsql.controller.shell.style.TerminalStyle;
import com.upandcoding.broadsql.controller.shell.style.TerminalStyleHolder;
import com.upandcoding.broadsql.controller.shell.swing.scriptlibrary.ScriptLibraryWorkspaceHolder;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;

public class BroadSQL {

	private final static Logger log = LoggerFactory.getLogger(BroadSQL.class.getName());

	/**
	 * Every registered, non-hidden command's primary keyword - for {@code HELP <TAB>} completion
	 * (SPRINT XT02A, section 13.9). Mirrors {@code CommandHelp#loadAllCommands}'s own reflective
	 * instantiation exactly (same "missing/unreadable lib/broadsql.jar is logged and skipped, not
	 * fatal" robustness {@code CommandLoader.loadAvailableCommands} already provides), but called once
	 * here at startup rather than on every {@code HELP} invocation.
	 */
	/**
	 * SPRINT 2409K: decides terminal styling ({@code color}, {@code theme}) and geometry ({@code displaymode})
	 * once the console reader is known, from what the actual terminal reports. With the standard console
	 * ({@code activatejline=OFF} or JLine unavailable) there is no terminal: {@code color=AUTO} then means no
	 * styling and the width is unknown ({@code displaymode=AUTO} then behaves as NORMAL). Never fails startup.
	 */
	private static void configureTerminalPresentation(ShellConsole console, ConsoleSettings consoleSettings, JLineConsoleLineReader jLineReader,
			ApplicationContext context) {
		try {
			TerminalStyle style = TerminalStyle.resolve(consoleSettings.getColorMode(), consoleSettings.getTheme(),
					jLineReader == null ? null : jLineReader.getTerminal());
			TerminalStyleHolder.set(style);
			if (jLineReader != null) {
				jLineReader.applyStyle(style);
				DisplayLayout.configure(consoleSettings.getDisplayMode(), jLineReader::getTerminalWidth);
			} else {
				DisplayLayout.configure(consoleSettings.getDisplayMode(), () -> 0);
			}
			DatabaseConnection sqlDatabase = (DatabaseConnection) context.getBean("sqlDatabase");
			DatabaseDefinitionsVault vault = (DatabaseDefinitionsVault) context.getBean("databaseConnectionsVault");
			console.setProductionIndicator(() -> isProductionEnvironment(sqlDatabase, vault));
		} catch (RuntimeException e) {
			log.warn("Terminal styling could not be configured ({}); continuing without it.", e.getLocalizedMessage());
			TerminalStyleHolder.set(TerminalStyle.PLAIN);
		}
	}

	/** {@code true} when the current connection's Environment is flagged Production (for the prompt's PROMPT_PRODUCTION role). */
	private static boolean isProductionEnvironment(DatabaseConnection sqlDatabase, DatabaseDefinitionsVault vault) {
		try {
			DatabaseDefinition platform = sqlDatabase == null ? null : sqlDatabase.getPlatform();
			if (platform == null || platform.getEnvironment() == null || vault == null) {
				return false;
			}
			for (EnvironmentDefinition environment : vault.getEnvironmentDetails()) {
				if (platform.getEnvironment().equalsIgnoreCase(environment.getId())) {
					return environment.isProduction();
				}
			}
			return false;
		} catch (BroadSQLException | RuntimeException e) {
			return false;
		}
	}

	private static List<String> loadCommandKeywords(CommandInterpreter commandInterpreter) {
		List<String> keywords = new ArrayList<>();
		try {
			Map<String, String> classMap = commandInterpreter.getCommands().getConsoleCommandLoader().loadAvailableCommands();
			for (String className : classMap.keySet()) {
				try {
					Class<?> classObject = Class.forName(className);
					Constructor<?> constructor = classObject.getDeclaredConstructor();
					Command cmd = (Command) constructor.newInstance();
					// SPRINT 2309T: HELP <TAB> offers the commands HELP itself documents (not EXPORT/SET SEPARATOR).
					if (cmd != null && CommandCategoryCatalog.isDocumented(cmd)) {
						keywords.add(cmd.getKeywords()[0]);
					}
				} catch (ReflectiveOperationException | RuntimeException ex) {
					// Same robustness as CommandHelp's own loop - one bad/unloadable class must never
					// block completion for every other command.
				}
			}
		} catch (BroadSQLException ex) {
			// No commands loaded (e.g. lib/broadsql.jar missing) - HELP completion simply has nothing
			// to offer, same as CommandHelp's own bare HELP falls back to an empty list.
		}
		return keywords;
	}

	public static void main(String[] args) {

		ApplicationContext context = new AnnotationConfigApplicationContext(SpringMainConfig.class);

		StartupCommandLineParser cmdLine = (StartupCommandLineParser) context.getBean("cmdLineParser");
		cmdLine.setArgs(args);
		
		if (cmdLine.parse()) {

			Session session = (Session) context.getBean("session");

			CommandInterpreter commandInterpreter = (CommandInterpreter) context.getBean("consoleCommandInterpreter");
			commandInterpreter.setPlatform(cmdLine.getParameterValue("to"));
			ShellConsole console = (ShellConsole) context.getBean("shellConsole");

			// SPRINT XT02A (URL-Native API Execution), section 19.2: apiproxymode=SYSTEM must set
			// java.net.useSystemProxies=true before anything touches the HTTP stack - done here, as early
			// as possible after the INI file is loaded (context construction above) and before any command
			// executes, never lazily inside the first RUN. ApiProxyConfigHolder is populated in the same
			// place so every ApiEndpointExecutor -> ApiHttpTransport built later in this session sees it.
			ConsoleSettings consoleSettings = (ConsoleSettings) context.getBean("consoleSettings");
			com.upandcoding.broadsql.dao.api.http.ApiProxyConfig.applySystemPropertyIfNeeded(consoleSettings);
			try {
				com.upandcoding.broadsql.dao.api.http.ApiProxyConfigHolder.set(com.upandcoding.broadsql.dao.api.http.ApiProxyConfig.fromSettings(consoleSettings));
			} catch (BroadSQLException ex) {
				console.error("Invalid API proxy configuration (" + ex.getLocalizedMessage() + ") - API execution will use no proxy.");
			}

			try {

				// Display program name and version
				console.writeln("");
				console.writeln(ConsoleUtils.getTitleAndVersion());

				// DefaultFileFormat (BroadSQL.ini): report once at startup if missing/invalid,
				// see docs/TECHNICAL_CHANGE.md ("Export ODS")
				String defaultFileFormatNotice = consoleSettings.getDefaultFileFormatStartupNotice();
				if (defaultFileFormatNotice != null) {
					console.info(defaultFileFormatNotice);
				}

				// CsvSeparator (BroadSQL.ini): SPRINT 2309T (#165), only COMMA/SEMICOLON are supported by the CSV
				// export format - report an unsupported value once, CSV exports themselves refuse it.
				String csvSeparatorNotice = consoleSettings.getCsvSeparatorStartupNotice();
				if (csvSeparatorNotice != null) {
					console.info(csvSeparatorNotice);
				}

				// DefaultEnvironment (BroadSQL.ini): report once at startup if missing, see docs/TODO.md, item 5
				String defaultEnvironmentNotice = consoleSettings.getDefaultEnvironmentStartupNotice();
				if (defaultEnvironmentNotice != null) {
					console.info(defaultEnvironmentNotice);
				}

				// Scripts (BroadSQL.ini): report once at startup if missing, see docs/TECHNICAL_CHANGE.md
				String scriptsLibraryNotice = consoleSettings.getScriptsLibraryPathStartupNotice();
				if (scriptsLibraryNotice != null) {
					console.info(scriptsLibraryNotice);
				}

				// JsScripts (BroadSQL.ini): report once at startup if missing, see docs/LIGHT_SCRIPTING.md
				String jsScriptsPathNotice = consoleSettings.getJsScriptsPathStartupNotice();
				if (jsScriptsPathNotice != null) {
					console.info(jsScriptsPathNotice);
				}

				// GitHub #154: FieldsSeparator/MaxRowsOnScreen/ScreenSeparator/MaxRowXLSX/Autocommit/
				// IsLogActivated report once at startup if missing/invalid, same "isolated, non-fatal"
				// pattern as the four notices above - see ConsoleSettings#resolve*() for each. Unlike those
				// four (String-typed, so a wrong-but-present value was never possible), these six used to be
				// bound directly as int/boolean/char @Value fields - an invalid value (e.g. MaxRowXLSX=format)
				// used to abort Spring context construction entirely, before any of this console/notice
				// machinery even existed, taking down startup for every unrelated setting and command too.
				String defaultSeparatorNotice = consoleSettings.getDefaultSeparatorStartupNotice();
				if (defaultSeparatorNotice != null) {
					console.info(defaultSeparatorNotice);
				}
				String maxRowsOnScreenNotice = consoleSettings.getMaxRowsOnScreenStartupNotice();
				if (maxRowsOnScreenNotice != null) {
					console.info(maxRowsOnScreenNotice);
				}
				String onScreenSeparatorNotice = consoleSettings.getOnScreenSeparatorStartupNotice();
				if (onScreenSeparatorNotice != null) {
					console.info(onScreenSeparatorNotice);
				}
				String maxRowXlsxNotice = consoleSettings.getMaxRowXlsxStartupNotice();
				if (maxRowXlsxNotice != null) {
					console.info(maxRowXlsxNotice);
				}
				String autoCommitNotice = consoleSettings.getAutoCommitStartupNotice();
				if (autoCommitNotice != null) {
					console.info(autoCommitNotice);
				}
				String logDefaultActivatedNotice = consoleSettings.getLogDefaultActivatedStartupNotice();
				if (logDefaultActivatedNotice != null) {
					console.info(logDefaultActivatedNotice);
				}
				String activateJLineNotice = consoleSettings.getActivateJLineStartupNotice();
				if (activateJLineNotice != null) {
					console.info(activateJLineNotice);
				}
				for (String notice : new String[] { consoleSettings.getColorStartupNotice(), consoleSettings.getThemeStartupNotice(),
						consoleSettings.getDisplayModeStartupNotice() }) {
					if (notice != null) {
						console.info(notice);
					}
				}

				// activatejline (BroadSQL.ini), SPRINT XT02A corrective pass, section 12.1: install the
				// JLine-backed reader (tab completion, history) only when requested; on ANY failure, emit
				// one warning and continue with the default BasicLineReader ShellConsole already has - the
				// application must never fail to start because JLine could not initialize.
				JLineConsoleLineReader jLineReader = null;
				if (consoleSettings.isJLineActivated()) {
					try {
						ApiDefinitionsVault apiVault = (ApiDefinitionsVault) context.getBean("apiDefinitionsVault");
						// Loaded once, here, not from a supplier re-invoked on every TAB press - section
						// 13.12 requires completion to stay responsive from local/cached state.
						List<String> commandKeywords = loadCommandKeywords(commandInterpreter);
						// SPRINT 0917-02 (Intelligent TAB Auto-Completion): the live CommandList/DatabaseConnection
						// beans - CommandList is populated in place by CommandInterpreter.run() before the read
						// loop starts, so by the time a user can press TAB it already reflects every registered
						// command (see BroadSqlCommandCompletionProvider's own javadoc); sqlDatabase is the one
						// long-lived singleton connection, so it always reflects whichever platform is current.
						CommandList commandList = commandInterpreter.getCommands();
						DatabaseConnection sqlDatabase = (DatabaseConnection) context.getBean("sqlDatabase");
						// SPRINT XT02B, section 14: persistent, per-OS-user history file - resolveJLineHistoryFilePath()
						// itself never fails (it's pure path computation, no I/O); JLineConsoleLineReader.create
						// handles the file being unusable (unwritable/unreadable) with its own graceful
						// in-memory fallback, never failing JLine init because of it.
						jLineReader = JLineConsoleLineReader.create(new ApiCatalogService(apiVault), () -> commandKeywords,
								consoleSettings.resolveJLineHistoryFilePath(), commandList, sqlDatabase);
						console.setLineReader(jLineReader);
					} catch (IOException | RuntimeException ex) {
						jLineReader = null;
						console.warn("JLine could not be initialized (" + ex.getLocalizedMessage() + "); using the standard console.", true);
					}
				}
				configureTerminalPresentation(console, consoleSettings, jLineReader, context);

				// Authenticate
				session.authenticate(console);

				// Execute program until end
				commandInterpreter.run();

				// SPRINT 0917-01: protect any dirty Script Library buffers and dispose that window if it
				// was ever opened this session - it deliberately never disposes on a normal user hide (see
				// ScriptLibraryFrame), so without this, a hidden-but-undisposed frame would keep the Swing
				// event dispatch thread - and therefore this whole JVM - alive indefinitely after EXIT. A
				// no-op if the Script Library was never opened.
				ScriptLibraryWorkspaceHolder.shutdown();

				// Close the session
				session.close();

				console.writeln("");

			} catch (BroadSQLException ex) {
				console.error(ex);
				log.warn(ex.getLocalizedMessage());
			} catch (Exception e) {
				console.error(e);
				log.warn(e.getLocalizedMessage());
			} finally {
				// SPRINT XT02B, section 1.2/7: JLine/terminal cleanup (and, once persistent history is
				// active, its save) is guaranteed on every exit path from the try above - but only AFTER
				// session/application cleanup, since that cleanup can still write user-visible output
				// (e.g. "Disconnected from ...") that needs the console/prompt machinery intact. Safe to
				// reach a second time via CommandInterpreter's shutdown-hook fallback: close() is
				// explicitly idempotent.
				console.close();
			}
		}
		
		// Close context if necessary
		if (context!=null) {
			((AnnotationConfigApplicationContext)context).close();
		}
	}
}
