package com.upandcoding.broadsql.controller.shell.commands;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandExport;
import com.upandcoding.broadsql.controller.shell.commands.core.help.CommandHelp;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandEnv;
import com.upandcoding.broadsql.controller.shell.commands.core.set.CommandSetSeparator;
import com.upandcoding.broadsql.controller.shell.completion.BroadSqlCommandCompletionProvider;
import com.upandcoding.broadsql.controller.shell.completion.CompletionContext;
import com.upandcoding.broadsql.controller.shell.completion.EntityCompletionService;
import com.upandcoding.broadsql.controller.shell.completion.SqlCompletionLexer;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * SPRINT 2309T: what HELP and TAB completion teach after the sprint - DUMP (with {@code /}, {@code LIB},
 * {@code TO}/{@code AS} and the CSV/TEXT/JSON formats) and ENV are current; EXPORT and SET SEPARATOR are
 * compatibility-only (still registered and executable, never promoted).
 */
class TestSprint2309THelpAndCompletion {

	@TempDir
	Path tmp;

	private ConsoleSettings settings;
	private CapturingShellConsole console;
	private CommandInterpreter interpreter;
	private DatabaseConnection db;

	@BeforeEach
	void setUp() throws Exception {
		settings = TestDatabaseConnections.defaultConsoleSettings();
		Path library = Files.createDirectories(tmp.resolve("scripts"));
		Files.writeString(library.resolve("sales.sql"), "SELECT 1;");
		settings.setScriptsLibraryPath(library.toString());
		console = new CapturingShellConsole();
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE CUSTOMER (ID INT)");
		interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, db);
	}

	// --- Documentation status -------------------------------------------------------------------------

	@Test
	void exportAndSetSeparatorAreExecutableButUndocumented() {
		Assertions.assertFalse(CommandCategoryCatalog.isDocumented(new CommandExport()));
		Assertions.assertFalse(CommandCategoryCatalog.isDocumented(new CommandSetSeparator()));
		Assertions.assertTrue(CommandCategoryCatalog.isRegistered(CommandExport.class), "still registered, so still executable");
		Assertions.assertTrue(CommandCategoryCatalog.isRegistered(CommandSetSeparator.class));
		Assertions.assertTrue(interpreter.getCommands().get("EXPORT") instanceof CommandExport);
		Assertions.assertTrue(interpreter.getCommands().get("SET SEPARATOR") instanceof CommandSetSeparator);
	}

	@Test
	void envIsADocumentedEnvironmentsCommand() {
		Assertions.assertTrue(CommandCategoryCatalog.isDocumented(new CommandEnv()));
		Assertions.assertEquals(CommandCategory.ENVIRONMENTS, CommandCategoryCatalog.categoryOf(CommandEnv.class));
		Assertions.assertEquals("ENV", new CommandEnv().getKeywords()[0], "canonical keyword");
	}

	// --- HELP -----------------------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private String help(String query) throws Exception {
		CapturingShellConsole helpConsole = new CapturingShellConsole();
		CommandInterpreter helpInterpreter = CommandTestSupport.createCommandInterpreter(settings, helpConsole);
		Field field = CommandLoader.class.getDeclaredField("availableClasses");
		field.setAccessible(true);
		HashMap<String, String> classes = (HashMap<String, String>) field.get(helpInterpreter.getCommands().getConsoleCommandLoader());
		for (Class<? extends Command> commandClass : CommandCategoryCatalog.registeredClasses().keySet()) {
			classes.put(commandClass.getName(), commandClass.getName());
		}
		CommandHelp cmd = CommandTestSupport.create(CommandHelp.class, null, helpConsole, settings);
		cmd.setConsoleCommandInterpreter(helpInterpreter);
		cmd.execute(query);
		return helpConsole.getOutput();
	}

	@Test
	void helpAllTeachesDumpAndEnvButNotExportOrSetSeparator() throws Exception {
		String all = help("HELP ALL");
		Assertions.assertTrue(all.contains("  DUMP "), all);
		Assertions.assertTrue(all.contains("  ENV "), all);
		Assertions.assertTrue(all.contains("  CONNECT "), all);
		Assertions.assertFalse(all.contains("  EXPORT "), all);
		Assertions.assertFalse(all.contains("  SET SEPARATOR "), all);
	}

	@Test
	void helpEnvShowsTheEnvCommandsDetailedHelp() throws Exception {
		for (String query : new String[] { "HELP ENV", "HELP ENVT", "HELP CONNECT ENVIRONMENT", "HELP env" }) {
			String help = help(query);
			Assertions.assertTrue(help.contains("Synonyms: ENVT, CONNECT ENVIRONMENT"), query + ": " + help);
			Assertions.assertTrue(help.contains("ENV <environment>"), query + ": " + help);
			Assertions.assertFalse(help.contains("ENVIRONMENTS ("), "must not be the category listing: " + query + ": " + help);
		}
	}

	@Test
	void theEnvironmentsCategoryIsReachedThroughItsOwnAlias() throws Exception {
		String category = help("HELP ENVS");
		Assertions.assertTrue(category.contains("ENVIRONMENTS (ENVS)"), category);
		Assertions.assertTrue(category.contains("  ENV "), "ENV is listed with the other Environment commands: " + category);
		Assertions.assertTrue(help("HELP ENVIRONMENTS").contains("ENVIRONMENTS (ENVS)"));
		Assertions.assertTrue(help("HELP CONN").contains("CONNECTIONS (CONN)"), "other category aliases are unchanged");
	}

	@Test
	void helpForHiddenCommandsDoesNotDocumentThem() throws Exception {
		Assertions.assertFalse(help("HELP SET SEPARATOR").contains("Synonyms:"));
		Assertions.assertFalse(help("HELP EXTRACT").contains("Synonyms:"));
	}

	@Test
	void helpDumpShowsTheCanonicalGrammar() throws Exception {
		String dump = help("HELP DUMP");
		Assertions.assertTrue(dump.contains("DUMP /"), dump);
		Assertions.assertTrue(dump.contains("DUMP LIB"), dump);
		Assertions.assertTrue(dump.contains("TEXT"), dump);
		Assertions.assertTrue(dump.contains("PULL"), "PULL is mentioned as the compatibility alias: " + dump);
	}

	@Test
	void helpConnectDistinguishesConnectFromEnv() throws Exception {
		String connect = help("HELP CONNECT");
		Assertions.assertTrue(connect.contains("ENV <environment>"), connect);
		Assertions.assertTrue(connect.contains("OPEN, CONN, CON"), connect);
		String envt = help("HELP ENVT");
		Assertions.assertTrue(envt.contains("CONNECT ENVIRONMENT"), envt);
	}

	// --- Command-name completion ----------------------------------------------------------------------

	private List<String> commandNames(String line) {
		CompletionContext context = SqlCompletionLexer.analyze(line, line.length(), false);
		List<String> result = new ArrayList<>();
		for (CompletionCandidate c : new BroadSqlCommandCompletionProvider(interpreter.getCommands()).complete(context)) {
			result.add(c.getValue());
		}
		return result;
	}

	@Test
	void commandCompletionNoLongerPromotesExportOrSetSeparator() {
		Assertions.assertFalse(commandNames("EX").contains("EXPORT"), commandNames("EX").toString());
		Assertions.assertFalse(commandNames("EX").contains("EXTRACT"), commandNames("EX").toString());
		Assertions.assertFalse(commandNames("SEP").contains("SEPARATOR"), commandNames("SEP").toString());
		Assertions.assertFalse(commandNames("SET ").contains("SEPARATOR"), commandNames("SET ").toString());
		Assertions.assertTrue(commandNames("SET ").contains("SCHEMA"), "documented SET commands still complete");
		Assertions.assertTrue(commandNames("DU").contains("DUMP"));
		Assertions.assertTrue(commandNames("EN").contains("ENV"));
	}

	// --- DUMP / PULL argument completion --------------------------------------------------------------

	private List<String> complete(String... words) {
		EntityCompletionService service = EntityCompletionService.standard(interpreter.getCommands(), db);
		List<String> result = new ArrayList<>();
		for (CompletionCandidate c : service.complete(List.of(words), words.length - 1)) {
			result.add(c.getValue());
		}
		return result;
	}

	@Test
	void dumpSourcePositionOffersSlashLibAndTables() {
		List<String> offered = complete("DUMP", "");
		Assertions.assertTrue(offered.containsAll(List.of("/", "LIB", "CUSTOMER")), offered.toString());
		Assertions.assertEquals(List.of("LIB"), complete("DUMP", "LI"));
		Assertions.assertEquals(List.of("sales.sql"), complete("DUMP", "LIB", ""));
	}

	@Test
	void dumpOffersToAsAndFormats() {
		Assertions.assertEquals(List.of("TO", "AS"), complete("DUMP", "CUSTOMER", ""));
		Assertions.assertEquals(List.of("TO", "AS"), complete("DUMP", "/", ""));
		Assertions.assertEquals(List.of("TO", "AS"), complete("DUMP", "LIB", "sales.sql", ""));
		Assertions.assertEquals(List.of("AS"), complete("DUMP", "/", "TO", "customers", ""));
		Assertions.assertEquals(List.of(), complete("DUMP", "/", "TO", ""), "destination names are free text");
		List<String> formats = complete("DUMP", "/", "AS", "");
		Assertions.assertTrue(formats.containsAll(List.of("CSV", "TEXT", "JSON", "XLSX", "ODS")), formats.toString());
		Assertions.assertFalse(formats.contains("TXT"), "TEXT is taught, TXT is only accepted");
		Assertions.assertEquals(List.of("TEXT"), complete("DUMP", "CUSTOMER", "AS", "T"));
		Assertions.assertEquals(List.of(), complete("DUMP", "(SELECT", "*", "FROM", ""), "inside a query: SQL completion");
	}

	@Test
	void pullSharesTheSameArgumentCompletion() {
		Assertions.assertEquals(complete("DUMP", "/", "AS", ""), complete("PULL", "/", "AS", ""));
		Assertions.assertTrue(complete("PULL", "").contains("CUSTOMER"));
	}
}
