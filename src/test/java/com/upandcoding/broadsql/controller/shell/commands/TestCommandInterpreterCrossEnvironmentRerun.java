package com.upandcoding.broadsql.controller.shell.commands;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;

/**
 * Covers the "/ &lt;environment&gt;" cross-environment quick rerun (docs/TECHNICAL_CHANGE.md) and the
 * "/ QA"-silently-discards-QA footgun it fixes (docs/TODO.md, "Cross-environment interactive
 * investigation") - both handled by {@code CommandInterpreter.handleSlashRerun(String)}, a
 * package-private test seam extracted from {@code run()}'s console read loop specifically so tests can
 * drive it directly without a real blocking console.
 *
 * <p>Two real, independently-connected in-memory H2 databases stand in for a PROD and a QA connection
 * of the same Database Group, both registered in one real, file-backed {@link DatabaseDefinitionsVault}
 * (needed since resolution goes through {@code DatabaseDefinitionsVault#resolveConnectionForGroupAndEnvironment},
 * which reads real CDF rows) - proving the rerun genuinely executes against the resolved connection,
 * not just that it prints the right name, per docs/TODO.md's explicit test requirement.
 */
class TestCommandInterpreterCrossEnvironmentRerun {

	private static final String GROUP = "TAT";
	private static final String PROD_ENV = "PR";
	private static final String QA_ENV = "QA";
	private static final String PROD_ID = "PROD1";
	private static final String QA_ID = "QA1";

