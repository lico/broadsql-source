package com.upandcoding.broadsql.controller.shell.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.SessionTestSupport;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandDuplicateConnection;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageConnectionAdd;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageConnectionEdit;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandConnect;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.controller.shell.reader.ScriptedLineReader;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.DatabaseGroupDefinition;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;

/**
 * The whole Connection lifecycle, on a copy of the CDF BroadSQL ships (its real schema: {@code ID}, {@code DRIVER}
 * and {@code URL} are {@code NOT NULL}), through the real persistence layer ({@link DatabaseDefinitionsVault}),
 * the real CLI commands ({@code ADD}/{@code EDIT}/{@code DUPLICATE CONNECTION}, driven by scripted answers) and the
 * real {@code CONNECT}: CREATE, READ, UPDATE, DUPLICATE, DEACTIVATE, REACTIVATE, DELETE PERMANENTLY, TEST, CONNECT,
 * for H2 (real JDBC) and PostgreSQL (definition only, to catch H2-specific persistence). Every persisted column is
 * read back with plain SQL, not only through the vault. Passwords are compared without ever appearing in an
 * assertion message or in the console output.
 */
class TestConnectionLifecycle {

	private static final String SHIPPED_CDF_PASSWORD = "clipper8AD";
	private static final Path SHIPPED_CDF = Path.of("src", "main", "resources", "release-template", "conf", "ConnectionsDefinitionFile.cdf.mv.db");
	private static final String SECRET = "s3cret-Pwd";

	@TempDir
	Path dir;
	private String cdf;
	private DatabaseConnection db;

	/** One database type: the persisted definition must not depend on it. */
	record Kind(String type, String driver, String url) {
		@Override
		public String toString() {
			return type;
		}
	}

	static List<Kind> kinds() {
		return List.of(new Kind("H2", "org.h2.Driver", "jdbc:h2:mem:lifecycle_test;DB_CLOSE_DELAY=-1"),
				new Kind("PostgreSQL", "org.postgresql.Driver", "jdbc:postgresql://localhost:5432/lifecycle"));
	}

	@BeforeEach
	void copyTheShippedCdf() throws Exception {
		Files.copy(SHIPPED_CDF, dir.resolve("ConnectionsDefinitionFile.cdf.mv.db"), StandardCopyOption.REPLACE_EXISTING);
		cdf = dir.resolve("ConnectionsDefinitionFile.cdf").toString();
	}

	@AfterEach
	void tearDown() throws Exception {
		TestDatabaseConnections.close(db);
	}

	private DatabaseDefinitionsVault vault() throws BroadSQLException {
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault(cdf, SHIPPED_CDF_PASSWORD);
		vault.load();
		return vault;
	}

