package com.upandcoding.broadsql.controller.shell.output;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.IllegalFormatException;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
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
		println(prefix + cse.getLocalizedMessage(), displayPrompt);
	}

	/**
	 * 
	 * @param message
	 * @param displayPrompt
	 */
	public void warn(String message, boolean displayPrompt) {
		String prefix = MSG_WARN + ": ";
		println(prefix + message, displayPrompt);
	}

	/**
	 * 
	 * @param message
	 * @param displayPrompt
	 */
	public void info(String message, boolean displayPrompt) {
		String prefix = MSG_INFO + ": ";
		println(prefix + message, displayPrompt);
	}

	/**
	 * 
	 * @param cse
	 * @param displayPrompt
	 */
	public void error(Exception cse, boolean displayPrompt) {
		String prefix = MSG_CRIT + ": ";
		println(prefix + cse.getLocalizedMessage(), displayPrompt);
	}

	/**
	 * 
	 * @param message
	 * @param displayPrompt
	 */
	public void error(String message, boolean displayPrompt) {
		String prefix = MSG_CRIT + ": ";
		println(prefix + message, displayPrompt);
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
		if (writeConsole == null) {
			initConsole();
		}
		String msg = message;
		if (message == null) {
			msg = "";
		}
		if (displayPrompt) {
			msg = prompt + msg;
		}
		//console.printf(msg);
		printAtomic(msg);

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
		if (displayPrompt) {
			return (readConsole.readLine(prompt));
		} else {
			return (readConsole.readLine());
		}
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
	 * 
	 * @return
	 */
	public String readPassword() {
		String password = null;
		Console cons;
		char[] passwd;
		try {
			if ((cons = System.console()) != null && (passwd = cons.readPassword("[%s]", "Enter password")) != null) {
				password = String.valueOf(passwd);
			}
		} catch (IllegalFormatException ife) {
			error(new BroadSQLException(ife));
		}
		return (password);
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
				println(currentValue);
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