	private DatabaseConnection prodDb;
	private CapturingShellConsole console;
	private ConsoleSettings consoleSettings;
	private CommandInterpreter interpreter;
	private DatabaseDefinitionsVault vault;
	private String qaUrl;

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(prodDb);
	}

	private void setUpTwoEnvironmentConnections() throws Exception {
		consoleSettings = TestDatabaseConnections.defaultConsoleSettings();
		console = new CapturingShellConsole();
		vault = TestDatabaseConnections.newFileBackedVault(GROUP);
		vault.saveEnvironment(new EnvironmentDefinition(PROD_ENV, "Production", true, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition(QA_ENV, "QA", false, null, DatabaseDefinition.STATUS_ACTIVE));

		String prodUrl = "jdbc:h2:mem:prod_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
		DatabaseDefinition prodDef = new DatabaseDefinition(PROD_ID);
		prodDef.setDbType("H2");
		prodDef.setDbDriver("org.h2.Driver");
		prodDef.setDbName(PROD_ID);
		prodDef.setUrl(prodUrl);
		prodDef.setStatus(DatabaseDefinition.STATUS_ACTIVE);
		prodDef.setDatabaseGroup(GROUP);
		prodDef.setEnvironment(PROD_ENV);
		vault.saveDatabaseDefinition(prodDef);

		qaUrl = "jdbc:h2:mem:qa_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
		DatabaseDefinition qaDef = new DatabaseDefinition(QA_ID);
		qaDef.setDbType("H2");
		qaDef.setDbDriver("org.h2.Driver");
		qaDef.setDbName(QA_ID);
		qaDef.setUrl(qaUrl);
		qaDef.setStatus(DatabaseDefinition.STATUS_ACTIVE);
		qaDef.setDatabaseGroup(GROUP);
		qaDef.setEnvironment(QA_ENV);
		vault.saveDatabaseDefinition(qaDef);

		vault.load();

		try (Connection setup = DriverManager.getConnection(qaUrl)) {
			try (Statement st = setup.createStatement()) {
				st.execute("CREATE TABLE MARKER(N INT)");
			}
		}

		prodDb = new DatabaseConnection(consoleSettings, vault);
		prodDb.setPlatformCode(PROD_ID);
		prodDb.connect();
		prodDb.executeUpdateQuery("CREATE TABLE MARKER(N INT)");
		prodDb.setCmdLineConsole(console);

		interpreter = CommandTestSupport.createCommandInterpreter(consoleSettings, console, prodDb);
		interpreter.databaseConnectionsVault = vault;
		interpreter.setPlatform(PROD_ID);
	}

	private int countRows(String url, String table) throws Exception {
		try (Connection conn = DriverManager.getConnection(url); Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
			rs.next();
			return rs.getInt(1);
		}
	}

	@Test
	void bareSlashStillReRunsOnTheCurrentConnectionUnchanged() throws Exception {
		setUpTwoEnvironmentConnections();
		interpreter.lastSQLQuery = "INSERT INTO MARKER VALUES (1)";

		boolean executed = interpreter.handleSlashRerun("/");

		Assertions.assertTrue(executed);
		Assertions.assertEquals(1, prodDb.getNumberOfRecords("MARKER"), "bare / must still rerun on the current (PROD) connection");
	}

	@Test
	void crossEnvironmentRerunExecutesOnTheResolvedTargetConnection() throws Exception {
		setUpTwoEnvironmentConnections();
		interpreter.lastSQLQuery = "INSERT INTO MARKER VALUES (1)";

		boolean executed = interpreter.handleSlashRerun("/ " + QA_ENV);

		Assertions.assertTrue(executed);
		Assertions.assertEquals(1, countRows(qaUrl, "MARKER"), "the rerun must genuinely execute on QA, not just print its name");
		Assertions.assertEquals(0, prodDb.getNumberOfRecords("MARKER"), "PROD must not be touched by / QA");
	}

	@Test
	void crossEnvironmentRerunBindsTheCurrentValuesOfVariablesOnTheTargetConnection() throws Exception {
		// SPRINT 0110A, spec 18.6: references in the stored query are resolved again and bound on the transient connection
		setUpTwoEnvironmentConnections();
		interpreter.getScriptVariables().assign("v", com.upandcoding.broadsql.controller.shell.scripts.ScriptValue.ofLong(1));
		interpreter.lastSQLQuery = "INSERT INTO MARKER VALUES (${v})";

		boolean executed = interpreter.handleSlashRerun("/ " + QA_ENV);

		Assertions.assertTrue(executed);
		Assertions.assertEquals(1, countRows(qaUrl, "MARKER"), "bound and executed on QA");
	}

	@Test
	void crossEnvironmentRerunPopulatesLastQueryResultFromTheTargetConnection() throws Exception {
		// docs/TODO.md, "Previous result's column as SQL input" (§22): a successful / QA becomes the new
		// LAST result, since it is the most recent successful result the user actually saw - even though
		// lastSQLQuery (the text "/" reruns) is unrelated to and unaffected by this.
		setUpTwoEnvironmentConnections();
		try (Connection setup = DriverManager.getConnection(qaUrl); Statement st = setup.createStatement()) {
			st.execute("INSERT INTO MARKER VALUES (42)");
		}
		interpreter.lastSQLQuery = "SELECT N FROM MARKER";
		LastQueryResultHolder.set(null);

		interpreter.handleSlashRerun("/ " + QA_ENV);

		LastQueryResult last = LastQueryResultHolder.get();
		Assertions.assertNotNull(last, "a successful / QA must populate LAST");
		Assertions.assertEquals("42", last.rows().get(0)[0]);
	}

	@Test
	void crossEnvironmentRerunIsCaseInsensitiveOnTheEnvironmentId() throws Exception {
		setUpTwoEnvironmentConnections();
		interpreter.lastSQLQuery = "INSERT INTO MARKER VALUES (1)";

		interpreter.handleSlashRerun("/ qa");

		Assertions.assertEquals(1, countRows(qaUrl, "MARKER"), "environment matching must be case-insensitive, like the rest of the Environment model");
	}

	@Test
	void currentConnectionIsPreservedAfterCrossEnvironmentRerun() throws Exception {
		setUpTwoEnvironmentConnections();
		interpreter.lastSQLQuery = "INSERT INTO MARKER VALUES (1)";

		interpreter.handleSlashRerun("/ " + QA_ENV);

		Assertions.assertEquals(PROD_ID, interpreter.getPlatform(), "the interpreter's current connection id must not change");
		Assertions.assertTrue(prodDb.isConnected(), "the original PROD connection must still be open and usable");
		Assertions.assertSame(prodDb, interpreter.sqlDatabase, "the session's singleton connection object must be untouched");
	}

	@Test
	void missingDatabaseGroupFailsClearly() throws Exception {
		setUpTwoEnvironmentConnections();
		DatabaseDefinition standalone = new DatabaseDefinition("STANDALONE1");
		standalone.setDbType("H2");
		standalone.setDbDriver("org.h2.Driver");
		standalone.setDbName("STANDALONE1");
		standalone.setUrl("jdbc:h2:mem:standalone_" + UUID.randomUUID().toString().replace("-", ""));
		standalone.setStatus(DatabaseDefinition.STATUS_ACTIVE);
		standalone.setEnvironment(QA_ENV);
		vault.saveDatabaseDefinition(standalone);
		vault.load();
		interpreter.setPlatform("STANDALONE1");
		interpreter.lastSQLQuery = "SELECT 1";

		boolean executed = interpreter.handleSlashRerun("/ " + PROD_ENV);

		Assertions.assertTrue(executed, "an attempt was made, even though it failed - mirrors invalid-syntax's own semantics");
		Assertions.assertTrue(console.getOutput().contains("no Database Group"),
				"a connection with no Database Group must fail clearly rather than searching globally");
	}

	@Test
	void unknownEnvironmentFailsClearly() throws Exception {
		setUpTwoEnvironmentConnections();
		interpreter.lastSQLQuery = "SELECT 1";

		interpreter.handleSlashRerun("/ NOPE");

		Assertions.assertTrue(console.getOutput().contains("NOPE"), "an unknown environment must fail clearly");
	}

	@Test
	void environmentWithNoConnectionInCurrentGroupFailsClearly() throws Exception {
		setUpTwoEnvironmentConnections();
		vault.saveEnvironment(new EnvironmentDefinition("DV", "Dev", false, null, DatabaseDefinition.STATUS_ACTIVE));
		interpreter.lastSQLQuery = "SELECT 1";

		interpreter.handleSlashRerun("/ DV");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("DV") && output.contains(GROUP),
				"an environment with no connection in the current Database Group must fail clearly, naming both");
	}

	@Test
	void ambiguousConnectionMappingFailsClearly() throws Exception {
		setUpTwoEnvironmentConnections();
		// A real (Database Group, Environment) collision is normally prevented at save time
		// (assertGroupEnvironmentPairAvailable) - TestDatabaseConnections#insertConnectionBypassingValidation
		// bypasses it directly, making the otherwise-unreachable ambiguous case representable for this test.
		DatabaseDefinition secondQa = new DatabaseDefinition("QA2");
		secondQa.setDbType("H2");
		secondQa.setDbDriver("org.h2.Driver");
		secondQa.setUrl("jdbc:h2:mem:qa2_" + UUID.randomUUID().toString().replace("-", ""));
		secondQa.setDatabaseGroup(GROUP);
		secondQa.setEnvironment(QA_ENV);
		TestDatabaseConnections.insertConnectionBypassingValidation(vault, secondQa);
		interpreter.lastSQLQuery = "SELECT 1";

		interpreter.handleSlashRerun("/ " + QA_ENV);

		Assertions.assertTrue(console.getOutput().toLowerCase().contains("more than one"),
				"an ambiguous (Database Group, Environment) mapping must fail clearly rather than guessing");
	}

	@Test
	void noPreviousQueryFailsClearly() throws Exception {
		setUpTwoEnvironmentConnections();
		interpreter.lastSQLQuery = null;

		boolean executed = interpreter.handleSlashRerun("/ " + QA_ENV);

		Assertions.assertFalse(executed);
		Assertions.assertTrue(console.getOutput().contains("No command in memory"),
				"/ <environment> with no previous query must fail exactly like bare / does");
	}

	@Test
	void trailingArgumentsAreRejectedRatherThanSilentlyDiscarded() throws Exception {
		setUpTwoEnvironmentConnections();
		interpreter.lastSQLQuery = "INSERT INTO MARKER VALUES (1)";

		boolean executed = interpreter.handleSlashRerun("/ " + QA_ENV + " somethingElse");

		Assertions.assertFalse(executed);
		Assertions.assertEquals(0, prodDb.getNumberOfRecords("MARKER"), "trailing text must not silently rerun on the current connection");
		Assertions.assertEquals(0, countRows(qaUrl, "MARKER"), "trailing text must not silently rerun on QA either");
		Assertions.assertTrue(console.getOutput().toLowerCase().contains("invalid syntax"),
				"a line like '/ QA somethingElse' must be rejected with a clear error, not silently discarded");
	}

	@Test
	void ctrlCCancelsALongCrossEnvironmentRerunOnTheTransientConnection() throws Exception {
		// The "/ <environment>" statement runs on a transient connection, from the input loop's thread: CTRL+C (the
		// handler the console's INT registrations call) must cancel it there through JDBC, not only the session's
		setUpTwoEnvironmentConnections();
		interpreter.lastSQLQuery = "SELECT COUNT(*) FROM SYSTEM_RANGE(1, 3000000000) A WHERE MOD(A.X, 7) = 3";
		java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
		Thread runner = new Thread(() -> {
			try {
				interpreter.handleSlashRerun("/ " + QA_ENV);
			} catch (Throwable t) {
				failure.set(t);
			}
		});
		runner.start();
		long deadline = System.currentTimeMillis() + 20_000;
		while (executingOn(qaUrl) == 0) {
			Assertions.assertTrue(System.currentTimeMillis() < deadline, "the rerun never started on QA: " + console.getOutput());
			Thread.sleep(20);
		}
		Thread.sleep(300);
		long pressed = System.currentTimeMillis();
		interpreter.handleInterrupt();
		runner.join(30_000);
		try {
			Assertions.assertFalse(runner.isAlive(), "CTRL+C must end the rerun: " + console.getOutput());
			Assertions.assertNull(failure.get());
			Assertions.assertTrue(System.currentTimeMillis() - pressed < 10_000, "cancelled, not waited out");
			Assertions.assertEquals(0, executingOn(qaUrl), "QA no longer executes the query");
			Assertions.assertTrue(console.getOutput().contains("57014"), console.getOutput());
			Assertions.assertTrue(prodDb.isConnected(), "the session's own connection is untouched");
			interpreter.lastSQLQuery = "INSERT INTO MARKER VALUES (1)";
			Assertions.assertTrue(interpreter.handleSlashRerun("/ " + QA_ENV));
			Assertions.assertEquals(1, countRows(qaUrl, "MARKER"), "the next rerun is not affected by the earlier CTRL+C");
		} finally {
			CommandCancellation.resetRun();
		}
	}

	/** Statements other sessions of {@code url} are executing on SYSTEM_RANGE (read on a separate connection). */
	private long executingOn(String url) throws Exception {
		try (Connection conn = DriverManager.getConnection(url); Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID <> SESSION_ID() "
						+ "AND EXECUTING_STATEMENT LIKE '%3000000000%'")) {
			rs.next();
			return rs.getLong(1);
		}
	}
}