	/** A vault with a Database Group SALES and Environments QA and PROD, all active. */
	private DatabaseDefinitionsVault vaultWithGroupAndEnvironments() throws BroadSQLException {
		DatabaseDefinitionsVault vault = vault();
		vault.saveGroup(new DatabaseGroupDefinition("SALES", "Sales systems", null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("QA", "Quality", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("PROD", "Production", true, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.load();
		return vault;
	}

	private Connection cdfConnection() throws Exception {
		return DriverManager.getConnection("jdbc:h2:" + cdf + ";CIPHER=AES", DatabaseDefinitionsVault.H2_ADMIN, SHIPPED_CDF_PASSWORD + " " + SHIPPED_CDF_PASSWORD);
	}

	/** Every column of the CONNECTIONS row {@code id}, as stored; null when there is no such row. */
	private Map<String, String> row(String id) throws Exception {
		try (Connection conn = cdfConnection(); PreparedStatement stat = conn.prepareStatement("SELECT * FROM CONNECTIONS WHERE ID=?")) {
			stat.setString(1, id);
			try (ResultSet rs = stat.executeQuery()) {
				if (!rs.next()) {
					return null;
				}
				ResultSetMetaData md = rs.getMetaData();
				Map<String, String> values = new LinkedHashMap<>();
				for (int i = 1; i <= md.getColumnCount(); i++) {
					values.put(md.getColumnName(i), rs.getString(i));
				}
				return values;
			}
		}
	}

	/** The whole CONNECTIONS table, to prove a failed operation changed nothing. */
	private List<Map<String, String>> table() throws Exception {
		List<Map<String, String>> rows = new ArrayList<>();
		try (Connection conn = cdfConnection(); Statement stat = conn.createStatement(); ResultSet rs = stat.executeQuery("SELECT * FROM CONNECTIONS ORDER BY ID")) {
			ResultSetMetaData md = rs.getMetaData();
			while (rs.next()) {
				Map<String, String> values = new LinkedHashMap<>();
				for (int i = 1; i <= md.getColumnCount(); i++) {
					values.put(md.getColumnName(i), rs.getString(i));
				}
				rows.add(values);
			}
		}
		return rows;
	}

	private static DatabaseDefinition definition(String id, Kind kind, String group, String environment) {
		DatabaseDefinition c = new DatabaseDefinition(id, kind.type(), kind.driver(), kind.url(), "app_user", SECRET, id + " name");
		c.setDatabaseGroup(group);
		c.setEnvironment(environment);
		c.setComment("comment of " + id);
		c.setStatus(DatabaseDefinition.STATUS_ACTIVE);
		return c;
	}

	private static void assertPassword(String expected, String actual) {
		Assertions.assertTrue(expected.equals(actual), "the password differs (value not shown)");
	}

	/** The stored row matches {@code c} column by column (the password without showing it). */
	private void assertRow(DatabaseDefinition c, String expectedDriver, String expectedStatus) throws Exception {
		Map<String, String> r = row(c.getId());
		Assertions.assertNotNull(r, c.getId() + " has no row");
		Assertions.assertEquals(c.getId(), r.get("ID"));
		Assertions.assertEquals(c.getDbName(), r.get("NAME"));
		Assertions.assertEquals(c.getUrl(), r.get("URL"));
		Assertions.assertEquals(c.getDbType(), r.get("TYPE_ID"));
		Assertions.assertNotNull(r.get("DRIVER"), "DRIVER is null");
		Assertions.assertEquals(expectedDriver, r.get("DRIVER"));
		Assertions.assertEquals(c.getUserName(), r.get("USER_NAME"));
		assertPassword(c.getUserPassword(), r.get("USER_PASSWORD"));
		Assertions.assertEquals(c.getDatabaseGroup(), r.get("INSTANCE_ID"));
		Assertions.assertEquals(c.getEnvironment(), r.get("ENVIRONMENT_ID"));
		Assertions.assertEquals(expectedStatus, r.get("STATUS_ID"));
		Assertions.assertEquals(c.getComment(), r.get("COMMENT"));
		Assertions.assertNotNull(r.get("CREATED"), "CREATED");
		Assertions.assertNotNull(r.get("LAST_MODIFIED"), "LAST_MODIFIED");
	}

	/** The definition a vault returns matches {@code c}. */
	private static void assertLoaded(DatabaseDefinition c, DatabaseDefinition loaded, String expectedDriver, String expectedStatus) {
		Assertions.assertNotNull(loaded, c.getId() + " not loaded");
		Assertions.assertEquals(c.getId(), loaded.getId());
		Assertions.assertEquals(c.getDbName(), loaded.getDbName());
		Assertions.assertEquals(c.getUrl(), loaded.getUrl());
		Assertions.assertEquals(c.getDbType(), loaded.getDbType());
		Assertions.assertEquals(expectedDriver, loaded.getDbDriver());
		Assertions.assertEquals(c.getUserName(), loaded.getUserName());
		assertPassword(c.getUserPassword(), loaded.getUserPassword());
		Assertions.assertEquals(c.getDatabaseGroup(), loaded.getDatabaseGroup());
		Assertions.assertEquals(c.getEnvironment(), loaded.getEnvironment());
		Assertions.assertEquals(c.getComment(), loaded.getComment());
		Assertions.assertEquals(expectedStatus, loaded.getStatus());
	}

	// ================================================================== the DRIVER defect

	/**
	 * The release-blocking defect: a new connection with Driver org.h2.Driver could not be saved ("NULL not
	 * allowed for column DRIVER": the INSERT left DRIVER out). Kept even though the matrix below covers DRIVER too.
	 */
	@Test
	void regression_aNewConnectionIsSavedWithItsDriverNotNull() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition c = new DatabaseDefinition("TEST_H2", "H2", "org.h2.Driver", "jdbc:h2:mem:test", "sa", "", "Test H2 Connection");
		c.setEnvironment(vault.resolveLocalEnvironmentId());
		c.setStatus(DatabaseDefinition.STATUS_ACTIVE);

		Assertions.assertDoesNotThrow(() -> vault.saveDatabaseDefinition(c));

		Assertions.assertNotNull(row("TEST_H2").get("DRIVER"));
		Assertions.assertEquals("org.h2.Driver", row("TEST_H2").get("DRIVER"));
		Assertions.assertEquals("org.h2.Driver", vault().getDatabaseConnection("TEST_H2").getDbDriver());
	}

	// ================================================================== CREATE and READ

	@ParameterizedTest(name = "{0}")
	@MethodSource("kinds")
	void create_everyPersistedFieldIsStoredAndReloaded(Kind kind) throws Exception {
		DatabaseDefinitionsVault vault = vaultWithGroupAndEnvironments();
		DatabaseDefinition c = definition("NEW_" + kind.type().toUpperCase(), kind, "SALES", "QA");

		vault.saveDatabaseDefinition(c);

		assertRow(c, kind.driver(), DatabaseDefinition.STATUS_ACTIVE);
		assertLoaded(c, vault().getDatabaseConnection(c.getId()), kind.driver(), DatabaseDefinition.STATUS_ACTIVE);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("kinds")
	void read_byIdInTheActiveListNotInTheInactiveOne(Kind kind) throws Exception {
		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition c = definition("READ_ME", kind, null, vault.resolveLocalEnvironmentId());
		vault.saveDatabaseDefinition(c);

		DatabaseDefinitionsVault reloaded = vault();
		Assertions.assertTrue(reloaded.contains("READ_ME"));
		Assertions.assertTrue(reloaded.getIds().contains("READ_ME"));
		Assertions.assertFalse(reloaded.getInactiveIds().contains("READ_ME"));
		Assertions.assertFalse(reloaded.isInactiveConnection("READ_ME"));
		assertLoaded(c, reloaded.getDatabaseConnection("READ_ME"), kind.driver(), DatabaseDefinition.STATUS_ACTIVE);
		Assertions.assertNull(reloaded.getDatabaseConnection("NO_SUCH_ID"));
	}

	// ================================================================== UPDATE

	@ParameterizedTest(name = "{0}")
	@MethodSource("kinds")
	void update_changedFieldsAreStoredOthersKeptAndNothingBecomesNull(Kind kind) throws Exception {
		DatabaseDefinitionsVault vault = vaultWithGroupAndEnvironments();
		vault.saveDatabaseDefinition(definition("UPD", kind, null, "QA"));

		DatabaseDefinitionsVault editing = vault();
		DatabaseDefinition edited = editing.getDatabaseConnection("UPD");
		edited.setDbName("renamed");
		edited.setUrl(kind.url() + "_v2");
		edited.setUserName("other_user");
		edited.setDatabaseGroup("SALES");
		edited.setEnvironment("PROD");
		edited.setComment("changed comment");
		editing.saveDatabaseDefinition(edited);

		assertRow(edited, kind.driver(), DatabaseDefinition.STATUS_ACTIVE);
		assertLoaded(edited, vault().getDatabaseConnection("UPD"), kind.driver(), DatabaseDefinition.STATUS_ACTIVE);
		assertPassword(SECRET, row("UPD").get("USER_PASSWORD"));
		Map<String, String> r = row("UPD");
		for (String column : new String[] { "ID", "NAME", "URL", "TYPE_ID", "DRIVER", "USER_NAME", "USER_PASSWORD", "INSTANCE_ID", "ENVIRONMENT_ID", "STATUS_ID", "COMMENT" }) {
			Assertions.assertNotNull(r.get(column), column + " became null");
		}
	}

	@Test
	void update_changingTheTypeChangesTheDriverWithIt() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		vault.saveDatabaseDefinition(definition("RETYPE", kinds().get(0), null, vault.resolveLocalEnvironmentId()));
		DatabaseDefinitionsVault editing = vault();
		DatabaseDefinition edited = editing.getDatabaseConnection("RETYPE");
		edited.setDbType("PostgreSQL"); // the definition still carries org.h2.Driver, as after a type change in a wizard
		edited.setUrl("jdbc:postgresql://localhost/retype");
		editing.saveDatabaseDefinition(edited);

		Assertions.assertEquals("org.postgresql.Driver", row("RETYPE").get("DRIVER"));
		Assertions.assertEquals("org.postgresql.Driver", vault().getDatabaseConnection("RETYPE").getDbDriver());
	}

	@Test
	void update_theIdIsTheKeySavingUnderAnotherIdCreatesAConnectionAndLeavesTheOriginal() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		vault.saveDatabaseDefinition(definition("ORIGINAL", kinds().get(0), null, vault.resolveLocalEnvironmentId()));
		Map<String, String> before = row("ORIGINAL");
		DatabaseDefinitionsVault editing = vault();
		DatabaseDefinition renamed = editing.getDatabaseConnection("ORIGINAL");
		renamed.setId("RENAMED");
		editing.saveDatabaseDefinition(renamed);

		Assertions.assertEquals(before, row("ORIGINAL"), "the original row is untouched");
		Assertions.assertNotNull(row("RENAMED"));
	}

	// ================================================================== DUPLICATE

	@ParameterizedTest(name = "{0}")
	@MethodSource("kinds")
	void duplicate_theCopyHasEveryFieldOfTheSourceAndTheSourceIsUnchanged(Kind kind) throws Exception {
		DatabaseDefinitionsVault vault = vault();
		vault.saveDatabaseDefinition(definition("SRC", kind, null, vault.resolveLocalEnvironmentId()));
		Map<String, String> sourceBefore = row("SRC");
		DatabaseDefinitionsVault duplicating = vault();
		DatabaseDefinition source = duplicating.getDatabaseConnection("SRC");
		// DUPLICATE CONNECTION's copy (CommandUtils.duplicatePlatform): every field, the password included
		DatabaseDefinition copy = new DatabaseDefinition("SRC_COPY", source.getDbType(), source.getDbDriver(), source.getUrl(), source.getUserName(),
				source.getUserPassword(), source.getDbName());
		copy.setDatabaseGroup(source.getDatabaseGroup());
		copy.setEnvironment(source.getEnvironment());
		copy.setComment(source.getComment());
		copy.setStatus(DatabaseDefinition.STATUS_ACTIVE);
		duplicating.saveDatabaseDefinition(copy);

		assertRow(copy, kind.driver(), DatabaseDefinition.STATUS_ACTIVE);
		Assertions.assertEquals(sourceBefore, row("SRC"), "the source row is untouched");
		assertPassword(SECRET, row("SRC_COPY").get("USER_PASSWORD"));
	}

	// ================================================================== DEACTIVATE, REACTIVATE, DELETE

	@ParameterizedTest(name = "{0}")
	@MethodSource("kinds")
	void deactivate_reactivate_delete_keepEveryFieldUntilTheRowIsRemoved(Kind kind) throws Exception {
		DatabaseDefinitionsVault vault = vaultWithGroupAndEnvironments();
		DatabaseDefinition c = definition("LIFE", kind, "SALES", "QA");
		vault.saveDatabaseDefinition(c);
		vault.saveDatabaseDefinition(definition("NEIGHBOUR", kind, null, "QA"));
		Map<String, String> neighbour = row("NEIGHBOUR");

		// DEACTIVATE: the row stays, only the status changes; out of the active list, in the inactive one
		DatabaseDefinitionsVault working = vault();
		working.softDeleteDatabaseDefinition("LIFE");
		assertRow(c, kind.driver(), DatabaseDefinition.STATUS_INACTIVE);
		DatabaseDefinitionsVault reloaded = vault();
		Assertions.assertFalse(reloaded.contains("LIFE"));
		Assertions.assertTrue(reloaded.isInactiveConnection("LIFE"));
		DatabaseDefinition inactive = reloaded.getInactiveConnectionDetails().stream().filter(d -> d.getId().equals("LIFE")).findFirst().orElse(null);
		assertLoaded(c, inactive, kind.driver(), DatabaseDefinition.STATUS_INACTIVE);
		Assertions.assertTrue(reloaded.getConnectionIdsForGroup("SALES").contains("LIFE"), "still referenced by its Database Group");

		// REACTIVATE: back in the active list, every field intact
		reloaded.reactivateDatabaseDefinition("LIFE");
		assertRow(c, kind.driver(), DatabaseDefinition.STATUS_ACTIVE);
		assertLoaded(c, vault().getDatabaseConnection("LIFE"), kind.driver(), DatabaseDefinition.STATUS_ACTIVE);

		// DELETE PERMANENTLY: refused while active, allowed once inactive; the neighbour is untouched
		DatabaseDefinitionsVault deleting = vault();
		BroadSQLException active = Assertions.assertThrows(BroadSQLException.class, () -> deleting.deleteDatabaseDefinition("LIFE"));
		Assertions.assertTrue(active.getMessage().contains("Deactivate it first"), active.getMessage());
		Assertions.assertNotNull(row("LIFE"));
		deleting.softDeleteDatabaseDefinition("LIFE");
		deleting.deleteDatabaseDefinition("LIFE");
		Assertions.assertNull(row("LIFE"), "the row is removed");
		DatabaseDefinitionsVault after = vault();
		Assertions.assertFalse(after.contains("LIFE"));
		Assertions.assertFalse(after.isInactiveConnection("LIFE"));
		Assertions.assertEquals(neighbour, row("NEIGHBOUR"), "no other connection is touched");
	}

	@Test
	void delete_anUnknownIdIsRefused() throws Exception {
		List<Map<String, String>> before = table();
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> vault().deleteDatabaseDefinition("NO_SUCH_ID"));
		Assertions.assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
		Assertions.assertEquals(before, table());
	}

	/**
	 * Current rules: reactivation is a status change only (no Group/Environment check), and a Database Group or an
	 * Environment still referenced by a connection, even an inactive one, cannot be deactivated. So a deactivated
	 * connection can never come back pointing at an inactive Group or Environment.
	 */
	@Test
	void reactivate_cannotMeetAnInactiveGroupOrEnvironmentBecauseAReferencedOneCannotBeDeactivated() throws Exception {
		DatabaseDefinitionsVault vault = vaultWithGroupAndEnvironments();
		vault.saveDatabaseDefinition(definition("GROUPED", kinds().get(0), "SALES", "QA"));
		vault.softDeleteDatabaseDefinition("GROUPED");

		BroadSQLException group = Assertions.assertThrows(BroadSQLException.class, () -> vault().softDeleteGroup("SALES"));
		Assertions.assertTrue(group.getMessage().contains("still referenced by connection(s) GROUPED"), group.getMessage());
		BroadSQLException environment = Assertions.assertThrows(BroadSQLException.class, () -> vault().softDeleteEnvironment("QA"));
		Assertions.assertTrue(environment.getMessage().contains("GROUPED"), environment.getMessage());

		vault().reactivateDatabaseDefinition("GROUPED");
		Assertions.assertTrue(vault().contains("GROUPED"));
		Assertions.assertEquals("SALES", row("GROUPED").get("INSTANCE_ID"));
		Assertions.assertEquals("QA", row("GROUPED").get("ENVIRONMENT_ID"));
	}

	// ================================================================== validation and integrity

	private void assertRefusedAndNothingChanged(DatabaseDefinition c, String expectedMessagePart) throws Exception {
		DatabaseDefinitionsVault vault = vault();
		List<Map<String, String>> before = table();
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> vault.saveDatabaseDefinition(c));
		Assertions.assertTrue(e.getMessage().contains(expectedMessagePart), e.getMessage());
		Assertions.assertFalse(e.getMessage().contains(SECRET), "the message shows the password");
		Assertions.assertEquals(before, table(), "the CDF changed after a refused save");
	}

