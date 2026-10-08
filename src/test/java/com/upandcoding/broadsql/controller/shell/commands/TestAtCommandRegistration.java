package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExternalFile;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * SPRINT 1909S regression: {@code @} was made a visible command, which put it through {@code CommandLoader}'s
 * keyword validation, whose letters-only rule rejected the symbol. The class then never reached the registry, so
 * the interpreter did not classify {@code @QR1.sql} as a command and the SQL default sent it to the database.
 * These tests cover the loader rule, the registry, and the real interpreter's dispatch.
 */
class TestAtCommandRegistration {

	@TempDir
	Path tmp;

	private Path library;
	private ConsoleSettings settings;
	private DatabaseConnection db;
	private CapturingShellConsole console;
	private CommandInterpreter interpreter;

	@BeforeEach
	void setUp() throws BroadSQLException, IOException {
		library = Files.createDirectories(tmp.resolve("scripts"));
		settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(library.toString());
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");
		console = new CapturingShellConsole();
		interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, db);
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private CommandLoader newLoader(CapturingShellConsole loaderConsole) {
		CommandLoader loader = new CommandLoader();
		loader.shellConsoleSettings = settings;
		loader.console = loaderConsole;
		return loader;
	}

	private Path lib(String relative, String text) throws IOException {
		Path file = library.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	private int rows() throws BroadSQLException {
		CapturingShellConsole probe = new CapturingShellConsole();
		db.setCmdLineConsole(probe);
		db.executeSelectQuery("SELECT COUNT(*) FROM CUSTOMER");
		db.setCmdLineConsole(console);
		// the first number in a table cell (cells are bordered with |, see TableBorders)
		for (String token : probe.getOutput().split("[\\s|]+")) {
			if (token.matches("\\d+")) {
				return Integer.parseInt(token);
			}
		}
		throw new IllegalStateException(probe.getOutput());
	}

	private void assertNotTreatedAsSql() {
		Assertions.assertFalse(console.getOutput().contains("SQL ERROR"), "@ must never reach the SQL engine:\n" + console.getOutput());
		Assertions.assertFalse(console.getOutput().contains("Syntax error"), console.getOutput());
	}

	// ---- registration ----

	@Test
	void theAtCommandPassesTheLoadersKeywordValidationWithoutAnyError() throws Exception {
		CapturingShellConsole loaderConsole = new CapturingShellConsole();
		boolean accepted = newLoader(loaderConsole).checkNoErrorsInKeyword(CommandExternalFile.class);

		Assertions.assertTrue(accepted, loaderConsole.getOutput());
		Assertions.assertEquals("", loaderConsole.getOutput().trim(), "no invalid keyword error may be reported at startup");
	}

	@Test
	void everyRegisteredBuiltInCommandPassesTheLoaderTogetherWithNoError() throws Exception {
		CapturingShellConsole loaderConsole = new CapturingShellConsole();
		CommandLoader loader = newLoader(loaderConsole);
		for (Class<? extends Command> commandClass : CommandCategoryCatalog.registeredClasses().keySet()) {
			Assertions.assertTrue(loader.checkNoErrorsInKeyword(commandClass), commandClass.getName() + ": " + loaderConsole.getOutput());
		}
		Assertions.assertEquals("", loaderConsole.getOutput().trim(), loaderConsole.getOutput());
	}

	@Test
	void theGeneralKeywordRuleIsUnchangedAndOnlyBuiltInClassesMayUseTheAtSymbol() {
		Assertions.assertTrue(CommandLoader.isValidKeyword("@", CommandExternalFile.class));
		Assertions.assertFalse(CommandLoader.isValidKeyword("@", String.class), "a class outside BroadSQL can never take a symbolic keyword");
		Assertions.assertFalse(CommandLoader.isValidKeyword("@@", CommandExternalFile.class));
		Assertions.assertFalse(CommandLoader.isValidKeyword("#", CommandExternalFile.class));
		Assertions.assertFalse(CommandLoader.isValidKeyword("SHOW-TABLES", CommandExternalFile.class));
		Assertions.assertFalse(CommandLoader.isValidKeyword(null, CommandExternalFile.class));
		Assertions.assertTrue(CommandLoader.isValidKeyword("SHOW TABLES", String.class));
	}

	@Test
	void theRegistryLocatesTheAtCommandForAnyAtLine() throws Exception {
		Assertions.assertTrue(interpreter.getCommands().containsKey("@"));
		for (String line : new String[] { "@QR1.SQL", "@", "@./FOO.BSQL", "@\"FOLDER/MY SCRIPT.BSQL\" ARG1 \"ARG 2\"", "@C:\\TEMP\\X.BSQL" }) {
			Assertions.assertTrue(interpreter.getCommands().getCommandClassFromName(line) instanceof CommandExternalFile, line);
		}
	}

	// ---- dispatch through the real interpreter ----

	@Test
	void anAtLineIsDispatchedAsACommandNotAsSql() throws Exception {
		lib("QR1.sql", "INSERT INTO CUSTOMER VALUES (1, 'qr1');");

		interpreter.executeMultiStatementLine("@QR1.sql;");

		assertNotTreatedAsSql();
		Assertions.assertEquals(1, rows(), console.getOutput());
	}

	@Test
	void theTerminatingSemicolonIsOptionalForTheSplitter() throws Exception {
		lib("QR1.sql", "INSERT INTO CUSTOMER VALUES (1, 'qr1');");
		interpreter.executeMultiStatementLine("@QR1.sql");
		assertNotTreatedAsSql();
		Assertions.assertEquals(1, rows(), console.getOutput());
	}

	@Test
	void aQuotedLibraryPathWithSpacesAndQuotedArgumentsIsParsedAndBound() throws Exception {
		lib("folder/my script.bsql", "INSERT INTO CUSTOMER VALUES (${id}, ${name});");

		interpreter.executeMultiStatementLine("@\"folder/my script.bsql\" id=5 name='arg 2';");

		assertNotTreatedAsSql();
		CapturingShellConsole probe = new CapturingShellConsole();
		db.setCmdLineConsole(probe);
		db.executeSelectQuery("SELECT NAME FROM CUSTOMER WHERE ID = 5");
		db.setCmdLineConsole(console);
		Assertions.assertTrue(probe.getOutput().contains("arg 2"), probe.getOutput());
	}

	@Test
	void explicitRelativeAtTheInteractivePromptIsRelativeToTheWorkingDirectory() throws Exception {
		Path probe = Path.of(System.getProperty("user.dir")).resolve("target").resolve("at-registration-probe-1909s.bsql");
		Files.createDirectories(probe.getParent());
		Files.writeString(probe, "INSERT INTO CUSTOMER VALUES (9, 'cwd');");
		try {
			interpreter.executeMultiStatementLine("@./target/at-registration-probe-1909s.bsql;");
		} finally {
			Files.deleteIfExists(probe);
		}
		assertNotTreatedAsSql();
		Assertions.assertEquals(1, rows(), console.getOutput());
	}

	@Test
	void anAbsolutePathRunsAnExternalScript() throws Exception {
		Path external = Files.createDirectories(tmp.resolve("elsewhere")).resolve("ext.bsql");
		Files.writeString(external, "INSERT INTO CUSTOMER VALUES (7, 'external');");

		interpreter.executeMultiStatementLine("@" + external + ";");

		assertNotTreatedAsSql();
		Assertions.assertEquals(1, rows(), console.getOutput());
	}

	@Test
	void noExtensionIsGuessedAndTheFailureIsAScriptErrorNeverASqlError() throws Exception {
		lib("foo.bsql", "INSERT INTO CUSTOMER VALUES (1, 'x');");
		lib("bar.sql", "INSERT INTO CUSTOMER VALUES (2, 'y');");

		interpreter.executeMultiStatementLine("@foo;");
		interpreter.executeMultiStatementLine("@bar;");

		assertNotTreatedAsSql();
		Assertions.assertTrue(console.getOutput().contains("Script not found"), console.getOutput());
		Assertions.assertEquals(0, rows(), "neither foo.bsql nor bar.sql may have been run");
	}

	@Test
	void libRunIsUnaffectedAndEquivalent() throws Exception {
		lib("QR1.sql", "INSERT INTO CUSTOMER VALUES (${id}, 'lib');");

		interpreter.executeMultiStatementLine("LIB RUN QR1.sql id=3;");

		assertNotTreatedAsSql();
		Assertions.assertEquals(1, rows(), console.getOutput());
	}
}
