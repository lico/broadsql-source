package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.output.DisplayMode;
import com.upandcoding.broadsql.controller.shell.commands.core.help.CommandHelp;
import com.upandcoding.broadsql.controller.shell.output.DisplayLayout;
import com.upandcoding.broadsql.controller.shell.reader.ConsoleShortcuts;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * {@code HELP} (no argument) reaches through
 * {@code getConsoleCommandInterpreter().getCommands().getConsoleCommandLoader()} to enumerate
 * commands - the one command that needs the {@code CommandInterpreter}/{@code CommandList}/
 * {@code CommandLoader} chain wired, see {@link CommandTestSupport#createCommandInterpreter}.
 *
 * <p>{@code CommandLoader.loadAvailableCommands()} scans a literal {@code lib/broadsql.jar} file
 * path (the packaged release layout), which does not exist under {@code mvn test} - this is handled
 * as a real, already-existing robustness feature (a missing/unreadable jar is logged and skipped,
 * not fatal - see {@code docs/P0-commandes.md}, "A. Chargement robuste par JAR"), so {@code HELP}
 * still completes with an empty command list rather than throwing.
 */
class TestCommandHelp {

	private DatabaseConnection db;

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
		LastQueryResultHolder.set(null);
		DisplayLayout.configure(DisplayMode.AUTO, () -> 0);
	}

	private static CommandHelp newHelp(CapturingShellConsole console) {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		CommandHelp cmd = CommandTestSupport.create(CommandHelp.class, null, console, settings);
		cmd.setConsoleCommandInterpreter(CommandTestSupport.createCommandInterpreter(settings, console));
		return cmd;
	}

	@Test
	void bareHelpIsCompactOneLinePerCategoryAndShowsAliases() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandHelp cmd = newHelp(console);

		Assertions.assertDoesNotThrow(() -> cmd.execute(""));

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("CATEGORY") && output.contains("ALIAS") && output.contains("DESCRIPTION"),
				"expected the category/alias/description header row, got:\n" + output);
		for (CommandCategory category : CommandCategory.values()) {
			// One line per category: display name and alias on the SAME line, not split across two.
			String line = lineContaining(output, category.getDisplayName().toUpperCase());
			Assertions.assertNotNull(line, "expected a line for category '" + category.getDisplayName() + "', got:\n" + output);
			Assertions.assertTrue(line.contains(category.getAlias()),
					"expected category '" + category.getDisplayName() + "' line to also show its alias '"
							+ category.getAlias() + "' on the same line, got: " + line);
		}
		Assertions.assertTrue(output.contains("PATTERNS"), "expected the Patterns topic pointer, got:\n" + output);
		Assertions.assertTrue(output.contains("HELP FIND"), "expected the HELP FIND pointer, got:\n" + output);
		Assertions.assertTrue(output.contains("HELP ALL"), "expected the HELP ALL pointer, got:\n" + output);
		// Bare HELP must stay compact - not the old giant per-command dump, and not a wall of prose.
		Assertions.assertFalse(output.contains("MACRO COMMANDS"),
				"macro syntax moved to HELP PATTERNS, must not be in the compact bare HELP output:\n" + output);
		Assertions.assertFalse(output.contains("Specific help can be obtained"),
				"the old verbose intro paragraph must be gone - the table is self-explanatory, got:\n" + output);
	}

	@Test
	void noFormOfHelpShowsTheBanner() {
		for (String topicQuery : new String[] { "", "HELP", "HELP EXPORT", "HELP PATTERNS", "HELP PAT", "HELP ALL",
				"HELP FIND excel", "HELP DESCR", "HELP SHORTCUTS" }) {
			CapturingShellConsole console = new CapturingShellConsole();
			CommandHelp cmd = newHelp(console);
			Assertions.assertDoesNotThrow(() -> cmd.execute(topicQuery));
			Assertions.assertFalse(console.getOutput().contains("BroadSQL release"),
					"'" + topicQuery + "' must start immediately with the requested help, no banner - got:\n"
							+ console.getOutput());
		}
	}

	@Test
	void helpCategoryAliasesResolveTheIntendedCategoryCaseInsensitively() {
		for (CommandCategory category : CommandCategory.values()) {
			for (String query : new String[] { "HELP " + category.getAlias(), "HELP " + category.getAlias().toLowerCase() }) {
				CapturingShellConsole console = new CapturingShellConsole();
				CommandHelp cmd = newHelp(console);
				Assertions.assertDoesNotThrow(() -> cmd.execute(query));
				Assertions.assertTrue(console.getOutput().toUpperCase().contains(category.getDisplayName().toUpperCase()),
						"'" + query + "' should resolve to '" + category.getDisplayName() + "', got:\n" + console.getOutput());
			}
		}
	}

	private static String lineContaining(String text, String needle) {
		for (String line : text.split("\n")) {
			if (line.contains(needle)) {
				return line;
			}
		}
		return null;
	}

	@Test
	void helpCategoryPrintsThatCategoryOnly() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandHelp cmd = newHelp(console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("HELP EXPORT"));

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("EXPORT & LOCAL DATA"), "expected the matched category, got:\n" + output);
		Assertions.assertFalse(output.toUpperCase().contains("SQL LIBRARY"),
				"HELP <category> must not print unrelated categories, got:\n" + output);
	}

	@Test
	void helpAllPrintsTheFullReferenceHeader() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandHelp cmd = newHelp(console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("HELP ALL"));

		Assertions.assertTrue(console.getOutput().contains("Full BroadSQL command reference"),
				"expected the HELP ALL header, got:\n" + console.getOutput());
	}

	@Test
	void helpPatternsDocumentsMacroSyntax() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandHelp cmd = newHelp(console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("HELP PATTERNS"));

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("LIST SOURCES"), "expected the LIST SOURCES section, got:\n" + output);
		Assertions.assertTrue(output.contains("EXECUTION SHORTCUTS"), "expected the EXECUTION SHORTCUTS section, got:\n" + output);
		Assertions.assertTrue(output.contains("EXAMPLES"), "expected an EXAMPLES section, got:\n" + output);
		Assertions.assertTrue(output.contains("SEE ALSO"), "expected a SEE ALSO section, got:\n" + output);
		// LIST SOURCES must come before EXECUTION SHORTCUTS - the two concepts must not be flattened
		// back into one undifferentiated list.
		Assertions.assertTrue(output.indexOf("LIST SOURCES") < output.indexOf("EXECUTION SHORTCUTS"),
				"expected LIST SOURCES before EXECUTION SHORTCUTS, got:\n" + output);
		Assertions.assertTrue(output.contains("<@last:"), "expected <@last:...> documented, got:\n" + output);
		Assertions.assertTrue(output.contains("<@csv:"), "expected <@csv:...> documented, got:\n" + output);
		Assertions.assertTrue(output.contains("<@excel:"), "expected <@excel:...> documented, got:\n" + output);
		Assertions.assertTrue(output.contains("<@clipboard>"), "expected <@clipboard> documented, got:\n" + output);
		Assertions.assertTrue(output.contains("/ <environment>"), "expected / <environment> documented, got:\n" + output);
	}

	@Test
	void helpPatAliasIsEquivalentToHelpPatterns() {
		CapturingShellConsole viaAlias = new CapturingShellConsole();
		Assertions.assertDoesNotThrow(() -> newHelp(viaAlias).execute("HELP PAT"));
		CapturingShellConsole viaFullName = new CapturingShellConsole();
		Assertions.assertDoesNotThrow(() -> newHelp(viaFullName).execute("HELP PATTERNS"));
		Assertions.assertEquals(viaFullName.getOutput(), viaAlias.getOutput());
	}

	@Test
	void helpFindMatchesPatternsTopicByKeyword() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandHelp cmd = newHelp(console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("HELP FIND excel"));

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Matches for \"excel\""), "expected the search header, got:\n" + output);
		Assertions.assertTrue(output.contains("PATTERNS"), "expected the Patterns topic to match 'excel', got:\n" + output);
	}

	@Test
	void helpUnknownTopicSuggestsClosestCategory() {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandHelp cmd = newHelp(console);

		// No sqlDatabase wired (null) - must not NPE while still falling through gracefully, since
		// there is no H2 connection to fall back to.
		Assertions.assertDoesNotThrow(() -> cmd.execute("HELP EXPRT"));

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Did you mean"), "expected a suggestion, got:\n" + output);
		Assertions.assertTrue(output.toUpperCase().contains("EXPORT"), "expected 'export' suggested, got:\n" + output);
	}

	/**
	 * Regression test for the fix described in docs/TECHNICAL_CHANGE.md ("HELP must not replace
	 * LastQueryResult"): {@code HELP <unrecognized name>} falls through to H2's own internal
	 * {@code HELP} SQL statement (docs/P0-commandes.md is not relevant here - this is
	 * {@code CommandHelp.printHelpForCommand}'s existing "Searching for H2 specific help" branch), which
	 * goes through the exact same {@code DatabaseConnection#executeSelectQuery} ->
	 * {@code QueryExtractorToScreen} path as any user query - without {@code LastCaptureSuppressor},
	 * this internal lookup would silently overwrite {@code <@last:column>}'s source data.
	 */
	@Test
	void helpLookupDoesNotReplaceThePreviousUserQuerysLastResult() throws Exception {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		CapturingShellConsole console = new CapturingShellConsole();
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE CUSTOMER (CUSTOMER_ID VARCHAR(10))",
				"INSERT INTO CUSTOMER VALUES ('C001')");
		db.setCmdLineConsole(console);
		LastQueryResultHolder.set(null);

		// The user query LAST must still reflect afterward.
		db.executeSelectQuery("SELECT CUSTOMER_ID FROM CUSTOMER");
		LastQueryResult afterUserSelect = LastQueryResultHolder.get();
		Assertions.assertNotNull(afterUserSelect);
		Assertions.assertEquals("C001", afterUserSelect.rows().get(0)[0]);

		CommandHelp helpCmd = CommandTestSupport.create(CommandHelp.class, db, console, settings);
		helpCmd.setConsoleCommandInterpreter(CommandTestSupport.createCommandInterpreter(settings, console));

		// Not a recognized BroadSQL command name -> falls through to H2's own internal HELP lookup
		// (this test's DatabaseDefinition is H2, matching the branch's own guard). Must be "HELP <name>"
		// (not just "<name>"), matching how CommandInterpreter always calls execute() with the full
		// original input line - see CommandInterpreter.executeCommand - otherwise parseArgs() strips
		// nothing (the query doesn't start with the HELP keyword) and this exercises the bare-HELP
		// branch instead of the H2-fallback branch this test is actually meant to regression-test.
		Assertions.assertDoesNotThrow(() -> helpCmd.execute("HELP NOTAREALBROADSQLCOMMANDXYZ"));

		LastQueryResult afterHelp = LastQueryResultHolder.get();
		Assertions.assertSame(afterUserSelect, afterHelp,
				"HELP's internal H2 lookup must not replace the previous user query's LastQueryResult");
		Assertions.assertEquals("C001", afterHelp.rows().get(0)[0]);
	}

    @Test
    void officialHelpKeepsEmptyExtensionsAndExcludesCompatibilityDiscovery() throws Exception {
        for (String query : new String[] { "HELP ALL", "HELP EXT", "HELP IMPORT", "HELP QUERY", "HELP FIND load" }) {
            CapturingShellConsole console = new CapturingShellConsole();
            CommandHelp help = newHelp(console);
            // Production reads lib/broadsql.jar, absent under Maven. Seed the real loader's cache
            // with compiled catalog classes, including the deprecated compatibility adapter.
            var classes = help.getConsoleCommandInterpreter().getCommands().getConsoleCommandLoader().loadAvailableCommands();
            for (var commandClass : CommandCategoryCatalog.registeredClasses().keySet()) {
                classes.put(commandClass.getName(), CommandLoader.CMD_CORE);
            }
            help.execute(query);
            String output = console.getOutput();
            Assertions.assertFalse(output.contains("BATCHLOAD"), output);
            Assertions.assertFalse(output.contains("BALO"), output);
            if (query.equals("HELP ALL") || query.equals("HELP EXT")) {
                Assertions.assertTrue(output.contains(CommandCategory.EXTENSION_COMMANDS.getEmptyState()), output);
            }
            if (query.equals("HELP IMPORT")) Assertions.assertTrue(output.contains("LOAD"), output);
            if (query.equals("HELP QUERY")) Assertions.assertTrue(output.contains("EDIT"), output);
        }
    }

	// --- HELP; as tables, HELP SHORTCUTS, HELP ALL unchanged ------------------------------------------

	private static String run(String query) {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandHelp cmd = newHelp(console);
		// Seeds the loader cache directly: loadAvailableCommands() would first report the lib/broadsql.jar
		// that does not exist under Maven, and that report would land in the output under test
		var classes = Assertions.assertDoesNotThrow(() -> {
			java.lang.reflect.Field field = CommandLoader.class.getDeclaredField("availableClasses");
			field.setAccessible(true);
			@SuppressWarnings("unchecked")
			java.util.Map<String, String> map = (java.util.Map<String, String>) field.get(cmd.getConsoleCommandInterpreter().getCommands().getConsoleCommandLoader());
			return map;
		});
		for (var commandClass : CommandCategoryCatalog.registeredClasses().keySet()) {
			classes.put(commandClass.getName(), CommandLoader.CMD_CORE);
		}
		Assertions.assertDoesNotThrow(() -> cmd.execute(query));
		return console.getOutput();
	}

	/** Every non-blank line of {@code output} belongs to a | and - table, except the given free-text lines. */
	private static void assertOnlyTables(String output, String... freeText) {
		for (String line : output.split("\\R")) {
			if (line.isBlank() || java.util.Arrays.asList(freeText).contains(line)) {
				continue;
			}
			Assertions.assertTrue(line.startsWith("|") && line.endsWith("|"), "not a table line: [" + line + "] in\n" + output);
		}
	}

	@Test
	void bareHelpIsTwoTablesFromTheCategoryMetadataWithTheShortcutsTopic() {
		String output = run("HELP");
		assertOnlyTables(output);
		Assertions.assertTrue(output.contains("|CATEGORY") && output.contains("|ALIAS") && output.contains("|DESCRIPTION"), output);
		Assertions.assertTrue(output.contains("|TYPE") && output.contains("|TO SEE"), output);
		for (CommandCategory category : CommandCategory.values()) {
			Assertions.assertTrue(output.contains("|" + category.getDescription()), "category metadata not used for " + category + ":\n" + output);
		}
		Assertions.assertTrue(lineContaining(output, "|SHORTCUTS ") != null, "SHORTCUTS topic row missing:\n" + output);
		Assertions.assertTrue(lineContaining(output, "|HELP SHORTCUTS") != null, "HELP SHORTCUTS form missing:\n" + output);
		Assertions.assertFalse(output.contains("rows fetched"), "a reference table has no row count:\n" + output);
		Assertions.assertEquals(run(""), output, "HELP; and a bare HELP are the same screen");
	}

	@Test
	void bareHelpFollowsDisplayModeLikeEveryOtherTable() {
		DisplayLayout.configure(DisplayMode.COMPACT, () -> 70);
		for (String line : run("HELP").split("\\R")) {
			Assertions.assertTrue(line.length() < 70, "wider than the terminal: [" + line + "]");
		}
		DisplayLayout.configure(DisplayMode.WIDE, () -> 0);
		Assertions.assertTrue(run("HELP").contains("| CONNECTIONS "), "WIDE adds a space around the separators");
	}

	@Test
	void helpShortcutsPrintsTheSharedDefinitionsAsTablesWithoutAConnection() {
		String output = run("HELP SHORTCUTS");
		assertOnlyTables(output, "Depending on your terminal:",
				"On a line by itself: / runs the last query again, // shows it, exit ends BroadSQL. See HELP PATTERNS.");
		Assertions.assertTrue(output.contains("|KEY / SHORTCUT") && output.contains("|ACTION"), output);
		for (ConsoleShortcuts.Shortcut shortcut : ConsoleShortcuts.STANDARD) {
			Assertions.assertNotNull(lineContaining(output, "|" + shortcut.keys()), shortcut.keys() + " missing:\n" + output);
			Assertions.assertTrue(output.contains("|" + shortcut.action()), shortcut.action() + " missing:\n" + output);
		}
		String dependent = output.substring(output.indexOf("Depending on your terminal:"));
		for (ConsoleShortcuts.Shortcut shortcut : ConsoleShortcuts.TERMINAL_DEPENDENT) {
			Assertions.assertNotNull(lineContaining(dependent, "|" + shortcut.keys()), shortcut.keys() + " missing:\n" + output);
		}
		Assertions.assertEquals(output, run("help shortcuts"), "not case-sensitive");
		Assertions.assertFalse(output.contains("rows fetched"), output);
	}

	/** What HELP shows describes the product, never its development history (sprints, issues, internal names). */
	@Test
	void helpTablesContainNoDevelopmentTerminology() {
		java.util.regex.Pattern internal = java.util.regex.Pattern.compile("(?i)\\bSPRINT\\b|\\bXT0\\d|#\\d+|GitHub|\\bTODO\\b|\\bissue\\b|\\bticket\\b");
		for (String query : new String[] { "HELP", "HELP SHORTCUTS", "HELP PATTERNS" }) {
			String output = run(query);
			Assertions.assertFalse(internal.matcher(output).find(), query + " shows development terminology:\n" + output);
		}
		for (CommandCategory category : CommandCategory.values()) {
			String text = category.getDisplayName() + " " + category.getDescription() + " " + category.getEmptyState();
			Assertions.assertFalse(internal.matcher(text).find(), text);
		}
	}

	@Test
	void helpShortcutsIsDiscoverableFromSearchAndSuggestions() {
		Assertions.assertTrue(run("HELP FIND keyboard").contains("See HELP SHORTCUTS."));
		Assertions.assertTrue(run("HELP FIND shortcut").contains("See HELP SHORTCUTS."));
		String typo = run("HELP SHORTCUT");
		Assertions.assertTrue(typo.contains("Did you mean") && typo.contains("SHORTCUTS"), typo);
	}

	/** HELP ALL keeps its established plain layout: a header line, then per category its name and one indented line per command. */
	@Test
	void helpAllIsUnchanged() {
		StringBuilder expected = new StringBuilder();
		java.util.Map<CommandCategory, java.util.List<Command>> byCategory = new java.util.EnumMap<>(CommandCategory.class);
		for (CommandCategory category : CommandCategory.values()) {
			byCategory.put(category, new java.util.ArrayList<>());
		}
		int total = 0;
		for (Class<? extends Command> type : CommandCategoryCatalog.registeredClasses().keySet()) {
			Command cmd = Assertions.assertDoesNotThrow(() -> type.getDeclaredConstructor().newInstance());
			if (CommandCategoryCatalog.isDocumented(cmd)) {
				byCategory.get(CommandCategoryCatalog.categoryOf(cmd)).add(cmd);
				total++;
			}
		}
		expected.append("Full BroadSQL command reference - ").append(total).append(" commands across ")
				.append(CommandCategory.values().length).append(" categories.\n\n");
		for (CommandCategory category : CommandCategory.values()) {
			java.util.List<Command> commands = byCategory.get(category);
			commands.sort(java.util.Comparator.comparing(c -> c.getKeywords()[0], String.CASE_INSENSITIVE_ORDER));
			if (commands.isEmpty() && category.getEmptyState() == null) {
				continue;
			}
			expected.append(category.getDisplayName().toUpperCase(java.util.Locale.ROOT)).append("\n");
			if (commands.isEmpty()) {
				expected.append(category.getEmptyState()).append("\n");
			}
			for (Command cmd : commands) {
				expected.append("  ").append(org.apache.commons.lang3.StringUtils.rightPad(cmd.getKeywords()[0], 28, " "))
						.append(cmd.getDescription()).append("\n");
			}
			expected.append("\n");
		}
		expected.append("\n");
		Assertions.assertEquals(expected.toString(), run("HELP ALL").replace("\r\n", "\n"));
	}
}
