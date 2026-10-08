package com.upandcoding.broadsql.controller.shell.reader;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandCategoryCatalog;
import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import com.upandcoding.broadsql.controller.shell.completion.EntityCompletionService;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * Table-name completion at every command position that expects a table of the current connection, from one
 * inventory ({@link #TABLE_COMMANDS}), through the shared {@link EntityCompletionService} and through real
 * JLine key dispatch, with the complete command registry loaded (so {@code ALL <table>} is resolved next to
 * {@code ALL APIS} and the other {@code ALL ...} commands exactly as in production).
 *
 * <p>Not table positions, and checked to stay uncompleted: {@code LINK TABLE <connection> <table>} (the table
 * is on the other connection), the patterns of {@code SHOW TABLES}/{@code SHOW VIEWS}, the texts of
 * {@code FIND COLUMN}/{@code FIND FK}/{@code FIND INDEX}, the file of {@code LOAD}, the connection of
 * {@code COMPARE TABLE STRUCTURE}, and the destination of {@code DUMP ... TO}.
 */
class TestTableArgumentCompletion {

	/** Every command form whose next argument is a table of the current connection (the audit's inventory). */
	static final List<String> TABLE_COMMANDS = List.of(
			"DESCR", "DESC", "DESCRIBE",
			"SHOW PK", "SHOW PRIMARY KEYS", "SH PK", "SHPK",
			"SHOW FK", "SHOW FOREIGN KEYS", "SH FK", "SHFK",
			"SHOW REFERENCES", "SHOW REFS", "SH REF", "SHREF",
			"SHOW INDEXES", "SHOW INDEX", "SH IX", "SHIX",
			"DUMP", "PULL",
			"LOAD", "LO", "BATCHLOAD", "BALO",
			"ALL", "CNT",
			"COMPARE TABLE STRUCTURE", "CM TA ST", "CMTAST");

	private DatabaseConnection db;
	private CommandList commandList;

	@BeforeEach
	void setUp() throws Exception {
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE PRODUCT (ID INT)", "CREATE TABLE CUSTOMER (ID INT)",
				"CREATE TABLE CUSTOMER_ARCHIVE (ID INT)", "CREATE SCHEMA SALES", "CREATE TABLE SALES.ORDERS (ID INT)",
				"CREATE TABLE SALES.ORDER_LINE (ID INT)");
		commandList = new CommandList();
		for (Class<? extends Command> type : CommandCategoryCatalog.registeredClasses().keySet()) {
			Command command = type.getDeclaredConstructor().newInstance();
			for (String keyword : command.getKeywords()) {
				commandList.put(keyword, command);
			}
		}
	}

	@AfterEach
	void tearDown() throws Exception {
		TestDatabaseConnections.close(db);
	}

	private List<String> complete(String line) {
		List<String> words = new DefaultParser().parse(line, line.length(), org.jline.reader.Parser.ParseContext.COMPLETE).words();
		return EntityCompletionService.standard(commandList, db).complete(words, words.size() - 1).stream()
				.map(CompletionCandidate::getValue).filter(v -> !List.of("/", "LIB").contains(v)).collect(Collectors.toList());
	}

	static List<String> tableCommands() {
		return TABLE_COMMANDS;
	}

	@ParameterizedTest
	@MethodSource("tableCommands")
	void completesAnUnqualifiedTable(String command) {
		Assertions.assertEquals(List.of("PRODUCT"), complete(command + " prod"));
	}

	@ParameterizedTest
	@MethodSource("tableCommands")
	void completesATableQualifiedWithItsSchema(String command) {
		Assertions.assertEquals(List.of("SALES.ORDERS"), complete(command + " SALES.ORDERS"));
		Assertions.assertEquals(new TreeSet<>(List.of("sales.ORDERS", "sales.ORDER_LINE")), new TreeSet<>(complete(command + " sales.ord")), "schema kept as typed, any case");
	}

	@ParameterizedTest
	@MethodSource("tableCommands")
	void offersEveryMatchingTable(String command) {
		Assertions.assertEquals(new TreeSet<>(List.of("CUSTOMER", "CUSTOMER_ARCHIVE")), new TreeSet<>(complete(command + " cus")));
	}

	@Test
	void theInventoryIsEveryRegisteredCommandThatDeclaresATableArgument() throws Exception {
		Set<String> declared = new TreeSet<>();
		for (Class<? extends Command> type : CommandCategoryCatalog.registeredClasses().keySet()) {
			Command command = type.getDeclaredConstructor().newInstance();
			List<CompletionEntityType> arguments = command.getCompletionArguments();
			boolean export = List.of("DUMP", "PULL").contains(command.getKeywords()[0]);
			if (export || (!arguments.isEmpty() && arguments.get(0) == CompletionEntityType.TABLE)) {
				declared.addAll(List.of(command.getKeywords()));
			}
		}
		Assertions.assertEquals(new TreeSet<>(TABLE_COMMANDS), declared);
	}

	@Test
	void positionsThatDoNotExpectATableOfThisConnectionAreNotCompletedWithTables() {
		for (String line : new String[] { "LINK TABLE prod", "LINK TABLE CONN prod", "SHOW TABLES prod", "SHOW VIEWS prod",
				"FIND COLUMN prod", "FIND FK prod", "FIND INDEX prod", "LOAD PRODUCT prod", "COMPARE TABLE STRUCTURE PRODUCT WITH prod",
				"COMPARE TABLE STRUCTURE PRODUCT prod", "DUMP PRODUCT TO prod", "SHOW PK PRODUCT prod", "CNT PRODUCT prod" }) {
			Assertions.assertFalse(complete(line).contains("PRODUCT"), line + " offered a table: " + complete(line));
		}
	}

	// ---- real JLine key dispatch ----

	/** Types each line (keystrokes ending with Enter) into one real JLine reader and returns what readLine returned for each. */
	private List<String> type(List<String> lines) throws Exception {
		PipedOutputStream keyboardOut = new PipedOutputStream();
		PipedInputStream keyboardIn = new PipedInputStream(keyboardOut);
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTestingWithRealKeyBindings(keyboardIn, new ByteArrayOutputStream(), null,
				null, commandList, db);
		try {
			byte[] bytes = String.join("", lines).getBytes(StandardCharsets.UTF_8);
			Thread feeder = new Thread(() -> {
				try {
					for (byte b : bytes) {
						keyboardOut.write(b);
						keyboardOut.flush();
						Thread.sleep(5);
					}
					Thread.sleep(60_000); // a finished writer breaks the pipe for the reader
				} catch (Exception e) {
					// interrupted at the end of the test
				}
			});
			feeder.setDaemon(true);
			feeder.start();
			List<String> result = new ArrayList<>();
			for (int i = 0; i < lines.size(); i++) {
				result.add(reader.readLine("test> "));
			}
			feeder.interrupt();
			return result;
		} finally {
			reader.close();
		}
	}

	@Test
	@Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	void tabCompletesTheTableThroughRealJLineKeyDispatchForEveryCommand() throws Exception {
		List<String> keystrokes = new ArrayList<>();
		List<String> expected = new ArrayList<>();
		for (String command : TABLE_COMMANDS) {
			keystrokes.add(command + " prod\t\n");
			expected.add(command + " PRODUCT ");
		}
		keystrokes.add("SHOW PK sales.orders\t\n");
		expected.add("SHOW PK sales.ORDERS ");
		Assertions.assertEquals(expected, type(keystrokes));
	}
}
