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
	void refusesANonH2TargetType() throws BroadSQLException {
		String url = "jdbc:hsqldb:mem:linktest_" + UUID.randomUUID().toString().replace("-", "");
		DatabaseDefinition hsql = new DatabaseDefinition("REMOTE");
		hsql.setDbDriver("org.hsqldb.jdbc.JDBCDriver");
		hsql.setDbType(SpringPropertiesConfig.DBTYPE_HSQL);
		hsql.setDbName("REMOTE");
		hsql.setUrl(url);
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault(hsql);
		TestDatabaseConnections.attachVault(db, vault);
		CommandLinkTable cmd = CommandTestSupport.create(CommandLinkTable.class, db, console);
		cmd.setDatabaseConnectionsVault(vault);

		Assertions.assertDoesNotThrow(() -> cmd.execute("LINK TABLE REMOTE PRODUCT"));

		Assertions.assertTrue(console.getOutput().contains("Operation not supported for database type: 'HSQL'"),
				"expected the unsupported-type error, got:\n" + console.getOutput());
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
}
