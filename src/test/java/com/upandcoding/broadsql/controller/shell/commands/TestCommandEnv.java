package com.upandcoding.broadsql.controller.shell.commands;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.Session;
import com.upandcoding.broadsql.controller.shell.SessionTestSupport;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandConnect;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandEnv;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import com.upandcoding.broadsql.controller.shell.completion.EntityCompletionService;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;

/**
 * SPRINT 2309T (#159): {@code ENV <environment>} and its aliases, end to end through a real
 * {@link CommandInterpreter}, a real file-backed CDF and real in-memory H2 databases - one per physical
 * Connection, each holding a {@code MARKER} row naming itself, so every test checks which database the
 * session is really connected to afterwards, not only which name was printed.
 *
 * <p>Fixture: Database Group {@code TAT} with {@code INT -> TATD3}, {@code QA -> TATV3},
 * {@code PROD -> TATR3} (a Production Environment) and {@code BROKEN -> TATB3} (unreachable URL);
 * Environment {@code UAT} exists but has no {@code TAT} Connection; Database Group {@code OLD} has
 * {@code INT -> OLDD3} and maps {@code QA} to the inactive {@code OLDV3}; a standalone physical
 * Connection is literally named {@code QA}.
 */
class TestCommandEnv {

	private static final String GROUP = "TAT";

	// Built once per class: the encrypted file-backed CDF costs several seconds per connection saved, and
	// no test modifies it. Each test gets its own session connection object and interpreter.
	private static DatabaseDefinitionsVault vault;
	private ConsoleSettings settings;
	private CapturingShellConsole console;
	private DatabaseConnection db;
	private CommandInterpreter interpreter;

