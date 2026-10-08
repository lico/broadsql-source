package com.upandcoding.broadsql.controller.shell.reader;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandDumpTable;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandDescr;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import com.upandcoding.broadsql.controller.shell.completion.EntityCompletionService;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * GitHub #153 - table-name autocomplete for {@code DESC} and {@code DUMP}, via
 * {@link CompletionEntityType#TABLE} and {@link EntityCompletionService#standard(CommandList, DatabaseConnection)} -
 * the same "a command declares its argument's entity type, the generic service supplies candidates"
 * mechanism {@code CONNECT}/{@code EDIT ENVIRONMENT}/{@code LIB EDIT} already use (see
 * {@link TestEntityCompletion}, "the reference implementation"), not a metadata lookup private to either
 * command. Exercised against a real, throwaway in-memory H2 database (same fixture policy as
 * {@code TestJdbcMetadataCompletionProvider} - the point of this completion is to be JDBC-metadata-driven,
 * so its tests should be too), covering both the plain service call and real JLine key dispatch.
 */
class TestEntityCompletionTable {

	private DatabaseConnection db;
	private CommandList commandList;

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private void givenConnectedDatabaseWithTables(String... createTableSql) throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory(createTableSql);
		commandList = new CommandList();
		for (Command command : new Command[] { new CommandDescr(), new CommandDumpTable() }) {
			for (String keyword : command.getKeywords()) {
				commandList.put(keyword, command);
			}
		}
	}

	private static List<String> words(String line) {
		return new DefaultParser().parse(line, line.length(), org.jline.reader.Parser.ParseContext.COMPLETE).words();
	}

	private List<String> entityValues(String line) {
		List<String> w = words(line);
		return EntityCompletionService.standard(commandList, db).complete(w, w.size() - 1).stream().map(CompletionCandidate::getValue)
				.collect(Collectors.toList());
	}

	// ---- DESC / DUMP: one matching table, several matching tables, no matching table ----

	@Test
	void descCompletesAUniquelyMatchingTable() throws BroadSQLException {
		givenConnectedDatabaseWithTables("CREATE TABLE PRODUCT (ID INT)", "CREATE TABLE CUSTOMER (ID INT)");
		Assertions.assertEquals(List.of("PRODUCT"), entityValues("DESC prod"));
	}

	@Test
	void dumpCompletesAUniquelyMatchingTable() throws BroadSQLException {
		givenConnectedDatabaseWithTables("CREATE TABLE PRODUCT (ID INT)", "CREATE TABLE CUSTOMER (ID INT)");
		Assertions.assertEquals(List.of("PRODUCT"), entityValues("DUMP prod"));
	}

	@Test
	void descAndDumpOfferBothCandidatesOnAnAmbiguousPrefix() throws BroadSQLException {
		givenConnectedDatabaseWithTables("CREATE TABLE CUSTOMER (ID INT)", "CREATE TABLE CUSTOMER_ARCHIVE (ID INT)", "CREATE TABLE PRODUCT (ID INT)");
		Assertions.assertEquals(List.of("CUSTOMER", "CUSTOMER_ARCHIVE"), entityValues("DESC CUS"));
		Assertions.assertEquals(List.of("CUSTOMER", "CUSTOMER_ARCHIVE"), entityValues("DUMP CUS"));
	}

	@Test
	void unmatchedPrefixYieldsNoCandidates() throws BroadSQLException {
		givenConnectedDatabaseWithTables("CREATE TABLE PRODUCT (ID INT)");
		Assertions.assertEquals(List.of(), entityValues("DESC ZZZ"));
		Assertions.assertEquals(List.of(), entityValues("DUMP ZZZ"));
	}

	@Test
	void matchingIsCaseInsensitiveAndInsertsTheCanonicalStoredValue() throws BroadSQLException {
		givenConnectedDatabaseWithTables("CREATE TABLE CUSTOMER (ID INT)");
		Assertions.assertEquals(List.of("CUSTOMER"), entityValues("DESC cust"));
	}

	@Test
	void viewsAreOfferedTooLikeJdbcMetadataCompletionProviderElsewhere() throws BroadSQLException {
		givenConnectedDatabaseWithTables("CREATE TABLE PRODUCT (ID INT)", "CREATE VIEW PRODUCT_VIEW AS SELECT * FROM PRODUCT");
		Assertions.assertEquals(List.of("PRODUCT", "PRODUCT_VIEW"), entityValues("DESC prod"));
	}

	// ---- no active connection: safe, non-blocking, no exception ----

	@Test
	void noActiveConnectionYieldsNoCandidatesAndNeverThrows() throws BroadSQLException {
		givenConnectedDatabaseWithTables("CREATE TABLE PRODUCT (ID INT)");
		Assertions.assertDoesNotThrow(() -> {
			List<String> w = words("DESC prod");
			// standard(commandList) - no connection overload - registers no TABLE provider at all.
			Assertions.assertEquals(List.of(), EntityCompletionService.standard(commandList).complete(w, w.size() - 1));
		});
	}

	@Test
	void aDisconnectedDatabaseYieldsNoCandidatesAndNeverThrows() throws BroadSQLException {
		givenConnectedDatabaseWithTables("CREATE TABLE PRODUCT (ID INT)");
		db.close();
		Assertions.assertDoesNotThrow(() -> Assertions.assertEquals(List.of(), entityValues("DESC prod")));
	}

	@Test
	void aNullSqlDatabaseYieldsNoCandidatesAndNeverThrows() throws BroadSQLException {
		givenConnectedDatabaseWithTables("CREATE TABLE PRODUCT (ID INT)");
		Assertions.assertDoesNotThrow(() -> {
			List<String> w = words("DESC prod");
			Assertions.assertEquals(List.of(), EntityCompletionService.standard(commandList, null).complete(w, w.size() - 1));
		});
	}

	// ---- grammar: only the declared argument position completes, exactly like every other entity command ----

	@Test
	void argumentsPastTheDeclaredOneAreNotCompleted() throws BroadSQLException {
		givenConnectedDatabaseWithTables("CREATE TABLE PRODUCT (ID INT)");
		Assertions.assertEquals(List.of(), entityValues("DESC PRODUCT extra"));
		Assertions.assertEquals(List.of(), entityValues("DUMP PRODUCT extra"));
	}

	@Test
	void anEmptyPrefixListsEveryTable() throws BroadSQLException {
		givenConnectedDatabaseWithTables("CREATE TABLE PRODUCT (ID INT)", "CREATE TABLE CUSTOMER (ID INT)");
		Assertions.assertEquals(List.of("CUSTOMER", "PRODUCT"), entityValues("DESC "));
	}

	// Existing (non-table) entity completion commands (CONNECT, EDIT ENVIRONMENT/GROUP, LIB EDIT, @script)
	// are unaffected by this addition: see TestEntityCompletion, unchanged by this sprint and re-run as
	// part of the same targeted regression pass.

	// ---- real JLine key dispatch, not merely a direct service call ----

	private List<String> type(String... keystrokes) throws Exception {
		PipedOutputStream keyboardOut = new PipedOutputStream();
		PipedInputStream keyboardIn = new PipedInputStream(keyboardOut);
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTestingWithRealKeyBindings(
				keyboardIn, new ByteArrayOutputStream(), null, null, commandList, db);
		try {
			byte[] bytes = String.join("", keystrokes).getBytes(StandardCharsets.UTF_8);
			Thread feeder = new Thread(() -> {
				try {
					for (byte b : bytes) {
						keyboardOut.write(b);
						keyboardOut.flush();
						Thread.sleep(5);
					}
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			feeder.setDaemon(true);
			feeder.start();
			List<String> lines = new java.util.ArrayList<>();
			for (int i = 0; i < keystrokes.length; i++) {
				lines.add(reader.readLine("test> "));
			}
			return lines;
		} finally {
			reader.close();
		}
	}

	@Test
	void tabOnDescCompletesAUniqueTableThroughRealJLineKeyDispatch() throws Exception {
		givenConnectedDatabaseWithTables("CREATE TABLE PRODUCT (ID INT)", "CREATE TABLE CUSTOMER (ID INT)");
		Assertions.assertEquals(List.of("DESC PRODUCT "), type("DESC prod\t\n"));
	}

	@Test
	void tabOnDumpCompletesAUniqueTableThroughRealJLineKeyDispatch() throws Exception {
		givenConnectedDatabaseWithTables("CREATE TABLE PRODUCT (ID INT)", "CREATE TABLE CUSTOMER (ID INT)");
		Assertions.assertEquals(List.of("DUMP PRODUCT "), type("DUMP prod\t\n"));
	}

	@Test
	void tabOnAnAmbiguousDescPrefixEntersNormalJLineMenuCompletion() throws Exception {
		givenConnectedDatabaseWithTables("CREATE TABLE CUSTOMER (ID INT)", "CREATE TABLE CUSTOMER_ARCHIVE (ID INT)");
		// Same ambiguous-TAB behavior as CONNECT in TestEntityCompletion: first candidate on the first
		// TAB, cycles to the next on a second TAB - JLine's own MENU_COMPLETE, nothing bespoke here.
		Assertions.assertEquals(List.of("DESC CUSTOMER ", "DESC CUSTOMER_ARCHIVE "),
				type("DESC CUS\t\n\n", "DESC CUS\t\t\n\n"));
	}

	@Test
	void tabOnDescWithNoActiveConnectionDoesNotThrowAndLeavesTheLineUnchanged() throws Exception {
		commandList = new CommandList();
		for (Command command : new Command[] { new CommandDescr(), new CommandDumpTable() }) {
			for (String keyword : command.getKeywords()) {
				commandList.put(keyword, command);
			}
		}
		db = null;
		Assertions.assertEquals(List.of("DESC prod"), type("DESC prod\t\n"));
	}
}