	@Test
	void validation_requiredFieldsAndReferencesAreCheckedBeforeAnyChange() throws Exception {
		Kind h2 = kinds().get(0);
		String local = vault().resolveLocalEnvironmentId();
		vaultWithGroupAndEnvironments();
		DatabaseDefinitionsVault setup = vault();
		setup.saveGroup(new DatabaseGroupDefinition("OLDGROUP", "retired", null, DatabaseDefinition.STATUS_INACTIVE));
		setup.saveEnvironment(new EnvironmentDefinition("OLDENV", "retired", false, null, DatabaseDefinition.STATUS_INACTIVE));
		setup.saveDatabaseDefinition(definition("RETIRED", h2, null, local));
		setup.softDeleteDatabaseDefinition("RETIRED");

		DatabaseDefinition noId = definition("X", h2, null, local);
		noId.setId(" ");
		assertRefusedAndNothingChanged(noId, "Connection ID is required");
		DatabaseDefinition noUrl = definition("NOURL", h2, null, local);
		noUrl.setUrl("");
		assertRefusedAndNothingChanged(noUrl, "URL is required");
		DatabaseDefinition noTypeNoDriver = definition("NOTYPE", h2, null, local);
		noTypeNoDriver.setDbType(null);
		noTypeNoDriver.setDbDriver(null);
		assertRefusedAndNothingChanged(noTypeNoDriver, "No JDBC driver class is known");
		DatabaseDefinition unknownType = definition("BADTYPE", new Kind("NoSuchType", null, "jdbc:none:x"), null, local);
		assertRefusedAndNothingChanged(unknownType, "No JDBC driver class is known for database type 'NoSuchType'");
		assertRefusedAndNothingChanged(definition("NOENV", h2, null, ""), "Environment is required");
		assertRefusedAndNothingChanged(definition("BADENV", h2, null, "NO_SUCH_ENV"), "Environment 'NO_SUCH_ENV' does not exist");
		assertRefusedAndNothingChanged(definition("BADGROUP", h2, "NO_SUCH_GROUP", local), "Database Group 'NO_SUCH_GROUP' does not exist");
		assertRefusedAndNothingChanged(definition("OLDG", h2, "OLDGROUP", local), "Database Group 'OLDGROUP' is inactive");
		assertRefusedAndNothingChanged(definition("OLDE", h2, null, "OLDENV"), "Environment 'OLDENV' is inactive");
		// the ID of an inactive connection: the CDF's primary key refuses it, and the inactive row is untouched
		Map<String, String> retired = row("RETIRED");
		DatabaseDefinitionsVault vault = vault();
		Assertions.assertThrows(BroadSQLException.class, () -> vault.saveDatabaseDefinition(definition("RETIRED", h2, null, local)));
		Assertions.assertEquals(retired, row("RETIRED"));
	}