	@BeforeAll
	static void createConnectionsDefinitionFile() throws Exception {
		vault = TestDatabaseConnections.newFileBackedVault(GROUP, "OLD");
		vault.saveEnvironment(new EnvironmentDefinition("INT", "Integration", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("QA", "QA", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("PROD", "Production", true, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("UAT", "UAT", false, null, DatabaseDefinition.STATUS_ACTIVE));
		vault.saveEnvironment(new EnvironmentDefinition("BROKEN", "Broken", false, null, DatabaseDefinition.STATUS_ACTIVE));

		addConnection("TATD3", GROUP, "INT");
		addConnection("TATV3", GROUP, "QA");
		addConnection("TATR3", GROUP, "PROD");
		addConnection("QA", null, vault.resolveLocalEnvironmentId());
		addConnection("OLDD3", "OLD", "INT");
		addConnection("OLDV3", "OLD", "QA");
		vault.saveDatabaseDefinition(definition("TATB3", GROUP, "BROKEN",
				"jdbc:h2:C:\\definitely\\does\\not\\exist\\nested\\folder\\db;IFEXISTS=TRUE"));
		vault.load();
		vault.softDeleteDatabaseDefinition("OLDV3");
		vault.load();
	}

	@BeforeEach
	void setUp() throws Exception {
		settings = TestDatabaseConnections.defaultConsoleSettings();
		console = new CapturingShellConsole();

		db = new DatabaseConnection(settings, vault);
		db.setPlatformCode("TATD3");
		db.connect();
		db.setCmdLineConsole(console);

		interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, db);
		CommandTestSupport.shareVault(interpreter, vault);
		interpreter.databaseConnectionsVault = vault;
		Session session = SessionTestSupport.newSession(vault, settings, db);
		interpreter.setSession(session);
		for (Command command : interpreter.getCommands().values()) {
			command.setSession(session);
		}
		interpreter.setPlatform("TATD3");
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private static DatabaseDefinition definition(String id, String group, String environment, String url) {
		DatabaseDefinition def = new DatabaseDefinition(id);
		def.setDbType("H2");
		def.setDbDriver("org.h2.Driver");
		def.setDbName(id);
		def.setUrl(url);
		def.setStatus(DatabaseDefinition.STATUS_ACTIVE);
		def.setDatabaseGroup(group);
		def.setEnvironment(environment);
		return def;
	}

	/** A physical Connection backed by its own in-memory H2 database whose MARKER table names it. */
	private static void addConnection(String id, String group, String environment) throws Exception {
		String url = "jdbc:h2:mem:env_" + id + "_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
		try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
			st.execute("CREATE TABLE MARKER(NAME VARCHAR(20))");
			try (PreparedStatement ps = c.prepareStatement("INSERT INTO MARKER VALUES (?)")) {
				ps.setString(1, id);
				ps.executeUpdate();
			}
		}
		vault.saveDatabaseDefinition(definition(id, group, environment, url));
	}

	private void run(String line) throws BroadSQLException {
		interpreter.setQuery(line);
		interpreter.executeCommand();
	}

	/** The physical database the session's single connection object is really attached to. */
	private String connectedMarker() throws Exception {
		try (Statement st = db.getDirectConnection().createStatement(); java.sql.ResultSet rs = st.executeQuery("SELECT NAME FROM MARKER")) {
			rs.next();
			return rs.getString(1);
		}
	}

	private void assertConnectedTo(String id) throws Exception {
		Assertions.assertEquals(id, interpreter.getPlatform(), "interpreter's current connection; console:\n" + console.getOutput());
		Assertions.assertEquals(id, connectedMarker(), "the session must really be attached to " + id);
	}

	// --- physical Connection (unchanged) -------------------------------------------------------------

	@Test
	void connectStillOpensAPhysicalConnection() throws Exception {
		run("CONNECT TATV3");
		assertConnectedTo("TATV3");
	}

	@Test
	void connectAliasesStillOpenAPhysicalConnection() throws Exception {
		for (String alias : new String[] { "OPEN", "CON", "CONN" }) {
			run("CONNECT TATD3");
			run(alias + " TATV3");
			assertConnectedTo("TATV3");
		}
	}

	// --- Environment switching -----------------------------------------------------------------------

	@Test
	void envSwitchesToTheConnectionMappedInTheCurrentGroup() throws Exception {
		run("ENV QA");
		assertConnectedTo("TATV3");
	}

	@Test
	void envtAliasSwitchesTheSameWay() throws Exception {
		run("ENVT QA");
		assertConnectedTo("TATV3");
	}

	@Test
	void connectEnvironmentAliasSwitchesTheSameWay() throws Exception {
		run("CONNECT ENVIRONMENT QA");
		assertConnectedTo("TATV3");
	}

	@Test
	void environmentMatchingIsCaseInsensitive() throws Exception {
		run("env qa");
		assertConnectedTo("TATV3");
	}

	@Test
	void promptShowsOnlyThePhysicalConnectionName() throws Exception {
		run("ENV QA");
		Assertions.assertEquals("TATV3> ", console.getPrompt(), "prompt must stay Connection-only, no [group/environment] suffix");
	}

	// --- Same name: never ambiguous ------------------------------------------------------------------

	@Test
	void connectWithANameThatIsAlsoAnEnvironmentOpensThePhysicalConnection() throws Exception {
		run("CONNECT QA");
		assertConnectedTo("QA");
	}

	@Test
	void envWithANameThatIsAlsoAConnectionResolvesTheEnvironment() throws Exception {
		run("ENV QA");
		assertConnectedTo("TATV3");
		Assertions.assertFalse(console.getOutput().toLowerCase().contains("ambiguous"), console.getOutput());
	}

	// --- Errors --------------------------------------------------------------------------------------

	@Test
	void noCurrentDatabaseGroupFailsWithoutSearchingOtherGroups() throws Exception {
		run("CONNECT QA"); // standalone: no Database Group
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run("ENV QA"));
		Assertions.assertTrue(ex.getMessage().contains("there is no current Database Group"), ex.getMessage());
		assertConnectedTo("QA");
	}

	@Test
	void noCurrentConnectionFailsClearly() throws Exception {
		CommandEnv cmd = CommandTestSupport.create(CommandEnv.class, db, console, settings);
		cmd.setDatabaseConnectionsVault(vault);
		cmd.setPlatform(null);
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("ENV QA"));
		Assertions.assertTrue(ex.getMessage().contains("no current Database Group"), ex.getMessage());
	}

