package com.upandcoding.broadsql.controller.shell.commands;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.Session;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * Covers the fix for docs/TODO.md item 7 ("USERS_SCRIPT sometimes not re-executed on reconnect"):
 * {@code CommandInterpreter.executeCommand()} must re-run the current platform's login script
 * whenever it silently reconnects a dropped connection, and the new platform's login script
 * whenever a command changes the platform - including from inside a nested script, not just a
 * directly typed {@code CONNECT} (see {@code CommandExternalFile}).
 *
 * <p>Real, AES-encrypted-CDF-backed login script reading ({@code USERS_SCRIPT}) is already out of
 * reach for this test tier (see docs/TESTS_STRATEGY.md, "What's still genuinely out of reach") - a
 * {@link RecordingSession} stands in for it here, recording how many times, and for which platform,
 * the interpreter asked for the login script. Every actual command (the login script's own
 * statement, and the command that triggered it) still runs for real, through the real
 * {@code executeCommand()} pipeline, against a real in-memory H2 database.
 */
class TestCommandInterpreterLoginScriptRerun {

	private DatabaseConnection db;
	private CapturingShellConsole console;
	private ConsoleSettings consoleSettings;
	private CommandInterpreter interpreter;
	private RecordingSession session;

	@BeforeEach
	void setUp() throws BroadSQLException {
		consoleSettings = TestDatabaseConnections.defaultConsoleSettings();
		console = new CapturingShellConsole();
		db = TestDatabaseConnections.connectInMemory(consoleSettings);
		interpreter = CommandTestSupport.createCommandInterpreter(consoleSettings, console, db);
		interpreter.setPlatform(db.getPlatform().getId());
		session = new RecordingSession(consoleSettings, "CREATE TABLE LOGIN_MARKER(N INT)");
		interpreter.session = session;
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void reRunsTheLoginScriptAfterASilentReconnectAndStillRunsTheTriggeringCommand() throws Exception {
		// Simulates a server-side session dying mid-work (an Oracle IDLE_TIME profile limit, a
		// dropped idle TCP connection...) while sqlDatabase still believes it is connected.
		TestDatabaseConnections.killUnderlyingConnection(db);

		interpreter.setQuery("CREATE TABLE PENDING_MARKER(N INT)");
		interpreter.executeCommand();

		Assertions.assertEquals(1, session.callCount,
				"the login script must re-run exactly once after the silent reconnect");
		Assertions.assertEquals(db.getPlatform().getId(), session.lastRequestedPlatform);
		Assertions.assertDoesNotThrow(() -> Assertions.assertEquals(0, db.getNumberOfRecords("LOGIN_MARKER")),
				"the login script's own statement must actually have run");
		Assertions.assertDoesNotThrow(() -> Assertions.assertEquals(0, db.getNumberOfRecords("PENDING_MARKER")),
				"the command that triggered the reconnect must still run correctly afterwards");
	}

	@Test
	void doesNotReRunTheLoginScriptWhenNeitherTheConnectionNorThePlatformChanged() throws BroadSQLException {
		interpreter.setQuery("CREATE TABLE PENDING_MARKER(N INT)");
		interpreter.executeCommand();

		Assertions.assertEquals(0, session.callCount,
				"nothing reconnected and the platform did not change - the login script must not run");
	}

	@Test
	void reRunsTheLoginScriptWhenACommandChangesThePlatform() throws BroadSQLException {
		// Stands in for a real CONNECT, typed directly or run from inside an @file/LIB RUN script -
		// see the PlatformSwitchingCommand javadoc for why a fake command is used here instead.
		PlatformSwitchingCommand switchCmd = new PlatformSwitchingCommand("OTHERPLATFORM");
		switchCmd.setConsole(console);
		switchCmd.setSqlDatabase(db);
		switchCmd.setConsoleSettings(consoleSettings);
		interpreter.getCommands().put("SWITCH", switchCmd);

		interpreter.setQuery("SWITCH");
		interpreter.executeCommand();

		Assertions.assertEquals(1, session.callCount,
				"a command that changes the platform must trigger exactly one login-script run");
		Assertions.assertEquals("OTHERPLATFORM", session.lastRequestedPlatform,
				"the login script must be fetched for the new platform, not the old one");
		Assertions.assertDoesNotThrow(() -> Assertions.assertEquals(0, db.getNumberOfRecords("LOGIN_MARKER")),
				"the login script's own statement must actually have run");
	}

	/**
	 * A minimal {@link Command} test double standing in for anything that changes platform. A real
	 * {@code CONNECT}'s own success path needs a working {@code Session.currentDatabase} - package-
	 * private with no public setter, already documented as out of reach at this test tier (see
	 * {@code TestCommandConnect}). Only the "this command changed the platform" contract that
	 * {@code CommandInterpreter} reacts to is under test here, not {@code CommandConnect} itself.
	 */
	private static final class PlatformSwitchingCommand extends Command {
		private final String newPlatform;

		PlatformSwitchingCommand(String newPlatform) {
			super("SWITCH");
			this.newPlatform = newPlatform;
		}

		@Override
		public void execute(String query) {
			this.setPlatform(newPlatform);
		}

		@Override
		public String getDescription() {
			return "test double";
		}

		@Override
		public String getArguments() {
			return "";
		}

		@Override
		public String getExamples() {
			return "";
		}
	}

	/**
	 * Records every call to {@link #getCurrentUserLoginScript} and returns a small, fixed script of
	 * raw SQL statements instead of reading a real CDF - see the class javadoc above.
	 */
	private static final class RecordingSession extends Session {
		int callCount = 0;
		String lastRequestedPlatform;
		private final List<String> script;

		RecordingSession(ConsoleSettings consoleSettings, String... sqlCommands) throws BroadSQLException {
			super(new DatabaseDefinitionsVault(), consoleSettings);
			this.script = Arrays.asList(sqlCommands);
		}

		@Override
		public List<String> getCurrentUserLoginScript(String dbId, DatabaseConnection db) {
			callCount++;
			lastRequestedPlatform = dbId;
			return script;
		}
	}
}
