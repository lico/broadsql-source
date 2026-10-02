package com.upandcoding.broadsql.controller.shell.commands;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;

import com.upandcoding.broadsql.controller.config.SpringMainConfig;
import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * EXIT runs the connection's shutdown lifecycle (pending-work check, rollback or commit, JDBC close, "Disconnected")
 * exactly once. Two cleanups reach the same {@code sqlDatabase} bean on EXIT: {@code CommandInterpreter.run()}'s own
 * close, then {@code BroadSQL.main}'s {@code context.close()}, whose destroy callback is {@code DatabaseConnection.close()}.
 * The second one used to probe and roll back an already-closed JDBC connection ("Could not determine whether
 * uncommitted changes are pending", "The object is already closed [90007]") and print "Disconnected" twice.
 *
 * <p>The Spring half uses a real context, with the {@code sqlDatabase} bean's destroy method taken from
 * {@link SpringMainConfig#getSqlDatabase()}'s own {@code @Bean} annotation, as Spring's configuration-class reader does.
 */
class TestExitConnectionShutdown {

	private static final String DISCONNECTED = "Disconnected from ";

	private static ConsoleSettings settings(boolean autoCommit) {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setAutoCommit(autoCommit);
		return settings;
	}

	/** An in-memory H2 database standing for the Connections Definition File, with its real id and name. */
	private static DatabaseDefinition cdf() {
		DatabaseDefinition def = new DatabaseDefinition(SpringPropertiesConfig.CDF_ID);
		def.setDbDriver("org.h2.Driver");
		def.setDbType(SpringPropertiesConfig.DBTYPE_H2);
		def.setDbName("CliSQL Connections Definition File");
		def.setUrl("jdbc:h2:mem:cdf_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1");
		return def;
	}

	private static DatabaseDefinition hsqldb() {
		DatabaseDefinition def = new DatabaseDefinition("HSQL_" + UUID.randomUUID().toString().substring(0, 8));
		def.setDbDriver("org.hsqldb.jdbc.JDBCDriver");
		def.setDbType(SpringPropertiesConfig.DBTYPE_HSQL);
		def.setDbName("HSQL");
		def.setUrl("jdbc:hsqldb:mem:exit_" + UUID.randomUUID().toString().replace("-", ""));
		def.setUserName("SA");
		def.setUserPassword("");
		return def;
	}

	private static void createTable(DatabaseDefinition def) throws SQLException {
		try (Connection conn = DriverManager.getConnection(def.getUrl(), def.getUserName(), def.getUserPassword());
				Statement st = conn.createStatement()) {
			st.execute("CREATE TABLE T (ID INT)");
		}
	}

	private static int count(DatabaseDefinition def) throws SQLException {
		try (Connection conn = DriverManager.getConnection(def.getUrl(), def.getUserName(), def.getUserPassword());
				Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM T")) {
			rs.next();
			return rs.getInt(1);
		}
	}

	private static int occurrences(String text, String fragment) {
		int n = 0;
		for (int i = text.indexOf(fragment); i >= 0; i = text.indexOf(fragment, i + fragment.length())) {
			n++;
		}
		return n;
	}

	/** The application context as {@code BroadSQL.main} closes it, holding {@code db} as its {@code sqlDatabase} bean. */
	private static AnnotationConfigApplicationContext contextHolding(DatabaseConnection db, ConsoleSettings settings)
			throws NoSuchMethodException {
		String destroyMethod = SpringMainConfig.class.getMethod("getSqlDatabase").getAnnotation(Bean.class).destroyMethod();
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		// DatabaseConnection's @Autowired collaborators, as in production
		context.registerBean("consoleSettings", ConsoleSettings.class, () -> settings);
		context.registerBean("databaseConnectionsVault", DatabaseDefinitionsVault.class, () -> new DatabaseDefinitionsVault());
		context.registerBean("sqlDatabase", DatabaseConnection.class, () -> db, bd -> bd.setDestroyMethodName(destroyMethod));
		context.refresh();
		context.getBean("sqlDatabase");
		return context;
	}

	/** EXIT: the interpreter's close at the end of {@code run()}, then {@code BroadSQL.main}'s {@code context.close()}. */
	private static void exit(DatabaseConnection db, ConsoleSettings settings, CapturingShellConsole console) throws Exception {
		AnnotationConfigApplicationContext context = contextHolding(db, settings);
		CommandInterpreter interpreter = CommandTestSupport.createCommandInterpreter(settings, console, db);
		interpreter.closeConnectionOnExit();
		context.close();
	}

	private static DatabaseConnection open(DatabaseDefinition def, ConsoleSettings settings, CapturingShellConsole console)
			throws BroadSQLException {
		DatabaseConnection db = TestDatabaseConnections.connect(def, settings);
		db.setCmdLineConsole(console);
		return db;
	}

	private static void assertSingleQuietDisconnect(CapturingShellConsole console, DatabaseDefinition def) {
		String out = console.getOutput();
		Assertions.assertEquals(1, occurrences(out, DISCONNECTED + "'" + def.getDbName() + "'"), out);
		Assertions.assertFalse(out.contains("Could not determine whether uncommitted changes are pending"), out);
		Assertions.assertFalse(out.contains("already closed"), out);
		Assertions.assertFalse(console.wasErrorReported(), out);
	}

	@Test
	void exitWhileConnectedToTheCdfDisconnectsOnceWithoutProbingOrRollingBackTheClosedConnection() throws Exception {
		DatabaseDefinition cdf = cdf();
		ConsoleSettings settings = settings(false);
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(cdf, settings, console);

		exit(db, settings, console);

		Assertions.assertFalse(db.isConnected());
		assertSingleQuietDisconnect(console, cdf);
	}

	@Test
	void exitWhileConnectedToTheCdfWithAutocommitOnDisconnectsOnce() throws Exception {
		DatabaseDefinition cdf = cdf();
		ConsoleSettings settings = settings(true);
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(cdf, settings, console);

		exit(db, settings, console);

		Assertions.assertFalse(db.isConnected());
		assertSingleQuietDisconnect(console, cdf);
	}

	@Test
	void exitWithANormalDatabaseConnectionDisconnectsOnce() throws Exception {
		DatabaseDefinition h2 = TestDatabaseConnections.newInMemoryTarget("EXITH2");
		ConsoleSettings settings = settings(false);
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(h2, settings, console);

		exit(db, settings, console);

		assertSingleQuietDisconnect(console, h2);
	}

	@Test
	void exitWithAnHsqldbConnectionDisconnectsOnceWithoutASecondRollback() throws Exception {
		DatabaseDefinition hsql = hsqldb();
		ConsoleSettings settings = settings(false);
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(hsql, settings, console);

		exit(db, settings, console);

		assertSingleQuietDisconnect(console, hsql);
	}

	@Test
	void exitWithPendingWorkStillRollsItBackAndWarnsExactlyOnce() throws Exception {
		DatabaseDefinition h2 = TestDatabaseConnections.newInMemoryTarget("EXITPENDING");
		createTable(h2);
		ConsoleSettings settings = settings(false);
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(h2, settings, console);
		db.executeUpdateQuery("INSERT INTO T VALUES (1)");

		exit(db, settings, console);

		String out = console.getOutput();
		Assertions.assertEquals(1, occurrences(out, "Uncommitted transactions aborted. Rolling back."), out);
		Assertions.assertEquals(0, count(h2), "the pending insert must be rolled back, never committed");
		assertSingleQuietDisconnect(console, h2);
	}

	@Test
	void exitWithPendingWorkOnHsqldbStillRollsItBack() throws Exception {
		DatabaseDefinition hsql = hsqldb();
		createTable(hsql);
		ConsoleSettings settings = settings(false);
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(hsql, settings, console);
		db.executeUpdateQuery("INSERT INTO T VALUES (1)");

		exit(db, settings, console);

		Assertions.assertEquals(1, occurrences(console.getOutput(), "Rolling back"), console.getOutput());
		Assertions.assertEquals(0, count(hsql));
		assertSingleQuietDisconnect(console, hsql);
	}

	@Test
	void whenTheInterpreterDidNotCloseTheConnectionTheSpringBackstopStillRunsTheSafeLifecycle() throws Exception {
		// An abnormal end of CommandInterpreter.run() (an exception) skips its own close: BroadSQL.main still
		// closes the context, and that destroy callback must then run the normal lifecycle, rollback included
		DatabaseDefinition h2 = TestDatabaseConnections.newInMemoryTarget("EXITBACKSTOP");
		createTable(h2);
		ConsoleSettings settings = settings(false);
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(h2, settings, console);
		db.executeUpdateQuery("INSERT INTO T VALUES (1)");

		contextHolding(db, settings).close();

		Assertions.assertFalse(db.isConnected());
		Assertions.assertTrue(console.getOutput().contains("Uncommitted transactions aborted. Rolling back."), console.getOutput());
		Assertions.assertEquals(0, count(h2));
		assertSingleQuietDisconnect(console, h2);
	}

	@Test
	void closingAnAlreadyClosedConnectionIsHarmlessAndSilent() throws Exception {
		DatabaseDefinition cdf = cdf();
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(cdf, settings(false), console);

		db.close();
		console.clear();
		Assertions.assertDoesNotThrow(() -> db.close());
		Assertions.assertDoesNotThrow(() -> db.close(false));

		Assertions.assertEquals("", console.getOutput(), "a second close must neither probe, roll back nor report");
		Assertions.assertFalse(console.wasErrorReported());
	}

	@Test
	void closingAnAlreadyClosedAutocommitConnectionDoesNotCommitAgain() throws Exception {
		// With Autocommit on, a second close used to call commit() on the closed connection and throw; on EXIT Spring
		// swallowed that exception into a log line hidden by the ERROR-level logback configuration
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(cdf(), settings(true), console);

		db.close();
		console.clear();
		Assertions.assertDoesNotThrow(() -> db.close());

		Assertions.assertEquals("", console.getOutput());
	}

	@Test
	void closingAConnectionWhoseJdbcHandleIsAlreadyClosedDoesNotProbeOrRollBack() throws Exception {
		// The JDBC connection is closed underneath BroadSQL (a dropped session): there is nothing left to check or
		// roll back, so close() must not query or roll back the dead handle
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(cdf(), settings(false), console);
		TestDatabaseConnections.killUnderlyingConnection(db);

		Assertions.assertDoesNotThrow(() -> db.close());

		Assertions.assertFalse(console.getOutput().contains("Rolling back"), console.getOutput());
		Assertions.assertFalse(console.wasErrorReported(), console.getOutput());
	}

	@Test
	void reconnectingAfterAnExitStyleCloseStillWorks() throws Exception {
		// close() being idempotent must not stop the same DatabaseConnection from opening again (CONNECT, ENV)
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection db = open(cdf(), settings(false), console);
		db.close();

		db.setPlatform(TestDatabaseConnections.newInMemoryTarget("AFTERCLOSE"));
		db.connect();

		Assertions.assertTrue(db.isConnected());
		db.close();
		Assertions.assertFalse(db.isConnected());
	}
}