	@Test
	void validation_aSecondConnectionForTheSameGroupAndEnvironmentIsRefused() throws Exception {
		DatabaseDefinitionsVault vault = vaultWithGroupAndEnvironments();
		vault.saveDatabaseDefinition(definition("FIRST", kinds().get(0), "SALES", "QA"));
		assertRefusedAndNothingChanged(definition("SECOND", kinds().get(0), "SALES", "QA"), "SALES");
		vault().saveDatabaseDefinition(definition("OTHERENV", kinds().get(0), "SALES", "PROD"));
		Assertions.assertNotNull(row("OTHERENV"), "the same group in another Environment is accepted");
	}

	@Test
	void validation_theTypesDriverWinsOverAMismatchingDriver() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition mismatch = definition("MISMATCH", new Kind("H2", "com.example.NotTheH2Driver", "jdbc:h2:mem:mismatch"), null, vault.resolveLocalEnvironmentId());
		vault.saveDatabaseDefinition(mismatch);
		Assertions.assertEquals("org.h2.Driver", row("MISMATCH").get("DRIVER"), "a connection's driver is its type's");
	}

	// ================================================================== TEST and CONNECT

	private DatabaseConnection connectionWith(DatabaseDefinitionsVault vault) throws Exception {
		db = TestDatabaseConnections.connectInMemory();
		TestDatabaseConnections.attachVault(db, vault);
		return db;
	}

	@Test
	void test_anUnsavedDefinitionIsTestedWithoutBeingSavedAndABadOneFailsClearly() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		DatabaseConnection connection = connectionWith(vault);
		List<Map<String, String>> before = table();
		DatabaseDefinition draft = new DatabaseDefinition("DRAFT", "H2", "org.h2.Driver", "jdbc:h2:mem:draft_test", "sa", "", "Draft");
		draft.setEnvironment(vault.resolveLocalEnvironmentId());

		StringBuffer ok = connection.testConnectionToPlatform(draft);

		Assertions.assertTrue(ok.length() > 0, "an H2 connection answers");
		Assertions.assertEquals(before, table(), "Test saves nothing");
		Assertions.assertEquals("jdbc:h2:mem:draft_test", draft.getUrl(), "Test leaves the definition as it was");
		Assertions.assertEquals("org.h2.Driver", draft.getDbDriver());

		DatabaseDefinition badUrl = new DatabaseDefinition("BAD", "H2", "org.h2.Driver", "jdbc:nosuchdb://nowhere", "sa", "", "Bad");
		Assertions.assertThrows(BroadSQLException.class, () -> connection.testConnectionToPlatform(badUrl));
		DatabaseDefinition badDriver = new DatabaseDefinition("BAD", "PostgreSQL", "com.example.NoSuchDriver", "jdbc:postgresql://localhost/x", "u", "p", "Bad");
		Assertions.assertThrows(BroadSQLException.class, () -> connection.testConnectionToPlatform(badDriver));
		Assertions.assertEquals(before, table());
	}

	@Test
	void test_aSavedConnectionIsTestedById() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition c = new DatabaseDefinition("SAVED_H2", "H2", "org.h2.Driver", "jdbc:h2:mem:saved_test", "sa", "", "Saved");
		c.setEnvironment(vault.resolveLocalEnvironmentId());
		vault.saveDatabaseDefinition(c);
		DatabaseDefinitionsVault reloaded = vault();

		Assertions.assertTrue(connectionWith(reloaded).testConnectionToExistingPlatform("SAVED_H2").length() > 0);
	}

	@Test
	void connect_aSavedAndReloadedConnectionOpensThroughConnect() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition c = new DatabaseDefinition("CONNECT_ME", "H2", "org.h2.Driver", "jdbc:h2:mem:connect_me;DB_CLOSE_DELAY=-1", "sa", "", "Connect me");
		c.setEnvironment(vault.resolveLocalEnvironmentId());
		vault.saveDatabaseDefinition(c);

		DatabaseDefinitionsVault reloaded = vault();
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection current = connectionWith(reloaded);
		CommandInterpreter interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, current);
		CommandTestSupport.shareVault(interpreter, reloaded);
		CommandConnect connect = CommandTestSupport.create(CommandConnect.class, current, console, settings);
		connect.setDatabaseConnectionsVault(reloaded);
		connect.setConsoleCommandInterpreter(interpreter);
		connect.setSession(SessionTestSupport.newSession(reloaded, settings, current));

		connect.execute("CONNECT CONNECT_ME");

		Assertions.assertTrue(current.isConnected(), console.getOutput());
		Assertions.assertEquals("CONNECT_ME", current.getPlatform().getId());
		Assertions.assertEquals("org.h2.Driver", current.getPlatform().getDbDriver());
		current.executeUpdateQuery("CREATE TABLE PROOF (ID INT)");
		current.executeUpdateQuery("INSERT INTO PROOF VALUES (1)");
		Assertions.assertTrue(console.getOutput().contains("Connected to 'CONNECT_ME'"), console.getOutput());
	}

	// ================================================================== the CLI commands, end to end

	private CapturingShellConsole scripted(String... answers) {
		CapturingShellConsole console = new CapturingShellConsole();
		console.setLineReader(new ScriptedLineReader(List.of(answers)));
		return console;
	}

	private <T extends Command> T command(Class<T> type, DatabaseDefinitionsVault vault, CapturingShellConsole console) throws Exception {
		T cmd = CommandTestSupport.create(type, connectionWith(vault), console);
		cmd.setDatabaseConnectionsVault(vault);
		return cmd;
	}

	@Test
	void cli_addConnection_createsTheConnectionWithItsTypesDriver() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		String local = vault.resolveLocalEnvironmentId();
		CapturingShellConsole console = scripted("Added by the wizard", "H2", "jdbc:h2:mem:added_by_cli", "sa", SECRET, SECRET, "", local, "wizard comment", "y");

		command(CommandManageConnectionAdd.class, vault, console).execute("ADD CONNECTION CLI_NEW");

		Map<String, String> r = row("CLI_NEW");
		Assertions.assertNotNull(r, console.getOutput().replace(SECRET, "***"));
		Assertions.assertEquals("org.h2.Driver", r.get("DRIVER"));
		Assertions.assertEquals("Added by the wizard", r.get("NAME"));
		Assertions.assertEquals("jdbc:h2:mem:added_by_cli", r.get("URL"));
		Assertions.assertEquals("H2", r.get("TYPE_ID"));
		Assertions.assertEquals("sa", r.get("USER_NAME"));
		assertPassword(SECRET, r.get("USER_PASSWORD"));
		Assertions.assertNull(r.get("INSTANCE_ID"));
		Assertions.assertEquals(local, r.get("ENVIRONMENT_ID"));
		Assertions.assertEquals("ACTIVE", r.get("STATUS_ID"));
		Assertions.assertEquals("wizard comment", r.get("COMMENT"));
		Assertions.assertTrue(console.getOutput().contains("Test to connection: OK"), "the wizard tests the new connection");
		Assertions.assertFalse(console.getOutput().contains(SECRET), "the password was printed");
	}

	@Test
	void cli_editConnection_changesOnlyWhatIsEditedAndNeverPrintsThePassword() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition c = definition("CLI_EDIT", kinds().get(0), null, vault.resolveLocalEnvironmentId());
		vault.saveDatabaseDefinition(c);
		// Name, Type, URL, User Name, User Password kept; Database Group (blank) left blank; Environment kept; Comment changed
		CapturingShellConsole console = scripted("y", "y", "y", "y", "y", "", "y", "n", "edited by the wizard", "y");

		command(CommandManageConnectionEdit.class, vault(), console).execute("EDIT CONNECTION CLI_EDIT");

		c.setComment("edited by the wizard");
		assertRow(c, "org.h2.Driver", DatabaseDefinition.STATUS_ACTIVE);
		Assertions.assertFalse(console.getOutput().contains(SECRET), "the current password was printed");
		Assertions.assertTrue(console.getOutput().contains("********"), "the current password is shown masked");
	}

	@Test
	void cli_duplicateConnection_copiesEveryFieldUnderTheNewId() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition source = definition("CLI_SRC", kinds().get(0), null, vault.resolveLocalEnvironmentId());
		vault.saveDatabaseDefinition(source);
		Map<String, String> sourceBefore = row("CLI_SRC");
		// every pre-filled field kept (Database Group blank), then save
		CapturingShellConsole console = scripted("y", "y", "y", "y", "y", "", "y", "y", "y");

		command(CommandDuplicateConnection.class, vault(), console).execute("DUPLICATE CONNECTION CLI_SRC CLI_DUP");

		DatabaseDefinition expected = definition("CLI_DUP", kinds().get(0), null, source.getEnvironment());
		expected.setDbName(source.getDbName());
		expected.setComment(source.getComment());
		assertRow(expected, "org.h2.Driver", DatabaseDefinition.STATUS_ACTIVE);
		Assertions.assertEquals(sourceBefore, row("CLI_SRC"), "the source is untouched");
		Assertions.assertFalse(console.getOutput().contains(SECRET), "the password was printed");
	}

	@Test
	void cli_addConnection_refusesAnExistingIdWithoutChangingIt() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		vault.saveDatabaseDefinition(definition("TAKEN", kinds().get(0), null, vault.resolveLocalEnvironmentId()));
		List<Map<String, String>> before = table();

		Assertions.assertThrows(BroadSQLException.class, () -> command(CommandManageConnectionAdd.class, vault(), scripted()).execute("ADD CONNECTION TAKEN"));

		Assertions.assertEquals(before, table());
	}

	// ================================================================== an edit is a draft until it is saved

	/** CONNECT {@code id} with {@code vault}, as a user does; the definition the session is then connected with. */
	private DatabaseDefinition connectThroughConnect(DatabaseDefinitionsVault vault, String id) throws Exception {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection current = connectionWith(vault);
		CommandInterpreter interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, current);
		CommandTestSupport.shareVault(interpreter, vault);
		CommandConnect connect = CommandTestSupport.create(CommandConnect.class, current, console, settings);
		connect.setDatabaseConnectionsVault(vault);
		connect.setConsoleCommandInterpreter(interpreter);
		connect.setSession(SessionTestSupport.newSession(vault, settings, current));
		connect.execute("CONNECT " + id);
		Assertions.assertTrue(current.isConnected(), console.getOutput());
		Assertions.assertEquals(id, current.getPlatform().getId(), console.getOutput());
		return current.getPlatform();
	}

	private static final String ORIGINAL_URL = "jdbc:h2:mem:edit_original;DB_CLOSE_DELAY=-1";

	/** A saved H2 connection EDITED, and the vault the edit then runs against (the one CONNECT uses afterwards). */
	private DatabaseDefinitionsVault savedEditable() throws Exception {
		DatabaseDefinitionsVault setup = vault();
		DatabaseDefinition c = new DatabaseDefinition("EDITED", "H2", "org.h2.Driver", ORIGINAL_URL, "sa", "", "Edited");
		c.setEnvironment(setup.resolveLocalEnvironmentId());
		c.setComment("original comment");
		setup.saveDatabaseDefinition(c);
		return vault();
	}

	/** Every field of a definition, to compare the live cached object before and after an edit. */
	private static List<String> fields(DatabaseDefinition d) {
		return java.util.Arrays.asList(d.getId(), d.getDbName(), d.getDbType(), d.getDbDriver(), d.getUrl(), d.getUserName(), d.getUserPassword(),
				d.getDatabaseGroup(), d.getEnvironment(), d.getComment(), d.getStatus());
	}

	@Test
	void cli_editConnection_cancelledLeavesTheCdfTheCachedDefinitionAndConnectUnchanged() throws Exception {
		DatabaseDefinitionsVault vault = savedEditable();
		DatabaseDefinition live = vault.getDatabaseConnection("EDITED");
		List<String> before = fields(live);
		List<Map<String, String>> table = table();
		// Name kept, Type kept, URL changed, User Name changed, empty Password (typed and confirmed), no Group,
		// Environment kept, Comment kept, then c(ancel)
		CapturingShellConsole console = scripted("y", "y", "n", "jdbc:h2:mem:cancelled_edit", "n", "cancelled_user", "", "", "", "y", "y", "c");

		command(CommandManageConnectionEdit.class, vault, console).execute("EDIT CONNECTION EDITED");

		Assertions.assertTrue(console.getOutput().contains("Operation aborted"), console.getOutput());
		Assertions.assertEquals(table, table(), "the CDF is unchanged");
		Assertions.assertSame(live, vault.getDatabaseConnection("EDITED"), "the vault still holds the same definition");
		Assertions.assertEquals(before, fields(live), "the cached definition kept the cancelled values");
		Assertions.assertEquals(ORIGINAL_URL, connectThroughConnect(vault, "EDITED").getUrl(), "CONNECT uses the original URL");
	}

	@Test
	void cli_editConnection_aFailedSaveLeavesTheCdfTheCachedDefinitionAndConnectUnchanged() throws Exception {
		DatabaseDefinitionsVault vault = savedEditable();
		DatabaseDefinition live = vault.getDatabaseConnection("EDITED");
		List<String> before = fields(live);
		List<Map<String, String>> table = table();
		// A URL longer than CONNECTIONS.URL (2048): the database refuses the UPDATE
		String tooLong = "jdbc:h2:mem:" + "x".repeat(2100);
		CapturingShellConsole console = scripted("y", "y", "n", tooLong, "y", "", "", "", "y", "n", "failed edit", "y");

		Assertions.assertThrows(BroadSQLException.class, () -> command(CommandManageConnectionEdit.class, vault, console).execute("EDIT CONNECTION EDITED"));

		Assertions.assertEquals(table, table(), "the CDF is unchanged");
		Assertions.assertEquals(before, fields(vault.getDatabaseConnection("EDITED")), "the cached definition kept the values of the failed save");
		Assertions.assertEquals(ORIGINAL_URL, connectThroughConnect(vault, "EDITED").getUrl(), "CONNECT uses the original URL");
	}

	@Test
	void cli_editConnection_aSuccessfulSaveUpdatesTheCdfTheCachedDefinitionAndConnect() throws Exception {
		DatabaseDefinitionsVault vault = savedEditable();
		String newUrl = "jdbc:h2:mem:edit_saved;DB_CLOSE_DELAY=-1";
		CapturingShellConsole console = scripted("y", "y", "n", newUrl, "y", "", "", "", "y", "n", "saved edit", "y");

		command(CommandManageConnectionEdit.class, vault, console).execute("EDIT CONNECTION EDITED");

		Assertions.assertEquals(newUrl, row("EDITED").get("URL"));
		Assertions.assertEquals("saved edit", row("EDITED").get("COMMENT"));
		Assertions.assertEquals(newUrl, vault.getDatabaseConnection("EDITED").getUrl(), "the vault holds the saved definition");
		Assertions.assertEquals(newUrl, connectThroughConnect(vault, "EDITED").getUrl(), "CONNECT uses the saved URL");
		Assertions.assertEquals(newUrl, vault().getDatabaseConnection("EDITED").getUrl(), "and so does a restart");
	}

	@Test
	void cli_editConnection_isCaseInsensitiveLikeTheCdfKey() throws Exception {
		DatabaseDefinitionsVault vault = savedEditable();
		CapturingShellConsole console = scripted("y", "y", "y", "y", "", "", "", "y", "n", "edited through its lower-case ID", "y");

		command(CommandManageConnectionEdit.class, vault, console).execute("EDIT CONNECTION edited");

		Assertions.assertEquals("edited through its lower-case ID", row("EDITED").get("COMMENT"), console.getOutput());
	}

	// ================================================================== Connection ID: length and case-insensitive uniqueness

	private static void assertNoRawDatabaseError(String message) {
		for (String raw : new String[] { "SQLState", "23505", "22001", "org.h2", "JdbcSQL", "Unique index", "Value too long" }) {
			Assertions.assertFalse(message.contains(raw), "a raw database error reached the user: " + message);
		}
	}

	@Test
	void id_fifteenCharactersAreAccepted() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		String id = "ABCDEFGHIJKLMNO";
		Assertions.assertEquals(DatabaseDefinitionsVault.CONNECTION_ID_MAX_LENGTH, id.length());
		CapturingShellConsole console = scripted("Fifteen", "H2", "jdbc:h2:mem:fifteen", "sa", "", "", "", vault.resolveLocalEnvironmentId(), "", "y");

		command(CommandManageConnectionAdd.class, vault, console).execute("ADD CONNECTION " + id);

		Assertions.assertNotNull(row(id), console.getOutput());
	}

	@Test
	void id_sixteenCharactersAreRejectedBeforeAnyPromptOrSql() throws Exception {
		String id = "ABCDEFGHIJKLMNOP";
		List<Map<String, String>> before = table();
		ScriptedLineReader noAnswer = new ScriptedLineReader(List.of());
		CapturingShellConsole console = new CapturingShellConsole();
		console.setLineReader(noAnswer);

		BroadSQLException cli = Assertions.assertThrows(BroadSQLException.class, () -> command(CommandManageConnectionAdd.class, vault(), console).execute("ADD CONNECTION " + id));
		Assertions.assertEquals("Connection ID '" + id + "' is 16 characters long: it must not exceed 15 characters.", cli.getMessage());

		DatabaseDefinition direct = new DatabaseDefinition(id, "H2", "org.h2.Driver", "jdbc:h2:mem:x", "sa", "", "Too long");
		direct.setEnvironment(vault().resolveLocalEnvironmentId());
		BroadSQLException save = Assertions.assertThrows(BroadSQLException.class, () -> vault().saveDatabaseDefinition(direct));
		Assertions.assertTrue(save.getMessage().contains("must not exceed 15 characters"), save.getMessage());
		assertNoRawDatabaseError(save.getMessage());

		BroadSQLException duplicate = Assertions.assertThrows(BroadSQLException.class,
				() -> command(CommandDuplicateConnection.class, vault(), scripted()).execute("DUPLICATE CONNECTION WORLD " + id));
		Assertions.assertTrue(duplicate.getMessage().contains("must not exceed 15 characters"), duplicate.getMessage());
		Assertions.assertEquals(before, table());
	}

	@Test
	void id_aCaseVariantOfAnExistingIdIsRejectedWithoutARawDatabaseError() throws Exception {
		// The shipped CDF has WORLD
		Assertions.assertNotNull(row("WORLD"));
		List<Map<String, String>> before = table();
		String expected = "Connection 'WORLD' already exists. Connection IDs are case-insensitive.";

		BroadSQLException cli = Assertions.assertThrows(BroadSQLException.class, () -> command(CommandManageConnectionAdd.class, vault(), scripted()).execute("ADD CONNECTION world"));
		Assertions.assertEquals(expected, cli.getMessage());
		BroadSQLException dup = Assertions.assertThrows(BroadSQLException.class,
				() -> command(CommandDuplicateConnection.class, vault(), scripted()).execute("DUPLICATE CONNECTION WORLD World"));
		Assertions.assertEquals(expected, dup.getMessage());

		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition lower = new DatabaseDefinition("world", "H2", "org.h2.Driver", "jdbc:h2:mem:x", "sa", "", "lower");
		lower.setEnvironment(vault.resolveLocalEnvironmentId());
		BroadSQLException save = Assertions.assertThrows(BroadSQLException.class, () -> vault.saveDatabaseDefinition(lower));
		Assertions.assertEquals(expected, save.getMessage());
		assertNoRawDatabaseError(save.getMessage());

		BroadSQLException exact = Assertions.assertThrows(BroadSQLException.class, () -> command(CommandManageConnectionAdd.class, vault(), scripted()).execute("ADD CONNECTION WORLD"));
		Assertions.assertEquals(com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages.ERR_CONN_02, exact.getMessage());
		Assertions.assertEquals(before, table());
	}

	@Test
	void id_anInactiveConnectionStillOccupiesItsIdWhateverTheCase() throws Exception {
		vault().softDeleteDatabaseDefinition("WORLD");
		List<Map<String, String>> before = table();

		CapturingShellConsole refused = scripted("n");
		command(CommandManageConnectionAdd.class, vault(), refused).execute("ADD CONNECTION world");
		Assertions.assertTrue(refused.getOutput().contains("An inactive connection already exists for ID 'WORLD'"), refused.getOutput());
		Assertions.assertEquals(before, table(), "no second row, nothing reactivated");

		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition lower = new DatabaseDefinition("world", "H2", "org.h2.Driver", "jdbc:h2:mem:x", "sa", "", "lower");
		lower.setEnvironment(vault.resolveLocalEnvironmentId());
		BroadSQLException save = Assertions.assertThrows(BroadSQLException.class, () -> vault.saveDatabaseDefinition(lower));
		Assertions.assertEquals("Connection 'WORLD' already exists. Connection IDs are case-insensitive.", save.getMessage());

		command(CommandManageConnectionAdd.class, vault(), scripted("y")).execute("ADD CONNECTION World");
		Assertions.assertEquals(DatabaseDefinition.STATUS_ACTIVE, row("WORLD").get("STATUS_ID"), "the inactive WORLD is the one reactivated");
	}

	// ================================================================== one driver contract: TYPE.DRIVER, else CONNECTIONS.DRIVER

	@Test
	void driver_theShippedOracleTypeSavesAndReloadsWithItsDriver() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition oracle = new DatabaseDefinition("ORA_NEW", "Oracle", null, "jdbc:oracle:thin:@//dbhost:1521/ORCL", "scott", SECRET, "Oracle");
		oracle.setEnvironment(vault.resolveLocalEnvironmentId());

		vault.saveDatabaseDefinition(oracle);

		Assertions.assertEquals("oracle.jdbc.OracleDriver", row("ORA_NEW").get("DRIVER"));
		Assertions.assertEquals("oracle.jdbc.OracleDriver", vault().getDatabaseConnection("ORA_NEW").getDbDriver());
	}

	@Test
	void driver_aConnectionLevelDriverOfATypeWithoutOneIsSavedAndReloaded() throws Exception {
		try (Connection conn = cdfConnection(); Statement stat = conn.createStatement()) {
			stat.executeUpdate("INSERT INTO TYPE (ID, MODE, DRIVER, STATUS_ID) VALUES ('CustomDB', 'Default', '', 'ACTIVE')");
		}
		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition custom = new DatabaseDefinition("CUSTOM", "CustomDB", "com.example.CustomDriver", "jdbc:custom://host/db", "u", SECRET, "Custom");
		custom.setEnvironment(vault.resolveLocalEnvironmentId());

		vault.saveDatabaseDefinition(custom);

		Assertions.assertEquals("com.example.CustomDriver", row("CUSTOM").get("DRIVER"));
		Assertions.assertEquals("com.example.CustomDriver", vault().getDatabaseConnection("CUSTOM").getDbDriver(), "reload returns the saved driver");
		vault().softDeleteDatabaseDefinition("CUSTOM");
		Assertions.assertEquals("com.example.CustomDriver", vault().getInactiveConnectionDetails().stream().filter(d -> d.getId().equals("CUSTOM")).findFirst()
				.orElseThrow().getDbDriver(), "and so does the inactive list");
	}

	@Test
	void driver_theTypesDriverWinsOnSaveAndOnReload() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		DatabaseDefinition h2 = new DatabaseDefinition("TYPED", "H2", "com.example.Ignored", "jdbc:h2:mem:typed", "sa", "", "Typed");
		h2.setEnvironment(vault.resolveLocalEnvironmentId());

		vault.saveDatabaseDefinition(h2);

		Assertions.assertEquals("org.h2.Driver", row("TYPED").get("DRIVER"));
		Assertions.assertEquals("org.h2.Driver", vault().getDatabaseConnection("TYPED").getDbDriver());
	}
}
