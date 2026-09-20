package com.upandcoding.broadsql.controller.shell.commands.core.help;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.CommandLoader;
import com.upandcoding.broadsql.controller.shell.output.ConsoleUtils;

/**
 * Displays the list of supported commands, or detailed help for one command: {@code HELP} or
 * {@code HELP <commandName>}.
 *
 * <p>With no argument, lists every non-hidden core and extension command with a one-line
 * description, followed by the built-in macro syntaxes ({@code <@file>}, {@code @file}, and
 * {@code /}). With a command name, shows that command's description, synonyms, arguments and
 * examples - matching is case-insensitive on the exact keyword (e.g. {@code HELP SHOW TABLES}), and
 * this form also finds hidden commands not listed by plain {@code HELP}.
 *
 * <p>If the given name doesn't match any BroadSQL command and the current connection is H2, BroadSQL
 * falls back to H2's own native {@code HELP} grammar for that term.
 */
public class CommandHelp extends Command {
	
	@Autowired
	CommandList commands;

	public CommandHelp() {
		super("HELP");
	}

	public void printForSetOfCommands(HashMap<String, String> mapClasses) throws BroadSQLException {
		TreeSet<String> sortedKeywords = new TreeSet(mapClasses.keySet());
		for (String keyword : sortedKeywords) {
			String className = mapClasses.get(keyword);
			try {
				Class classObject = Class.forName(className);
				try {
					Command cmd = (Command) classObject.getDeclaredConstructor().newInstance();
					if (cmd != null) {
						String[] keyWords = cmd.getKeywords();
						String keyWordDisplay = keyWords[0];
						String descr = cmd.getDescription();
						console.writeln("\t" + StringUtils.rightPad(keyWordDisplay, 30, " ") + descr);
					}

				} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException ex) {
					console.println(ex.toString());
				}
			} catch (ClassNotFoundException ex) {
				console.println(ex.toString());
			}
		}
	}

	/*
	 * Prints a list of command
	 * Does not print command which isHidden() method returns true
	 */
	private void printHelp() {
		try {
			HashMap<String, String> classMap = getConsoleCommandInterpreter().getCommands().getConsoleCommandLoader().loadAvailableCommands();
			Set<String> classes = classMap.keySet();
			HashMap<String, String> mapClassesCore = new HashMap<>();
			HashMap<String, String> mapClassesExt = new HashMap<>();
			for (String className : classes) {
				try {
					Class classObject = Class.forName(className);
					try {
						Command cmd = (Command) classObject.getDeclaredConstructor().newInstance();
						if (cmd != null && !cmd.isHidden()) {
							String[] keyWords = cmd.getKeywords();
							String keyWordDisplay = keyWords[0];
							String descr = cmd.getDescription();
							if (classMap.get(className) != null && classMap.get(className).equalsIgnoreCase(CommandLoader.CMD_CORE)) {
								mapClassesCore.put(keyWordDisplay, className);
							} else {
								mapClassesExt.put(keyWordDisplay, className);
							}
						}
					} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException ex) {
						console.println(ex.toString());
					}
				} catch (ClassNotFoundException ex) {
					console.println(ex.toString());
				}
			}//for

			console.writeln("Specific help can be obtained by typing HELP <commandName>, where commandName is one of the following:");
			console.writeln("1. " + SpringPropertiesConfig.APP_TITLE + " Core Commands: ");
			printForSetOfCommands(mapClassesCore);
			console.writeln("");
			console.writeln("2. Addin Commands: ");
			printForSetOfCommands(mapClassesExt);

		} catch (BroadSQLException se) {
			console.error(se);
		}
		console.writeln("");
		console.writeln("");
		console.writeln("3. MACRO COMMANDS are specific keywords substituted at execution time in SQL queries:");
		console.writeln("\t" + "<@fileName> retrieves values from a list and converts them into a list ('a','b','c', ... )");
		console.writeln("\t\t" + "Where: fileName is a valid file path");
		console.writeln("\t\t" + "Example: select count(*) from TEST where id in <@c:\\codes.txt>");
		console.writeln("\t" + "@<fileName> executes the SQL commands stored in a file");
		console.writeln("\t\t" + "Where: fileName is a valid file path");
		console.writeln("\t\t" + "Example: @c:\\dbRestore.sql;");
		console.writeln("\t" + "/ executes the former SQL query. No parameter");
	}

	/*
	 * Prints specific help for a given command
	 * Print all commands whether they're hidden or not
	 * @param commandName(String) the keyword of the command
	 */
	private void printHelpForCommand(String query, String commandName) {
		try {
			HashMap<String, String> classMap = getConsoleCommandInterpreter().getCommands().getConsoleCommandLoader().loadAvailableCommands();
			Set<String> classes = classMap.keySet();
			boolean cmdFound = false;
			for (String className : classes) {
				try {
					if (commandName != null && !commandName.trim().equals("")) {
						Class classObject = Class.forName(className);
						try {
							Command cmd = (Command) classObject.getDeclaredConstructor().newInstance();
							if (cmd != null) {
								String[] keyWords = cmd.getKeywords();
								for (String keyWord : keyWords) {
									if (keyWord.toUpperCase().equalsIgnoreCase(commandName.toUpperCase(Locale.FRENCH))) {
										cmdFound = true;
										String help = cmd.displayDetailedHelp();
										console.write(help);
									}
								}
							}
						} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException ex) {
							console.print(ex.toString());
						}
					}

				} catch (ClassNotFoundException ex) {
					console.error(new BroadSQLException(ex.toString()));
				}
			}//for
			if (!cmdFound) {
				console.println("No help available for command '" + commandName + "'.");
				if (this.sqlDatabase.getPlatform().getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_H2)) {
					console.println("Searching for H2 specific help for command " + commandName + "'.");
					boolean initLstMode = sqlDatabase.isListMode();
					sqlDatabase.setListMode(true);
					sqlDatabase.executeSelectQuery(query);
					sqlDatabase.setListMode(initLstMode);
				}
			}
		} catch (BroadSQLException se) {
			console.error(se);
		}
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);

		String cmdName = null;
		if (CommandUtils.isValidArgs(args)) {
			cmdName  =args[0].trim();
			printHelpForCommand(query, cmdName);
		} else {
			console.writeln(ConsoleUtils.getTitleAndVersion());
			printHelp();
		}
		console.writeln("");
	}

	@Override
	public String getDescription() {
		return ("displays a list of supported command or help for a specified command");
	}

	@Override
	public String getArguments() {
		return "command string (optional), the name of a command";
	}

	@Override
	public String getExamples() {
		return "HELP DESCR;\n\tHELP;";
	}
}
