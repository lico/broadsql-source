package com.upandcoding.broadsql.controller.shell.commands.core.help;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandCategory;
import com.upandcoding.broadsql.controller.shell.commands.CommandCategoryCatalog;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.reader.ConsoleShortcuts;
import com.upandcoding.broadsql.dao.LastCaptureSuppressor;

/**
 * BroadSQL's help: {@code HELP}, {@code HELP <category>}, {@code HELP <command>},
 * {@code HELP FIND <text>}, {@code HELP ALL}, {@code HELP PATTERNS} and {@code HELP SHORTCUTS}.
 *
 * <p>{@code HELP} alone prints two tables: the command categories (name, alias, description), followed by
 * the Patterns and Shortcuts topics, and the forms of {@code HELP} itself. {@code HELP ALL} prints the
 * full command reference grouped by category. {@code HELP <category>} (by name or alias, for example
 * {@code HELP EXPORT}) lists the commands of one category. {@code HELP FIND <text>} searches command
 * names, synonyms and descriptions and category names and descriptions. {@code HELP <command>} shows
 * the detailed help of one command. {@code HELP PATTERNS} documents the syntaxes usable inside SQL
 * ({@code <@file>}, {@code <@csv:...>}, {@code <@excel:...>}, {@code <@last:...>}, {@code <@clipboard>})
 * and the line shortcuts {@code @file}, {@code /} and {@code / <environment>}.
 * {@code HELP SHORTCUTS} lists the keyboard shortcuts of the interactive console; it needs no connection.
 * An unknown topic gets a "Did you mean" suggestion when a category or command name is close.
 *
 * <p>When the name matches no BroadSQL command, category or topic and the current connection is H2,
 * the help of H2's own SQL grammar for that term is shown. It does not change the result that
 * {@code <@last:column>} refers to.
 */
public class CommandHelp extends Command {

	private static final String[] PATTERN_SEARCH_KEYWORDS = { "pattern", "patterns", "macro", "list source",
			"file", "csv", "excel", "spreadsheet", "clipboard", "last", "rerun", "environment" };

	public CommandHelp() {
		super("HELP");
	}

	// ---------------------------------------------------------------------
	// Command loading (unchanged mechanism: reflect over the classes the
	// CommandLoader already knows about, instantiate throwaway instances to
	// read their static description/keywords - no console/session wiring
	// needed for that).
	// ---------------------------------------------------------------------

