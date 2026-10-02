package com.upandcoding.broadsql.controller.shell.commands;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.misc.CommandLinkTable;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

class TestCommandLinkTable {

	private DatabaseConnection db;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory();
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void rejectsAMissingConnectionId() {
		CommandLinkTable cmd = CommandTestSupport.create(CommandLinkTable.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("LINK TABLE"));

		Assertions.assertTrue(console.getOutput().contains("You must specify a connection ID"),
				"expected the missing-id error, got:\n" + console.getOutput());
	}

	@Test
	void refusesAConnectionThatIsNotDefined() throws BroadSQLException {
		CommandLinkTable cmd = CommandTestSupport.create(CommandLinkTable.class, db, console);
		cmd.setDatabaseConnectionsVault(TestDatabaseConnections.newVault());

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("LINK TABLE DOESNOTEXIST PRODUCT"));

		Assertions.assertEquals("Connection 'DOESNOTEXIST' not defined", ex.getLocalizedMessage());
	}


	@Test
	void linksAnExistingH2TableAndItsDataIsVisibleLocally() throws Exception {
		DatabaseDefinition remoteDef = TestDatabaseConnections.newInMemoryTarget("REMOTE");
		remoteDef.setUserName("sa");
		remoteDef.setUserPassword("");
		try (Connection seedConn = DriverManager.getConnection(remoteDef.getUrl(), "sa", "");
				Statement stmt = seedConn.createStatement()) {
			stmt.execute("CREATE TABLE PRODUCT (ID INT PRIMARY KEY, NAME VARCHAR(50))");
			stmt.execute("INSERT INTO PRODUCT VALUES (1, 'Widget')");
		}
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault(remoteDef);
		TestDatabaseConnections.attachVault(db, vault);
		CommandLinkTable cmd = CommandTestSupport.create(CommandLinkTable.class, db, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("LINK TABLE REMOTE PRODUCT");

		try (Statement stmt = db.getDirectConnection().createStatement();
				ResultSet rs = stmt.executeQuery("SELECT NAME FROM PRODUCT WHERE ID = 1")) {
			Assertions.assertTrue(rs.next(), "expected the linked table to return the remote row");
			Assertions.assertEquals("Widget", rs.getString(1));
		}
	}

	private static DatabaseDefinition hsqldb(String id, String url) {
		DatabaseDefinition def = new DatabaseDefinition(id);
		def.setDbDriver("org.hsqldb.jdbc.JDBCDriver");
		def.setDbType(SpringPropertiesConfig.DBTYPE_HSQL);
		def.setDbName(id);
		def.setUrl(url);
		def.setUserName("SA");
		def.setUserPassword("");
		return def;
	}

	@Test
	void linksATableOfAnHsqldbConnectionIntoTheCurrentH2Database() throws Exception {
		String url = "jdbc:hsqldb:mem:linktest_" + UUID.randomUUID().toString().replace("-", "");
		try (Connection seedConn = DriverManager.getConnection(url, "SA", ""); Statement stmt = seedConn.createStatement()) {
			stmt.execute("CREATE TABLE PRODUCT (ID INT PRIMARY KEY, NAME VARCHAR(50))");
			stmt.execute("INSERT INTO PRODUCT VALUES (1, 'Gadget')");
		}
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault(hsqldb("REMOTE", url));
		TestDatabaseConnections.attachVault(db, vault);
		CommandLinkTable cmd = CommandTestSupport.create(CommandLinkTable.class, db, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("LINK TABLE REMOTE PRODUCT");

		Assertions.assertFalse(console.wasErrorReported(), console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("Table 'PRODUCT' linked from connection 'REMOTE'."), console.getOutput());
		try (Statement stmt = db.getDirectConnection().createStatement(); ResultSet rs = stmt.executeQuery("SELECT NAME FROM PRODUCT WHERE ID = 1")) {
			Assertions.assertTrue(rs.next(), "the linked table reads the HSQLDB row");
			Assertions.assertEquals("Gadget", rs.getString(1));
		}
	}

	@Test
	void refusesANonH2CurrentConnectionBeforeSendingAnySqlOrPassword() throws Exception {
		String url = "jdbc:hsqldb:mem:current_" + UUID.randomUUID().toString().replace("-", "");
		DatabaseConnection hsqlCurrent = TestDatabaseConnections.connect(hsqldb("CURRENT_HSQL", url));
		try {
			DatabaseDefinition remote = TestDatabaseConnections.newInMemoryTarget("REMOTE");
			remote.setUserName("sa");
			remote.setUserPassword("Top$ecret42");
			DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault(remote);
			TestDatabaseConnections.attachVault(hsqlCurrent, vault);
			CommandLinkTable cmd = CommandTestSupport.create(CommandLinkTable.class, hsqlCurrent, console);
			cmd.setDatabaseConnectionsVault(vault);

			cmd.execute("LINK TABLE REMOTE PRODUCT");

			String output = console.getOutput();
			Assertions.assertTrue(output.contains("LINK TABLE needs an H2 current connection") && output.contains("'HSQL'"), output);
			Assertions.assertFalse(output.contains("Top$ecret42"), "the password must never be shown:\n" + output);
			try (Statement stmt = hsqlCurrent.getDirectConnection().createStatement();
					ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'PRODUCT'")) {
				rs.next();
				Assertions.assertEquals(0, rs.getInt(1), "nothing may have been created on the HSQLDB connection");
			}
		} finally {
			TestDatabaseConnections.close(hsqlCurrent);
		}
	}

	@Test
	void aFailedLinkNeverShowsTheRemotePassword() throws Exception {
		DatabaseDefinition remote = TestDatabaseConnections.newInMemoryTarget("REMOTE");
		remote.setUserName("SA");
		remote.setUserPassword("Pa'ss$ecret");
		try (Connection seedConn = DriverManager.getConnection(remote.getUrl(), "SA", "Pa'ss$ecret")) {
			Assertions.assertNotNull(seedConn);
		}
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault(remote);
		TestDatabaseConnections.attachVault(db, vault);
		CommandLinkTable cmd = CommandTestSupport.create(CommandLinkTable.class, db, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("LINK TABLE REMOTE NO_SUCH_TABLE");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("LINK TABLE failed"), output);
		Assertions.assertFalse(output.contains("Pa'ss$ecret") || output.contains("Pa''ss$ecret"), "the password must never be shown:\n" + output);
	}

	@Test
	void requiresTheTableToLink() {
		CommandLinkTable cmd = CommandTestSupport.create(CommandLinkTable.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("LINK TABLE REMOTE"));

		Assertions.assertTrue(console.getOutput().contains("You must specify the table to link"), console.getOutput());
	}
}
