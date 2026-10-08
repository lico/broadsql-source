package com.upandcoding.broadsql.controller.shell.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.scripting.CommandJsEval;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandDescr;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandFindForeignKeys;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandFindIndexes;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowForeignKeys;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowIndexes;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowPrimaryKeys;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowReferences;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * Every keyword and alias of every registered command, from the registry itself ({@link CommandCategoryCatalog}):
 * dispatch selects that command, and its arguments are parsed exactly as after the canonical keyword. The shared
 * parser used to accept the first declared keyword that was a mere text prefix of the query, so {@code DESCRIBE X}
 * was parsed after {@code DESCR} (arguments {@code IBE X}... then {@code DESCRIBE}, {@code X}), and
 * {@code FIND INDEXES X} after {@code FIND INDEX} (arguments {@code FIND}, {@code X}); {@code JS EVAL} only ever
 * stripped its canonical keyword, so {@code JSEV 1+1} evaluated {@code JSEV 1+1}.
 */
class TestCommandAliasParsing {

	/** Every registered command with its keywords (canonical first). */
	private static Map<Class<? extends Command>, String[]> registry() {
		Map<Class<? extends Command>, String[]> registry = new LinkedHashMap<>();
		for (Class<? extends Command> commandClass : CommandCategoryCatalog.registeredClasses().keySet()) {
			try {
				registry.put(commandClass, commandClass.getDeclaredConstructor().newInstance().getKeywords());
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(commandClass.getName(), e);
			}
		}
		return registry;
	}

	static Stream<Arguments> everyKeyword() {
		List<Arguments> all = new ArrayList<>();
		registry().forEach((commandClass, keywords) -> {
			for (String keyword : keywords) {
				all.add(Arguments.of(commandClass.getSimpleName(), keyword, commandClass, keywords));
			}
		});
		return all.stream();
	}

	@ParameterizedTest(name = "{0}: {1}")
	@MethodSource("everyKeyword")
	void everyAliasParsesTheSameArgumentsAsTheCanonicalKeyword(String name, String keyword, Class<? extends Command> commandClass, String[] keywords) {
		String[] canonical = CommandUtils.getArgumentsFromQuery(keywords[0] + " ARG1 \"ARG 2\"", keywords);
		Assertions.assertArrayEquals(new String[] { "ARG1", "ARG 2" }, canonical, keywords[0]);
		Assertions.assertArrayEquals(canonical, CommandUtils.getArgumentsFromQuery(keyword + " ARG1 \"ARG 2\"", keywords), keyword);
		Assertions.assertArrayEquals(canonical, CommandUtils.getArgumentsFromQuery(keyword.toLowerCase() + "   ARG1 \"ARG 2\"  ", keywords), keyword + " lower case");
		Assertions.assertArrayEquals(canonical, CommandUtils.getArgumentsFromQuery(keyword + "\tARG1 \"ARG 2\"", keywords), keyword + " then a tab");
		Assertions.assertNull(CommandUtils.getArgumentsFromQuery(keyword, keywords), keyword + " alone has no argument");
	}

	@ParameterizedTest(name = "{0}: {1}")
	@MethodSource("everyKeyword")
	void everyAliasDispatchesToItsOwnCommand(String name, String keyword, Class<? extends Command> commandClass, String[] keywords) throws BroadSQLException {
		CommandList commands = new CommandList();
		registry().forEach((type, words) -> {
			try {
				Command command = type.getDeclaredConstructor().newInstance();
				for (String word : words) {
					commands.put(word, command);
				}
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
		Command dispatched = commands.getCommandClassFromName((keyword + " ARG1").toUpperCase());
		Assertions.assertNotNull(dispatched, keyword);
		Assertions.assertEquals(commandClass, dispatched.getClass(), keyword + " dispatched to " + dispatched.getClass().getSimpleName());
		Assertions.assertEquals(commandClass, commands.getCommandClassFromName(keyword.toUpperCase()).getClass(), keyword + " alone");
	}

	@Test
	void theReportedPrefixCollisionsAreWholeWordMatches() {
		String[] descr = new CommandDescr().getKeywords();
		Assertions.assertEquals("DESCRIBE", CommandUtils.matchingKeyword("DESCRIBE CUSTOMER", descr));
		Assertions.assertEquals("DESCR", CommandUtils.matchingKeyword("DESCR CUSTOMER", descr));
		Assertions.assertEquals("DESC", CommandUtils.matchingKeyword("desc CUSTOMER", descr));
		Assertions.assertNull(CommandUtils.matchingKeyword("DESCRIPTION", descr));
		Assertions.assertEquals("FIND INDEXES", CommandUtils.matchingKeyword("FIND INDEXES CUSTOMER", new CommandFindIndexes().getKeywords()));
		Assertions.assertEquals("FIND REFERENCES", CommandUtils.matchingKeyword("FIND REFERENCES CUSTOMER", new CommandFindForeignKeys().getKeywords()));
		Assertions.assertTrue(CommandUtils.startsWithKeyword("@myscript", "@"), "a symbolic keyword needs no boundary");
	}

	// ---- the same arguments give the same result, on a real database -------------------------------------------

	private DatabaseConnection db;
	private ConsoleSettings settings;

	@BeforeEach
	void setUp() throws BroadSQLException {
		settings = TestDatabaseConnections.defaultConsoleSettings();
		db = TestDatabaseConnections.connectInMemory(settings,
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(80))",
				"CREATE TABLE ORDERS (ID INT PRIMARY KEY, CUSTOMER_ID INT, CONSTRAINT FK_ORDER_CUST FOREIGN KEY (CUSTOMER_ID) REFERENCES CUSTOMER(ID))",
				"CREATE INDEX IX_ORDERS_CUST ON ORDERS(CUSTOMER_ID)");
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private String run(Class<? extends Command> type, String query) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandTestSupport.create(type, db, console, settings).execute(query);
		return console.getOutput();
	}

	static Stream<Arguments> readOnlyCommands() {
		return Stream.of(Arguments.of(CommandDescr.class, "CUSTOMER", "NAME"), Arguments.of(CommandShowPrimaryKeys.class, "CUSTOMER", "ID"),
				Arguments.of(CommandShowForeignKeys.class, "ORDERS", "FK_ORDER_CUST"), Arguments.of(CommandShowReferences.class, "CUSTOMER", "FK_ORDER_CUST"),
				Arguments.of(CommandShowIndexes.class, "ORDERS", "IX_ORDERS_CUST"), Arguments.of(CommandFindIndexes.class, "CUST", "IX_ORDERS_CUST"),
				Arguments.of(CommandFindForeignKeys.class, "CUST", "FK_ORDER_CUST"), Arguments.of(CommandJsEval.class, "print(6*7)", "42"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("readOnlyCommands")
	void everyAliasGivesTheCanonicalResult(Class<? extends Command> type, String arguments, String expected) throws Exception {
		String[] keywords = type.getDeclaredConstructor().newInstance().getKeywords();
		String canonical = run(type, keywords[0] + " " + arguments);
		Assertions.assertTrue(canonical.contains(expected), keywords[0] + ":\n" + canonical);
		for (String keyword : Arrays.copyOfRange(keywords, 1, keywords.length)) {
			Assertions.assertEquals(canonical, run(type, keyword + " " + arguments), keyword + " differs from " + keywords[0]);
		}
	}
}