	@Test
	void unknownEnvironmentFailsClearly() throws Exception {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run("ENV NOPE"));
		Assertions.assertTrue(ex.getMessage().contains("Environment 'NOPE' does not exist"), ex.getMessage());
		assertConnectedTo("TATD3");
	}

	@Test
	void environmentWithoutAConnectionInTheGroupFailsClearly() throws Exception {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run("ENV UAT"));
		Assertions.assertTrue(ex.getMessage().contains("No connection is configured for Environment 'UAT' in Database Group 'TAT'"), ex.getMessage());
		assertConnectedTo("TATD3");
	}

	@Test
	void environmentMappedToAnInactiveConnectionFailsClearly() throws Exception {
		// Group OLD's only QA Connection (OLDV3) is inactive; the current connection is OLD's INT one.
		CommandEnv cmd = CommandTestSupport.create(CommandEnv.class, db, console, settings);
		cmd.setDatabaseConnectionsVault(vault);
		cmd.setPlatform("OLDD3");
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("ENV QA"));
		Assertions.assertTrue(ex.getMessage().contains("mapped to Connection 'OLDV3', which is inactive"), ex.getMessage());
		Assertions.assertTrue(ex.getMessage().contains("REACTIVATE CONNECTION OLDV3"), ex.getMessage());
	}

	@Test
	void failedPhysicalConnectionAfterSuccessfulResolutionLeavesTheSessionUntouched() throws Exception {
		Assertions.assertThrows(BroadSQLException.class, () -> run("ENV BROKEN"));
		assertConnectedTo("TATD3");
	}

	@Test
	void missingEnvironmentArgumentFailsClearly() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> run("ENV"));
		Assertions.assertTrue(ex.getMessage().contains("You must specify an Environment"), ex.getMessage());
	}

	// --- Production: same path as CONNECT -------------------------------------------------------------

	/** Records which physical Connection the shared {@code connectToConnection} path was asked to open. */
	static final class RecordingEnv extends CommandEnv {
		final List<String> opened = new ArrayList<>();

		@Override
		protected void connectToConnection(String id) {
			opened.add(id);
		}
	}

	static final class RecordingConnect extends CommandConnect {
		final List<String> opened = new ArrayList<>();

		@Override
		protected void connectToConnection(String id) {
			opened.add(id);
		}
	}

	@Test
	void productionEnvironmentGoesThroughExactlyTheSamePhysicalConnectPathAsConnect() throws Exception {
		RecordingEnv env = CommandTestSupport.create(RecordingEnv.class, db, console, settings);
		env.setDatabaseConnectionsVault(vault);
		env.setPlatform("TATD3");
		RecordingConnect connect = CommandTestSupport.create(RecordingConnect.class, db, console, settings);
		connect.setDatabaseConnectionsVault(vault);

		env.execute("ENV PROD");
		connect.execute("CONNECT TATR3");

		Assertions.assertEquals(List.of("TATR3"), env.opened, "ENV PROD must hand the resolved physical Connection to the CONNECT path");
		Assertions.assertEquals(connect.opened, env.opened, "ENV PROD and CONNECT TATR3 must reach the same physical-connect call");
	}

	@Test
	void productionEnvironmentSwitchEndToEnd() throws Exception {
		run("ENV PROD");
		assertConnectedTo("TATR3");
	}

	// --- Completion ----------------------------------------------------------------------------------

	private List<String> complete(String... words) {
		EntityCompletionService service = EntityCompletionService.standard(interpreter.getCommands(), db);
		List<String> result = new ArrayList<>();
		for (CompletionCandidate c : service.complete(List.of(words), words.length - 1)) {
			result.add(c.getDisplay());
		}
		return result;
	}

	@Test
	void connectCompletionOffersConnectionsOnlyNeverEnvironments() {
		List<String> offered = complete("CONNECT", "");
		Assertions.assertTrue(offered.contains("TATV3"), offered.toString());
		Assertions.assertFalse(offered.contains("INT"), "Environment names must not be mixed into CONNECT completion: " + offered);
		Assertions.assertFalse(offered.contains("PROD"), offered.toString());
		for (String alias : new String[] { "OPEN", "CON", "CONN" }) {
			Assertions.assertEquals(offered, complete(alias, ""), alias);
		}
	}

	@Test
	void envCompletionOffersTheCurrentGroupsEnvironments() {
		Assertions.assertEquals(List.of("BROKEN", "INT", "PROD", "QA"), complete("ENV", ""));
		Assertions.assertEquals(List.of("QA"), complete("ENV", "Q"));
		Assertions.assertEquals(List.of("QA"), complete("ENVT", "q"));
		Assertions.assertEquals(List.of("QA"), complete("CONNECT", "ENVIRONMENT", "Q"));
		Assertions.assertFalse(complete("ENV", "").contains("UAT"), "UAT has no Connection in TAT - ENV cannot switch to it");
		Assertions.assertFalse(complete("ENV", "").contains("TATV3"), "ENV completion must not offer Connection ids");
	}

	@Test
	void envCompletionIsEmptyWithoutACurrentGroup() throws Exception {
		run("CONNECT QA");
		Assertions.assertEquals(List.of(), complete("ENV", ""));
	}

	@Test
	void envDeclaresTheGroupEnvironmentCompletionType() {
		Assertions.assertEquals(List.of(CompletionEntityType.GROUP_ENVIRONMENT), new CommandEnv().getCompletionArguments());
		Assertions.assertEquals(List.of(CompletionEntityType.CONNECTION), new CommandConnect().getCompletionArguments());
	}
}
