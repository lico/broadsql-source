package com.upandcoding.broadsql.controller.shell.output;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.IllegalFormatException;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.errors.SqlExecutionException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.ShellPromptBuilder;
import com.upandcoding.broadsql.controller.shell.reader.BasicLineReader;
import com.upandcoding.broadsql.controller.shell.reader.ConsoleLineReader;
import com.upandcoding.broadsql.controller.shell.sql.error.SqlErrorRenderer;
import com.upandcoding.broadsql.controller.shell.style.StyleRole;
import com.upandcoding.broadsql.controller.shell.style.TerminalStyle;
import com.upandcoding.broadsql.controller.shell.style.TerminalStyleHolder;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/*
 * 10 Jan 2012: still use java.io.Console for password data but for all output, 
 * now uses PrintStream. Why? because we need to display data in a different 
 * character set, such as for example accented characters. In that case, the 
 * code page is Cp850 and is defined using the following declaration:
 * new PrintStream(System.out, true, "Cp850");
 */
public class ShellConsole {

	@Autowired
	public ConsoleLogger consoleLogger;

	private final static Logger log = LoggerFactory.getLogger(ShellConsole.class.getName());

	public final static String MSG_INFO = "INFO";
	public final static String MSG_WARN = "WARNING";
	public final static String MSG_CRIT = "ERROR";

	protected Console readConsole = System.console(); // Console for reading data from the users, incl password
	protected PrintStream writeConsole = null; // Console for displaying data to the user

	// SPRINT XT02A (URL-Native API Execution), section 12.2: readLine()/readLine(boolean) delegate to
	// this - BasicLineReader by default (byte-for-byte the previous readConsole.readLine(...) calls),
	// swapped for a JLineConsoleLineReader only when activatejline=ON and initialization succeeds (see
	// BroadSQL.main). SPRINT XT02B, section 1.1: readPassword() now also delegates to this reader
	// (BasicLineReader.readPassword still goes straight through Console.readPassword, unchanged;
	// JLineConsoleLineReader.readPassword uses JLine's own masked/no-echo, history-disabled read) - this
	// fixes a real regression risk where System.console() can return null once JLine has attached the
	// terminal, silently breaking password prompts.
	private ConsoleLineReader lineReader = new BasicLineReader(readConsole);

	private String prompt = ConsoleSettings.DEFAULT_PROMPT; // Command line prompt
	private String defaultWindowTitle = null; // Windows title
	private boolean printToLogFile = false; // enables print to output log files

	/**
	 * 
	 */
	public ShellConsole() {
		if (writeConsole == null) {
			synchronized (ShellConsole.class) {
				initConsole();
			}
		}
	}

	/**
	 * 
	 */
	private void initConsole() {
		if (writeConsole == null) {
			synchronized (ShellConsole.class) {
				try {
					writeConsole = new PrintStream(System.out, true, "Cp850");
				} catch (UnsupportedEncodingException error) {
					log.error(error.getLocalizedMessage());
					System.exit(0);
				}
			}
		}
	}

	/**
	 * Whether this console's output is shown on a terminal, so semantic styles and the styled prompt are rendered
	 * as terminal (ANSI) sequences. A console whose text is displayed somewhere else, such as the BroadSQL
	 * Editor's Output pane ({@link CapturingConsole}), returns {@code false} and receives the same messages as
	 * plain text: the one place styles are applied ({@link #print(String, boolean, StyleRole)}) skips them.
	 */
	protected boolean rendersTerminalStyles() {
		return true;
	}

	/** Called for every semantic message (error, warning, info, success) before it is printed; nothing by default. */
	protected void noteMessage(StyleRole role) {
	}

	/**
	 * Makes this console a stand-in for {@code original} (SPRINT 3009A): the same log file writer
	 * ({@link #consoleLogger}, injected by Spring into the application's console only), the same prompt and
	 * the same log-file switch. Commands and the interpreter treat whichever console they hold as the
	 * session's console (the interpreter switches {@link #setPrintToLogFile} on for every command on a
	 * connection other than the CDF), so a stand-in without the logger failed on its first logged line.
	 */
	protected void adoptSessionOf(ShellConsole original) {
		this.consoleLogger = original.consoleLogger;
		this.prompt = original.prompt;
		this.printToLogFile = original.printToLogFile;
		this.routineOutputSuppressor = original.routineOutputSuppressor;
	}

