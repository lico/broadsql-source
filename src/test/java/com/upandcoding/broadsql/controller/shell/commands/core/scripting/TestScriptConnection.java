package com.upandcoding.broadsql.controller.shell.commands.core.scripting;

import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

class TestScriptConnection {

	private DatabaseDefinitionsVault vaultWithInMemoryH2(String platformId) throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		DatabaseDefinition definition = new DatabaseDefinition(platformId);
		definition.setDbType("H2");
		definition.setUrl("jdbc:h2:mem:" + platformId + ";DB_CLOSE_DELAY=-1");
		definition.setStatus(DatabaseDefinition.STATUS_ACTIVE);
		definition.setEnvironment("LOCAL");
		vault.saveDatabaseDefinition(definition);
		vault.load();
		return vault;
	}

	@Test
	void connectOpensAnIndependentConnectionAndExecutesQueries() throws Exception {
		String platformId = "SC_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		DatabaseDefinitionsVault vault = vaultWithInMemoryH2(platformId);

		ScriptConnection conn = ScriptConnection.connect(platformId, vault);
		try {
			conn.executeUpdate("CREATE TABLE T (ID INT, NAME VARCHAR(20))");
			int inserted = conn.executeUpdate("INSERT INTO T VALUES (1, 'Alice')");
			Assertions.assertEquals(1, inserted);

			ScriptResultSet rs = conn.execute("SELECT ID, NAME FROM T");
			Assertions.assertEquals(1, rs.size());
			Assertions.assertEquals("Alice", rs.get(0).get("NAME"));
		} finally {
			conn.close();
		}
	}

	@Test
	void connectingToAnUnknownNameThrows() throws Exception {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		Assertions.assertThrows(BroadSQLException.class, () -> ScriptConnection.connect("DOES_NOT_EXIST", vault));
	}

	@Test
	void wrapExistingDoesNotCloseTheUnderlyingConnection() throws Exception {
		DatabaseConnection db = TestDatabaseConnections.connectInMemory("CREATE TABLE T (ID INT)");

		ScriptConnection wrapped = ScriptConnection.wrapExisting(db.getDirectConnection(), "db");
		wrapped.close();

		Assertions.assertFalse(db.getDirectConnection().isClosed());
	}

	@Test
	void twoConnectionsCanBeOpenSimultaneously() throws Exception {
		String sourceId = "SOURCE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		String targetId = "TARGET_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		for (String id : new String[] { sourceId, targetId }) {
			DatabaseDefinition definition = new DatabaseDefinition(id);
			definition.setDbType("H2");
			definition.setUrl("jdbc:h2:mem:" + id + ";DB_CLOSE_DELAY=-1");
			definition.setStatus(DatabaseDefinition.STATUS_ACTIVE);
			definition.setEnvironment("LOCAL");
			vault.saveDatabaseDefinition(definition);
		}
		vault.load();

		ScriptConnection source = ScriptConnection.connect(sourceId, vault);
		ScriptConnection target = ScriptConnection.connect(targetId, vault);
		try {
			source.executeUpdate("CREATE TABLE ORDERS (ID INT, AMOUNT DECIMAL(10,2))");
			source.executeUpdate("INSERT INTO ORDERS VALUES (1, 100.00)");
			target.executeUpdate("CREATE TABLE ORDERS_ARCHIVE (ID INT, AMOUNT DECIMAL(10,2))");

			ScriptResultSet rows = source.execute("SELECT ID, AMOUNT FROM ORDERS");
			for (int i = 0; i < rows.size(); i++) {
				target.executeUpdate("INSERT INTO ORDERS_ARCHIVE VALUES (" + rows.get(i).get("ID") + ", " + rows.get(i).get("AMOUNT") + ")");
			}

			ScriptResultSet archived = target.execute("SELECT ID, AMOUNT FROM ORDERS_ARCHIVE");
			Assertions.assertEquals(1, archived.size());
		} finally {
			source.close();
			target.close();
		}
	}
}
