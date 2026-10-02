package com.upandcoding.broadsql.controller.shell.reader;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowEndpoint;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowEndpoints;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * UX improvement following SPRINT 0917-02 - a natural keyboard way to choose among ambiguous TAB
 * candidates instead of leaving the user with a static list and forcing them to type further
 * distinguishing characters.
 *
 * <p>Investigated empirically (not from reading JLine's source) against the real JLine 3.26.3
 * production path, using {@link JLineConsoleLineReader#createForTestingWithRealKeyBindings} - the same
 * real, key-binding-capable terminal seam {@code TestJLineConsoleLineReaderRealKeyBindings} introduced
 * for the {@code CASE_INSENSITIVE} defect. Findings: with only {@code CASE_INSENSITIVE} set (the
 * pre-existing configuration), a first TAB on an ambiguous prefix only lists candidates statically
 * (JLine's default {@code AUTO_LIST}), exactly the reported UX gap; a second TAB then happens to enter
 * JLine's menu-selection mode on its own ({@code AUTO_MENU}, on by default), which is what actually
 * causes the malformed-looking behavior the task description warns about if not handled deliberately.
 * Setting {@code LineReader.Option.MENU_COMPLETE} makes the *first* TAB already enter menu-selection
 * mode directly (inserting the first candidate, highlighted), with every further TAB cycling forward
 * and Shift-TAB ({@code \033[Z}, JLine's own default keymap - no custom binding added) cycling backward;
 * both directions replace exactly the ambiguous token, never corrupting the rest of the line. A unique
 * candidate is entirely unaffected - {@code MENU_COMPLETE} only changes what happens once there is more
 * than one match.
 *
 * <p><b>Enter while a candidate is highlighted in the menu behaves like JLine/readline's standard
 * menu-select convention</b>: it accepts the highlighted candidate and closes the menu, exactly as
 * typing any further character would - it does not itself submit the line. A real interactive user
 * simply keeps typing (the common case) or presses Enter a second time to actually run the line; this
 * is not custom behavior introduced by this change, it is JLine's own menu-selection semantics,
 * confirmed against the real key-dispatch path in every test below.
 *
 * <p>No change was made to {@code BroadSqlJLineCompleter}, {@code CompletionEngine}, or any
 * {@code CompletionProvider} - selection/navigation among already-produced candidates is a
 * terminal/JLine responsibility, kept out of the completion engine per the architecture this sprint
 * established.
 */
class TestJLineConsoleLineReaderMenuCompletion {

	private DatabaseConnection db;

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private static CommandList commandListWithShowEndpointAndShowEndpoints() {
		CommandList commandList = new CommandList();
		for (Command command : new Command[] { new CommandShowEndpoint(), new CommandShowEndpoints() }) {
			for (String keyword : command.getKeywords()) {
				commandList.put(keyword, command);
			}
		}
		return commandList;
	}

	private String driveKeystrokes(CommandList commandList, DatabaseConnection sqlDatabase, String keystrokes) throws Exception {
		PipedOutputStream keyboardOut = new PipedOutputStream();
		PipedInputStream keyboardIn = new PipedInputStream(keyboardOut);
		ByteArrayOutputStream screen = new ByteArrayOutputStream();

		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTestingWithRealKeyBindings(
				keyboardIn, screen, null, null, commandList, sqlDatabase);
		try {
			byte[] bytes = keystrokes.getBytes(StandardCharsets.UTF_8);
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
			return reader.readLine("test> ");
		} finally {
			reader.close();
		}
	}

	// Test 1 - unique candidate: must still complete directly, no menu forced on the user.
	@Test
	void uniqueCandidateStillCompletesDirectlyWithoutEnteringAMenu() throws Exception {
		Assertions.assertEquals("SELECT ", driveKeystrokes(new CommandList(), null, "sel\t\n"));
	}

	// Test 2 - two ambiguous BroadSQL commands: SHOW ENDPOINT / SHOW ENDPOINTS.
	@Test
	void ambiguousBroadSqlCommandFirstTabSelectsFirstCandidate() throws Exception {
		// Enter while the menu is showing accepts the highlighted candidate and closes the menu (JLine's
		// own menu-select convention) - a second Enter is what actually submits the line.
		Assertions.assertEquals("show ENDPOINT ", driveKeystrokes(commandListWithShowEndpointAndShowEndpoints(), null, "show end\t\n\n"));
	}

	@Test
	void ambiguousBroadSqlCommandRepeatedTabCyclesToTheOtherCandidate() throws Exception {
		Assertions.assertEquals("show ENDPOINTS ", driveKeystrokes(commandListWithShowEndpointAndShowEndpoints(), null, "show end\t\t\n\n"));
	}

	@Test
	void ambiguousBroadSqlCommandThirdTabWrapsBackToTheFirstCandidate() throws Exception {
		Assertions.assertEquals("show ENDPOINT ", driveKeystrokes(commandListWithShowEndpointAndShowEndpoints(), null, "show end\t\t\t\n\n"));
	}

	@Test
	void shiftTabCyclesBackwardToThePreviousCandidate() throws Exception {
		// \033[Z is the standard terminal escape sequence for Shift-TAB; JLine's own default emacs
		// keymap already binds it to reverse-menu-complete - no custom key binding added for this.
		Assertions.assertEquals("show ENDPOINT ", driveKeystrokes(commandListWithShowEndpointAndShowEndpoints(), null, "show end\t\t[Z\n\n"));
	}

	@Test
	void continuingToTypeAfterCyclingAcceptsTheHighlightedCandidateWithoutCorruptingTheLine() throws Exception {
		// A single Enter is enough here: typing ';' exits the menu immediately (unlike Enter, it does not
		// need a second keystroke to submit), so the malformed states the task warns about
		// ("show endSHOW ENDPOINT", "SHOW ENDPOINTPOINTS") are exactly what this test protects against.
		Assertions.assertEquals("show ENDPOINTS ;", driveKeystrokes(commandListWithShowEndpointAndShowEndpoints(), null, "show end\t\t;\n"));
	}

	// Test 4 - case-insensitive ambiguous input: lowercase typed, not only uppercase.
	@Test
	void lowercaseAmbiguousInputStillEntersTheSameSelectionMechanism() throws Exception {
		Assertions.assertEquals("show ENDPOINTS ", driveKeystrokes(commandListWithShowEndpointAndShowEndpoints(), null, "show end\t\t\n\n"));
	}

	// Test 3 - ambiguous JDBC metadata objects: CUSTOMER / CUSTOMER_ADDRESS.
	@Test
	void ambiguousJdbcTableCandidatesCycleWithTheSameMechanism() throws Exception {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (CUSTOMER_ID INT)",
				"CREATE TABLE CUSTOMER_ADDRESS (ID INT)");
		Assertions.assertEquals("SELECT * FROM CUSTOMER ",
				driveKeystrokes(new CommandList(), db, "SELECT * FROM cust\t\n\n"));
	}

	@Test
	void ambiguousJdbcTableCandidatesCycleToTheSecondMatch() throws Exception {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (CUSTOMER_ID INT)",
				"CREATE TABLE CUSTOMER_ADDRESS (ID INT)");
		Assertions.assertEquals("SELECT * FROM CUSTOMER_ADDRESS ",
				driveKeystrokes(new CommandList(), db, "SELECT * FROM cust\t\t\n\n"));
	}
}