	private List<Command> loadAllCommands() {
		List<Command> result = new ArrayList<>();
		try {
			HashMap<String, String> classMap = getConsoleCommandInterpreter().getCommands().getConsoleCommandLoader()
					.loadAvailableCommands();
			for (String className : classMap.keySet()) {
				try {
					Class<?> classObject = Class.forName(className);
					Command cmd = (Command) classObject.getDeclaredConstructor().newInstance();
					if (cmd != null) {
						result.add(cmd);
					}
				} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
						| IllegalArgumentException | InvocationTargetException | NoSuchMethodException
						| SecurityException ex) {
					console.println(ex.toString());
				}
			}
		} catch (BroadSQLException se) {
			console.error(se);
		}
		return result;
	}

	private static String primaryKeyword(Command cmd) {
		return cmd.getKeywords()[0];
	}

	// ---------------------------------------------------------------------
	// HELP (bare) - compact category menu
	// ---------------------------------------------------------------------

	private static final String PATTERNS_ALIAS = "PAT";
	private static final String PATTERNS_DESCRIPTION = "Use files, clipboard, spreadsheets and previous results in SQL.";
	private static final String SHORTCUTS_TOPIC = "SHORTCUTS";
	private static final String SHORTCUTS_DESCRIPTION = "Keyboard shortcuts of the interactive console.";
	private static final String[] SHORTCUTS_SEARCH_KEYWORDS = { "shortcut", "keyboard", "key" };
	private static final List<String> SHORTCUT_HEADERS = List.of("KEY / SHORTCUT", "ACTION");

	/** Bare HELP: both tables go through the shared table renderer, so they follow displaymode and the terminal width. */
	private void printCompactOverview() {
		List<List<String>> topics = new ArrayList<>();
		for (CommandCategory category : CommandCategory.values()) {
			topics.add(List.of(category.getDisplayName().toUpperCase(Locale.ROOT), category.getAlias(), category.getDescription()));
		}
		topics.add(List.of("PATTERNS", PATTERNS_ALIAS, PATTERNS_DESCRIPTION));
		topics.add(List.of(SHORTCUTS_TOPIC, "", SHORTCUTS_DESCRIPTION));
		shellConsolePrinter.printTable(List.of("CATEGORY", "ALIAS", "DESCRIPTION"), topics, false);
		shellConsolePrinter.printTable(List.of("TYPE", "TO SEE"), List.of(
				List.of("HELP <category|alias>", "The commands of a category"),
				List.of("HELP <command>", "Detailed help for one command"),
				List.of("HELP FIND <text>", "Search the help"),
				List.of("HELP ALL", "Full command reference"),
				List.of("HELP " + SHORTCUTS_TOPIC, SHORTCUTS_DESCRIPTION)), false);
	}

	// ---------------------------------------------------------------------
	// HELP SHORTCUTS
	// ---------------------------------------------------------------------

	private void printShortcuts() {
		shellConsolePrinter.printTable(SHORTCUT_HEADERS, shortcutRows(ConsoleShortcuts.STANDARD), false);
		console.writeln("Depending on your terminal:");
		shellConsolePrinter.printTable(SHORTCUT_HEADERS, shortcutRows(ConsoleShortcuts.TERMINAL_DEPENDENT), false);
		console.writeln("On a line by itself: / runs the last query again, // shows it, exit ends BroadSQL. See HELP PATTERNS.");
	}

	private static List<List<String>> shortcutRows(List<ConsoleShortcuts.Shortcut> shortcuts) {
		List<List<String>> rows = new ArrayList<>();
		for (ConsoleShortcuts.Shortcut shortcut : shortcuts) {
			rows.add(List.of(shortcut.keys(), shortcut.action()));
		}
		return rows;
	}

	// ---------------------------------------------------------------------
	// HELP <category> / HELP ALL
	// ---------------------------------------------------------------------

	private Map<CommandCategory, List<Command>> groupByCategory(List<Command> allCommands) {
		Map<CommandCategory, List<Command>> byCategory = new EnumMap<>(CommandCategory.class);
		for (CommandCategory category : CommandCategory.values()) {
			byCategory.put(category, new ArrayList<>());
		}
		for (Command cmd : allCommands) {
			if (!CommandCategoryCatalog.isDocumented(cmd)) {
				continue;
			}
			byCategory.get(CommandCategoryCatalog.categoryOf(cmd)).add(cmd);
		}
		for (List<Command> list : byCategory.values()) {
			list.sort(Comparator.comparing(CommandHelp::primaryKeyword, String.CASE_INSENSITIVE_ORDER));
		}
		return byCategory;
	}

	private void printCommandLine(Command cmd) {
		console.writeln("  " + StringUtils.rightPad(primaryKeyword(cmd), 28, " ") + cmd.getDescription());
	}

	private void printCategory(CommandCategory category) {
		List<Command> inCategory = groupByCategory(loadAllCommands()).get(category);
		console.writeln(category.getDisplayName().toUpperCase(Locale.ROOT) + " (" + category.getAlias() + ")");
		console.writeln(category.getDescription());
		console.writeln("");
		if (inCategory.isEmpty()) {
			console.writeln(category.getEmptyState() != null ? category.getEmptyState() : "  (no commands loaded)");
		} else {
			inCategory.forEach(this::printCommandLine);
		}
		console.writeln("");
		console.writeln("HELP <command>   Detailed help for one of the commands above");
	}

	private void printAll() {
		Map<CommandCategory, List<Command>> byCategory = groupByCategory(loadAllCommands());
		int total = byCategory.values().stream().mapToInt(List::size).sum();
		console.writeln("Full BroadSQL command reference - " + total + " commands across " + CommandCategory.values().length + " categories.");
		console.writeln("");
		for (CommandCategory category : CommandCategory.values()) {
			List<Command> inCategory = byCategory.get(category);
			if (inCategory.isEmpty() && category.getEmptyState() == null) {
				continue;
			}
			console.writeln(category.getDisplayName().toUpperCase(Locale.ROOT));
			if (inCategory.isEmpty()) console.writeln(category.getEmptyState());
			inCategory.forEach(this::printCommandLine);
			console.writeln("");
		}
	}

	// ---------------------------------------------------------------------
	// HELP PATTERNS
	// ---------------------------------------------------------------------

	private void printPatterns() {
		console.writeln("PATTERNS - " + PATTERNS_DESCRIPTION);
		console.writeln("");
		console.writeln("LIST SOURCES - substituted with a quoted, comma-separated list in a SQL query:");
		console.writeln("  <@fileName>                       Values from a text file");
		console.writeln("  <@csv:<path>:<column>>            Values from a CSV column");
		console.writeln("  <@excel:<path>:<sheet>:<column>>  Values from an Excel column");
		console.writeln("  <@last:<column>>                  Values from the previous query result");
		console.writeln("  <@clipboard>                      Values from the clipboard");
		console.writeln("");
		console.writeln("EXECUTION SHORTCUTS");
		console.writeln("  @<fileName>                       Executes the SQL commands stored in a file");
		console.writeln("  /                                 Re-runs the last SQL query");
		console.writeln("  / <environment>                   Re-runs it against another environment");
		console.writeln("");
		console.writeln("EXAMPLES");
		console.writeln("  select count(*) from TEST where id in <@c:\\codes.txt>;");
		console.writeln("  SELECT * FROM ORDERS WHERE CUSTOMER_ID IN <@last:CUSTOMER_ID>;");
		console.writeln("  / QA");
		console.writeln("");
		console.writeln("SEE ALSO");
		console.writeln("  HELP " + CommandCategory.RUNNING_QUERIES.getAlias());
		console.writeln("  HELP " + CommandCategory.EXPORT_AND_LOCAL_DATA.getAlias());
	}

	// ---------------------------------------------------------------------
	// HELP FIND <text>
	// ---------------------------------------------------------------------

	private void printSearch(String text) {
		String needle = text.trim().toLowerCase(Locale.ROOT);
		Map<CommandCategory, List<Command>> byCategory = groupByCategory(loadAllCommands());
		console.writeln("Matches for \"" + text.trim() + "\"");
		console.writeln("");
		boolean any = false;
		for (CommandCategory category : CommandCategory.values()) {
			List<Command> matches = new ArrayList<>();
			for (Command cmd : byCategory.get(category)) {
				String haystack = (primaryKeyword(cmd) + " " + cmd.getSynonyms() + " " + cmd.getDescription())
						.toLowerCase(Locale.ROOT);
				if (haystack.contains(needle)) {
					matches.add(cmd);
				}
			}
			boolean categoryItselfMatches = category.getDisplayName().toLowerCase(Locale.ROOT).contains(needle)
					|| category.getDescription().toLowerCase(Locale.ROOT).contains(needle);
			if (!matches.isEmpty() || categoryItselfMatches) {
				any = true;
				console.writeln(category.getDisplayName().toUpperCase(Locale.ROOT));
				matches.forEach(this::printCommandLine);
				console.writeln("");
			}
		}
		for (String keyword : PATTERN_SEARCH_KEYWORDS) {
			if (keyword.contains(needle) || needle.contains(keyword)) {
				any = true;
				console.writeln("PATTERNS");
				console.writeln("  Use files, clipboard, spreadsheets and previous results in SQL - see HELP PATTERNS.");
				console.writeln("");
				break;
			}
		}
		for (String keyword : SHORTCUTS_SEARCH_KEYWORDS) {
			if (needle.contains(keyword)) {
				any = true;
				console.writeln(SHORTCUTS_TOPIC);
				console.writeln("  " + SHORTCUTS_DESCRIPTION + " See HELP " + SHORTCUTS_TOPIC + ".");
				console.writeln("");
				break;
			}
		}
		if (!any) {
			console.writeln("  (no matches - try HELP ALL)");
		}
	}

	// ---------------------------------------------------------------------
	// HELP <command>, "did you mean", and the existing H2 fallback
	// ---------------------------------------------------------------------

	/** All searchable topic labels (category display names/aliases-by-name and every command keyword). */
	private List<String> suggestionCandidates(List<Command> allCommands) {
		List<String> candidates = new ArrayList<>();
		for (CommandCategory category : CommandCategory.values()) {
			candidates.add(category.getDisplayName());
		}
		candidates.addAll(CommandCategory.aliasKeywords());
		candidates.add("PATTERNS");
		candidates.add(SHORTCUTS_TOPIC);
		candidates.add("ALL");
		candidates.add("FIND");
		for (Command cmd : allCommands) {
			if (CommandCategoryCatalog.isDocumented(cmd)) {
				candidates.addAll(Arrays.asList(cmd.getKeywords()));
			}
		}
		return candidates;
	}

	/** Simple, deliberately non-fuzzy-infrastructure suggestion: closest Levenshtein match, if close enough. */
	private String suggest(String input, List<String> candidates) {
		String best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (String candidate : candidates) {
			int distance = StringUtils.getLevenshteinDistance(input.toUpperCase(Locale.ROOT), candidate.toUpperCase(Locale.ROOT));
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}
		int threshold = Math.max(2, input.length() / 3);
		return (best != null && bestDistance > 0 && bestDistance <= threshold) ? best : null;
	}

	private void printHelpForCommand(String query, String commandName) {
		try {
			List<Command> allCommands = loadAllCommands();
			Command found = null;
			for (Command cmd : allCommands) {
				if (!CommandCategoryCatalog.isDocumented(cmd)) {
					continue;
				}
				for (String keyWord : cmd.getKeywords()) {
					if (keyWord.equalsIgnoreCase(commandName)) {
						found = cmd;
						break;
					}
				}
				if (found != null) {
					break;
				}
			}

			if (found != null) {
				console.write(found.displayDetailedHelp());
				console.writeln("    Category: " + CommandCategoryCatalog.categoryOf(found).getDisplayName());
				return;
			}

			if (printHelpForEndpointAlias(commandName)) {
				return;
			}

			console.println("No help available for command '" + commandName + "'.");
			String suggestion = suggest(commandName, suggestionCandidates(allCommands));
			if (suggestion != null) {
				console.println("Did you mean:");
				console.println("  " + suggestion.toUpperCase(Locale.ROOT));
			}
			if (this.sqlDatabase != null && this.sqlDatabase.getPlatform().getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_H2)) {
				console.println("Searching for H2 specific help for command " + commandName + "'.");
				boolean initLstMode = sqlDatabase.isListMode();
				sqlDatabase.setListMode(true);
				// This is BroadSQL's own internal lookup against H2's help tables, not a user data
				// query - <@last:column> must keep reflecting the most recent user query result
				// across a HELP lookup (see LastCaptureSuppressor).
				LastCaptureSuppressor.suppress();
				try {
					sqlDatabase.executeSelectQuery(query);
				} finally {
					LastCaptureSuppressor.allow();
				}
				sqlDatabase.setListMode(initLstMode);
			}
		} catch (BroadSQLException se) {
			console.error(se);
		}
	}

	/**
	 * {@code HELP <endpoint-alias>;} - SPRINT XT02A (URL-Native API Execution), section 3.5: broader
	 * than {@code SYNTAX <alias>;} ("how do I call it?") - explains what the endpoint does, then shows
	 * the same generated call syntax, consuming the exact same endpoint metadata {@code SYNTAX}/{@code
	 * RUN} use (never a separately duplicated description). Only consulted once no command keyword
	 * matched {@code commandName} above, and only when an API session is active - a bare {@code HELP
	 * <word>} with no active session and no matching command/category is still just "no help available",
	 * not a confusing API-specific error.
	 *
	 * @return {@code true} if {@code commandName} resolved to an endpoint alias for the active API's
	 *         and help was printed for it - {@code false} to fall through to the normal "no help
	 *         available" + suggestion path unchanged
	 */
	private boolean printHelpForEndpointAlias(String commandName) {
		com.upandcoding.broadsql.dao.api.ApiSessionContext context = com.upandcoding.broadsql.dao.api.ApiSessionContextHolder.get();
		if (context == null || getApiDefinitionsVault() == null) {
			return false;
		}
		try {
			// SPRINT XT02B, section 6: id/alias/name via the shared resolver, not alias-only.
			com.upandcoding.broadsql.dao.api.ApiEndpointReferenceResolver.Result result =
					com.upandcoding.broadsql.dao.api.ApiEndpointReferenceResolver.resolve(getApiDefinitionsVault(), context.getApiId(), commandName);
			if (result instanceof com.upandcoding.broadsql.dao.api.ApiEndpointReferenceResolver.Ambiguous ambiguous) {
				console.printBlock(com.upandcoding.broadsql.dao.api.ApiEndpointReferenceResolver.renderAmbiguous(getApiDefinitionsVault(), ambiguous.candidates()));
				return true;
			}
			if (!(result instanceof com.upandcoding.broadsql.dao.api.ApiEndpointReferenceResolver.Found found)) {
				return false;
			}
			com.upandcoding.broadsql.dao.api.model.ApiEndpoint endpoint = found.endpoint();
			console.writeln(endpoint.getMethod() + " " + endpoint.getName() + " (API " + context.getApiId() + ")");
			console.writeln("");
			console.writeln(com.upandcoding.broadsql.dao.api.invocation.ApiSyntaxFormatter.format(getApiDefinitionsVault(), endpoint));
			return true;
		} catch (BroadSQLException e) {
			console.error(e.getLocalizedMessage());
			return true;
		}
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);

		// Every form of HELP, the bare one included, starts immediately with the requested information: no banner
		if (!CommandUtils.isValidArgs(args)) {
			printCompactOverview();
			console.writeln("");
			return;
		}

		String first = args[0].trim();
		if ("ALL".equalsIgnoreCase(first) && args.length == 1) {
			printAll();
		} else if (args.length == 1 && ("PATTERNS".equalsIgnoreCase(first) || PATTERNS_ALIAS.equalsIgnoreCase(first))) {
			printPatterns();
		} else if (args.length == 1 && SHORTCUTS_TOPIC.equalsIgnoreCase(first)) {
			printShortcuts();
		} else if ("FIND".equalsIgnoreCase(first) && args.length > 1) {
			printSearch(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
		} else {
			String topic = String.join(" ", args);
			CommandCategory category = CommandCategory.findByName(topic);
			if (category != null) {
				printCategory(category);
			} else {
				printHelpForCommand(query, topic);
			}
		}
		console.writeln("");
	}

	@Override
	public String getDescription() {
		return ("Displays a list of supported command categories, or help for a specified command/category");
	}

	@Override
	public String getArguments() {
		return "command, category, ALL, PATTERNS, SHORTCUTS, or FIND <text> (all optional)";
	}

	@Override
	public String getExamples() {
		return "HELP;\n\tHELP EXPORT;\n\tHELP DESCR;\n\tHELP FIND excel;\n\tHELP ALL;\n\tHELP PATTERNS;\n\tHELP SHORTCUTS;";
	}
}