	/**
	 * SPRINT 0110A: whether routine output ({@link #routineln} and its variants) is currently hidden from the
	 * screen ({@code OUTPUT QUIET} in the running Script). The state itself lives in the Script's frame
	 * ({@code ScriptContextStack}), never in a console flag that commands rewrite: the interpreter installs a
	 * supplier that reads it.
	 */
	private BooleanSupplier routineOutputSuppressor;

	public void setRoutineOutputSuppressor(BooleanSupplier routineOutputSuppressor) {
		this.routineOutputSuppressor = routineOutputSuppressor;
	}

	public boolean isRoutineOutputSuppressed() {
		try {
			return routineOutputSuppressor != null && routineOutputSuppressor.getAsBoolean();
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * 
	 * @param msg
	 */
	private void printAtomic(String msg) {
		if (writeConsole == null) {
			initConsole();
		} else {
			writeConsole.print(msg);
		}
	}

	/**
	 * 
	 * @return
	 */
	public String getPrompt() {
		return prompt;
	}

	/**
	 * 
	 * @return
	 */
	public boolean isPrintToLogFile() {
		return printToLogFile;
	}

	/**
	 * 
	 * @param printToLogFile
	 */
	public void setPrintToLogFile(boolean printToLogFile) {
		this.printToLogFile = printToLogFile;
	}

	/**
	 * 
	 * @param prompt
	 */
	public void setPrompt(String prompt) {
		this.prompt = prompt;
		setWindowsTitle(prompt, "", true);
	}

	/**
	 * 
	 * @param prompt
	 * @param message
	 * @param backToDefault
	 */
	public void setWindowsTitle(String prompt, String message, boolean backToDefault) {
		defaultWindowTitle = prompt;
		if (defaultWindowTitle != null && defaultWindowTitle.trim().endsWith(">")) {
			defaultWindowTitle = StringUtils.substringBeforeLast(defaultWindowTitle, ">");
		}
		defaultWindowTitle = SpringPropertiesConfig.APP_TITLE + " " + defaultWindowTitle;
		if (backToDefault) {
			defaultWindowTitle = defaultWindowTitle + " (idle)";
		} else {
			if (message != null) {
				String dispMsg = "";
				if (message != null) {
					dispMsg = message.trim();
					if (dispMsg.length() > 40) {
						dispMsg = message.substring(0, 40) + " ...";
					}
				}
				defaultWindowTitle = defaultWindowTitle + " (running: " + dispMsg + ")";
			}
		}
		ConsoleWindows.setWindowTitle(defaultWindowTitle);
	}

	/**
	 * 
	 * @param cse
	 */
	public void error(Exception cse) {
		error(cse, true);
	}

	/**
	 * 
	 * @param cse
	 */
	public void warn(BroadSQLException cse) {
		warn(cse, true);
	}

	/**
	 * 
	 * @param cse
	 */
	public void error(BroadSQLException cse) {
		error(cse, true);
	}

	/**
	 * 
	 * @param cse
	 * @param displayPrompt
	 */
	public void warn(Exception cse, boolean displayPrompt) {
		String prefix = MSG_WARN + ": ";
		println(prefix + cse.getLocalizedMessage(), displayPrompt, StyleRole.WARNING);
	}

	/**
	 *
	 * @param message
	 * @param displayPrompt
	 */
	public void warn(String message, boolean displayPrompt) {
		String prefix = MSG_WARN + ": ";
		println(prefix + message, displayPrompt, StyleRole.WARNING);
	}

	/**
	 *
	 * @param message
	 * @param displayPrompt
	 */
	public void info(String message, boolean displayPrompt) {
		String prefix = MSG_INFO + ": ";
		println(prefix + message, displayPrompt, StyleRole.INFO);
	}

	/**
	 * SPRINT 2409K: a completed operation, shown in the theme's success style. Unlike {@link #info}, no
	 * prefix is added: the text is printed exactly as given, so converting an existing confirmation
	 * {@code println} to this changes nothing when styling is off.
	 */
	public void success(String message) {
		println(message, true, StyleRole.SUCCESS);
	}

	/** SPRINT 2409K: same as {@link #success(String)}, without the prompt prefix. */
	public void successln(String message) {
		println(message, false, StyleRole.SUCCESS);
	}

	/**
	 *
	 * @param cse
	 * @param displayPrompt
	 */
	public void error(Exception cse, boolean displayPrompt) {
		errorReported = true;
		ERROR_SERIAL.incrementAndGet();
		if (cse instanceof SqlExecutionException) {
			println(SqlErrorRenderer.render((SqlExecutionException) cse), displayPrompt, StyleRole.ERROR);
			return;
		}
		String prefix = MSG_CRIT + ": ";
		println(prefix + cse.getLocalizedMessage(), displayPrompt, StyleRole.ERROR);
	}

	/**
	 *
	 * @param message
	 * @param displayPrompt
	 */
	public void error(String message, boolean displayPrompt) {
		errorReported = true;
		ERROR_SERIAL.incrementAndGet();
		String prefix = MSG_CRIT + ": ";
		println(prefix + message, displayPrompt, StyleRole.ERROR);
	}

	// SPRINT XT02B, section 15: many commands (e.g. CommandDefault, the raw-SQL fallback command,
	// and most API commands such as CommandRun) deliberately catch their own errors and report them
	// via error(...) above rather than letting a BroadSQLException propagate out of Command#execute -
	// "BroadSQL itself keeps running and returns to the prompt" is CommandDefault's own documented
	// intent for the single-statement case. That means a thrown-exception check alone cannot tell
	// executeMultiStatementLine() whether a given statement actually failed. This flag is the generic
	// signal instead: true the moment any error(...) overload above is called, regardless of whether
	// the underlying failure was ever thrown as an exception.
	private boolean errorReported = false;

	// SPRINT 0110A: counts every error(...) call on any console, never reset. The Script executor compares it
	// before and after a statement, so an error reported by a nested Script's statements (which reset
	// errorReported for their own statements) or printed on another console instance (the BroadSQL Editor's
	// capture) can never go unnoticed or be cleared by somebody else.
	private static final AtomicLong ERROR_SERIAL = new AtomicLong();

	/** SPRINT 0110A: the number of errors reported so far by every console of the process (see {@link #ERROR_SERIAL}). */
	public static long errorSerial() {
		return ERROR_SERIAL.get();
	}

	/** Clears the "an error was reported" flag - call before running a statement whose success you need to check via {@link #wasErrorReported()}. */
	public void resetErrorReported() {
		errorReported = false;
	}

	/** {@code true} if any {@code error(...)} overload has been called since the last {@link #resetErrorReported()} (or since construction). */
	public boolean wasErrorReported() {
		return errorReported;
	}

	/**
	 * 
	 * @param message
	 */
	public void error(String message) {
		error(message, true);
	}

	/**
	 * 
	 * @param message
	 */
	public void warn(String message) {
		warn(message, true);
	}

	/**
	 * 
	 * @param message
	 */
	public void info(String message) {
		info(message, true);
	}

	/**
	 * 
	 * @param message
	 * @param displayPrompt
	 */
	public void print(String message, boolean displayPrompt) {
		print(message, displayPrompt, null);
	}

	/**
	 * SPRINT 2409K: the single output path. {@code role}, when given, styles the message on screen only
	 * ({@link TerminalStyleHolder}; unchanged text when styling is off); the prompt prefix is never styled
	 * here, a trailing line break is kept outside the styled text, and the log file always receives the
	 * plain text.
	 */
	private void print(String message, boolean displayPrompt, StyleRole role) {
		print(message, displayPrompt, role, false);
	}

	/**
	 * SPRINT 0110A: {@code routine} output is skipped on screen while {@link #isRoutineOutputSuppressed()}, and is
	 * written to the activity log in every case, so the log always holds the complete NORMAL-mode trace.
	 */
	private void print(String message, boolean displayPrompt, StyleRole role, boolean routine) {
		if (writeConsole == null) {
			initConsole();
		}
		if (role != null) {
			noteMessage(role);
		}
		String msg = message;
		if (message == null) {
			msg = "";
		}
		boolean terminal = rendersTerminalStyles();
		String screen = role == null || !terminal ? msg : styled(role, msg);
		if (displayPrompt) {
			msg = prompt + msg;
			screen = (terminal ? renderedPrompt() : prompt) + screen;
		}
		//console.printf(msg);
		if (!routine || !isRoutineOutputSuppressed()) {
			printAtomic(screen);
		}

		/* Also output to the log file if authorized at application level (printToLogFileGlobal) and at
		 * connection level (printToLogFile)
		 */
		//log.debug("printToLogFile? "+printToLogFile);
		if (printToLogFile) {
			try {
				boolean displayDateAndTime = displayPrompt;
				consoleLogger.setLogDisplayDateAndTime(displayDateAndTime);
				consoleLogger.write(msg);
			} catch (IOException ie) {
				writeConsole.printf(prompt + "Error: unable to write to log file due to " + ie.getMessage());
				setPrintToLogFile(false);
			}
		}

	}

	/**
	 * @param message
	 * @param messageType(String): can be INFO, WARNING or CRITICAL
	 * @param displayPrompt
	 */
	public void print(String message, String messageType, boolean displayPrompt) {
		print(messageType + ": " + message, displayPrompt);
	}

	/**
	 * @param message
	 * @param messageType(String): can be INFO, WARNING or CRITICAL
	 */
	public void print(String message, String messageType) {
		print(message, messageType, true);
	} 

	/**
	 * 
	 * @param message
	 * @param displayPrompt
	 */
	public void println(String message, boolean displayPrompt) {
		print(message + "\n", displayPrompt);
	}

	/** SPRINT 2409K: {@link #println(String, boolean)} with a semantic {@link StyleRole} for the message. */
	private void println(String message, boolean displayPrompt, StyleRole role) {
		print(message + "\n", displayPrompt, role);
	}

	/** {@code text} rendered for {@code role}, keeping any trailing line break unstyled so no style can bleed into the next line. */
	private static String styled(StyleRole role, String text) {
		TerminalStyle style = TerminalStyleHolder.get();
		if (!style.isEnabled()) {
			return text;
		}
		int end = text.length();
		while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
			end--;
		}
		return style.render(role, text.substring(0, end)) + text.substring(end);
	}

	/**
	 * @param message
	 * @param messageType(String): can be INFO, WARNING or CRITICAL
	 */
	public void println(String message, String messageType) {
		println(message, messageType, true);
	}

	/**
	 * 
	 * @param messageType(String): can be INFO, WARNING or CRITICAL
	 * @param messageType
	 * @param displayPrompt
	 */
	public void println(String message, String messageType, boolean displayPrompt) {
		if (messageType == null || (!MSG_INFO.equalsIgnoreCase(messageType) && !MSG_WARN.equalsIgnoreCase(messageType) && !MSG_CRIT.equalsIgnoreCase(messageType))) {
			messageType = MSG_INFO;
		} else {
			messageType = messageType.toUpperCase();
		}
		print(message + "\n", messageType, displayPrompt);
	}

	/**
	 * 
	 * @param message
	 */
	public void print(String message) {
		print(message, true);
	}

	/**
	 * 
	 * @param message
	 */
	public void println(String message) {
		println(message, true);
	}

	/**
	 * 
	 * @param message
	 */
	public void println(StringBuffer message) {
		println(message.toString(), true);
	}

	/**
	 * 
	 * @return
	 */
	public String readLine() {
		return readLine(true);
	}

	/**
	 *
	 * @param displayPrompt
	 * @return
	 */
	public String readLine(boolean displayPrompt) {
		return lineReader.readLine(displayPrompt ? renderedPrompt() : "");
	}

	/**
	 * Reads the next command at the primary prompt ({@code CommandInterpreter}'s input loop only): see
	 * {@link ConsoleLineReader#readCommandLine}.
	 *
	 * @param statementPending whether earlier lines of a statement not yet ended with {@code ;} are waiting
	 */
	public String readCommandLine(boolean statementPending) {
		return lineReader.readCommandLine(renderedPrompt(), statementPending);
	}

	/** Places {@code text} on the command prompt's input line without submitting it (the BroadSQL Editor's Send to CLI): see {@link ConsoleLineReader#offerInput}. */
	public com.upandcoding.broadsql.controller.shell.reader.InputOffer offerCommandInput(String text) {
		return lineReader.offerInput(text);
	}

	/**
	 * The primary prompt as it appears on screen: the one rendering of it, used both by {@link #readLine}
	 * and by every message printed with a prompt prefix ({@code print(..., displayPrompt=true)}, including
	 * the blank {@code println("")} lines commands end with). Before, those prefixes wrote the plain prompt
	 * text, so with a theme the line before the input prompt showed an unstyled copy of it. Identical to
	 * the plain prompt when styling is off. The log file always receives the plain prompt.
	 */
	private String renderedPrompt() {
		return ShellPromptBuilder.style(prompt, TerminalStyleHolder.get(), isProductionConnection());
	}

	/**
	 * SPRINT 2409K: tells the prompt whether the current connection's Environment is flagged Production
	 * ({@link StyleRole#PROMPT_PRODUCTION}); only consulted when styling is on. Installed by
	 * {@code BroadSQL.main}; without it, never production.
	 */
	public void setProductionIndicator(BooleanSupplier productionIndicator) {
		this.productionIndicator = productionIndicator;
	}

	private BooleanSupplier productionIndicator;

	private boolean isProductionConnection() {
		if (productionIndicator == null || !TerminalStyleHolder.get().isEnabled()) {
			return false;
		}
		try {
			return productionIndicator.getAsBoolean();
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** SPRINT 2209C: see {@link ConsoleLineReader#consumeInputCancellation()}. */
	public boolean consumeInputCancellation() {
		return lineReader.consumeInputCancellation();
	}

	/**
	 * Installs a different {@link ConsoleLineReader} (SPRINT XT02A, section 12) - called once, at
	 * startup, by {@code BroadSQL.main} once {@code activatejline}'s setting is known; the default
	 * {@link BasicLineReader} otherwise. Every subsequent {@link #readLine} call goes through whichever
	 * reader is installed - command execution semantics never depend on which one that is.
	 */
	public void setLineReader(ConsoleLineReader lineReader) {
		this.lineReader = lineReader == null ? new BasicLineReader(readConsole) : lineReader;
	}

	/**
	 * SPRINT 0110A: routine output of a Script run (statement echo, {@code Found N queries}, bound-value lines,
	 * row-count feedback, blank separators, {@code LET} confirmations, a {@code SUCCESS} status line): hidden on
	 * screen by {@code OUTPUT QUIET}, always logged. Never use it for an error, a warning or a result.
	 */
	public void routineln(String message) {
		print(message + "\n", true, null, true);
	}

	/** SPRINT 0110A: {@link #routineln} without the prompt prefix. */
	public void routineWriteln(String message) {
		print(message + "\n", false, null, true);
	}

	/**
	 * SPRINT 0110A: routine output that is hidden on screen whatever the current mode and always logged: the
	 * {@code SUCCESS} status line of a run that ended in {@code OUTPUT QUIET}, printed after its frame is gone.
	 */
	public void logOnlyln(String message) {
		BooleanSupplier current = routineOutputSuppressor;
		routineOutputSuppressor = () -> true;
		try {
			print(message + "\n", true, null, true);
		} finally {
			routineOutputSuppressor = current;
		}
	}

	/** SPRINT 0110A: {@link #routineWriteln} without the line break. */
	public void routineWrite(String message) {
		print(message, false, null, true);
	}

	/**
	 * SPRINT 0110A: a warning shown exactly as given (no {@code WARNING:} prefix), with the warning style: the
	 * {@code @instance}/{@code @environment} mismatch lines, whose text is unchanged.
	 */
	public void warningText(String message) {
		println(message, true, StyleRole.WARNING);
	}

	/**
	 * Same as print but does not write the prompt
	 * @param message
	 */
	public void write(String message) {
		print(message, false);
	}

	/**
	 * Same as print but does not write the prompt
	 * @param message
	 */
	public void writeln(String message) {
		println(message, false);
	}

	/**
	 * Prints a block of possibly multi-line content with no prompt anywhere in it - splits on {@code \n}
	 * and calls {@link #writeln(String)} once per line, rather than a caller looping {@link #println}
	 * (which defaults to {@code displayPrompt=true} and would prepend the shell prompt to every single
	 * line - SPRINT XT02B, section 12: "prompt is only for interactive input"). This is the recommended
	 * central helper for any command that renders a multi-line table/detail view.
	 * @param multilineText
	 */
	public void printBlock(String multilineText) {
		if (multilineText == null) {
			return;
		}
		for (String line : multilineText.split("\n", -1)) {
			writeln(line);
		}
	}

	/**
	 * 
	 * @return
	 */
	public String readPassword() {
		String password = null;
		try {
			// "[Enter password]" - the exact prompt text this project has always shown here
			// (previously produced via Console.readPassword("[%s]", "Enter password")).
			char[] passwd = lineReader.readPassword("[Enter password]");
			if (passwd != null) {
				password = String.valueOf(passwd);
			}
		} catch (IllegalFormatException ife) {
			error(new BroadSQLException(ife));
		}
		return (password);
	}

	/**
	 * Releases the installed {@link ConsoleLineReader}'s resources (JLine terminal, persistent history
	 * save) - SPRINT XT02B, section 1.2. Idempotent itself only insofar as the installed reader's own
	 * {@code close()} is (a no-op default for {@link BasicLineReader}; explicitly guarded for
	 * {@code JLineConsoleLineReader}), so calling this more than once (normal {@code EXIT} plus a JVM
	 * shutdown-hook fallback) is always safe.
	 */
	public void close() {
		lineReader.close();
	}

	/**
	 * 
	 * @param def
	 * @param fieldName
	 * @param currentValue
	 * @param nullAllowed
	 * @param isPassword
	 * @param isYesNo
	 * @param listOfValues
	 * @return
	 */
	public String inputField(DatabaseDefinition def, String fieldName, String currentValue, boolean nullAllowed, boolean isPassword, boolean isYesNo, Set<String> listOfValues) {
		String result = null;
		if (def != null) {
			boolean process = true;
			if (StringUtils.isNotBlank(currentValue)) {
				println("Current value for '" + fieldName + "': ");
				// never echo a password: the console output can be logged (command activity log)
				println(isPassword ? "********" : currentValue);
				String confirm = inputField(def, "Keep this value [y/n]?", "", false, false, true, null);
				if ("n".equalsIgnoreCase(confirm)) {
					process = true;
				} else {
					result = currentValue;
					process = false;
				}
			}
			if (process) {
				boolean repeat = true;
				while (repeat) {
					print(fieldName + ": ");
					if (!isPassword) {
						result = readLine(false);
					} else {
						boolean pwdRepeat = true;
						while (pwdRepeat) {
							result = readPassword();
							print("Confirmation: ");
							String verif = readPassword();
							if (StringUtils.isBlank(verif)) {
								if (StringUtils.isBlank(result)) {
									pwdRepeat = false;
								} else {
									error("Entered and confirmed password do not match. Try again.");
									pwdRepeat = true;
								}
							} else {
								if (verif.equalsIgnoreCase(result)) {
									pwdRepeat = false;
								} else {
									error("Entered and confirmed password do not match. Try again.");
									pwdRepeat = true;
								}
							}
						}
					}
					if (nullAllowed && StringUtils.isBlank(result)) {
						repeat = false;
					} else {
						if (StringUtils.isBlank(result)) {
							// Null not allowed but blank entered => error
							error("Entered value for '" + fieldName + "' is blank. Try again.");
							repeat = true;
						} else {
							if (listOfValues == null || listOfValues.isEmpty()) {
								if (!isYesNo) {
									repeat = false;
								} else {
									// Enforce Yes/No
									if (result.equalsIgnoreCase("y") || result.equalsIgnoreCase("n") || result.equalsIgnoreCase("c") || result.equalsIgnoreCase("yes") || result.equalsIgnoreCase("no") || result.equalsIgnoreCase("cancel")) {
										repeat = false;
									} else {
										error("Entered value for '" + fieldName + "' must be 'y', 'n' or 'c'. Try again.");
										repeat = true;
									}
								}
							} else {
								// Must check compared to listOfValue
								if (listOfValues.contains(result)) {
									repeat = false;
								} else {
									error("Entered value for '" + fieldName + "' is not in the list. Try again.");
									println("Valid values: " + String.join(", ", listOfValues));
									repeat = true;
								}
							}
						}
					}
				}
			}
		}
		println("");
		return result;
	}

}

