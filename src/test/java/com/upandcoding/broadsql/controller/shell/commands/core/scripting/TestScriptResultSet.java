package com.upandcoding.broadsql.controller.shell.commands.core.scripting;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestScriptResultSet {

	@Test
	void readsRowsInColumnOrderKeyedByLabel() throws Exception {
		DatabaseConnection db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE ORDERS (ID INT, CUSTOMER VARCHAR(50), AMOUNT DECIMAL(10,2))",
				"INSERT INTO ORDERS VALUES (1, 'Acme', 100.50)",
				"INSERT INTO ORDERS VALUES (2, 'Beta', 42.00)");

		try (Statement statement = db.getDirectConnection().createStatement();
				ResultSet rs = statement.executeQuery("SELECT ID, CUSTOMER, AMOUNT FROM ORDERS ORDER BY ID")) {

			ScriptResultSet result = ScriptResultSet.readAll(rs);

			Assertions.assertEquals(2, result.size());

			LinkedHashMap<String, Object> first = result.get(0);
			Assertions.assertEquals("[ID, CUSTOMER, AMOUNT]", first.keySet().toString());
			Assertions.assertEquals(1, first.get("ID"));
			Assertions.assertEquals("Acme", first.get("CUSTOMER"));

			LinkedHashMap<String, Object> second = result.get(1);
			Assertions.assertEquals("Beta", second.get("CUSTOMER"));
		}
	}

	@Test
	void emptyResultSetYieldsZeroRows() throws Exception {
		DatabaseConnection db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE EMPTY_TABLE (ID INT)");

		try (Statement statement = db.getDirectConnection().createStatement();
				ResultSet rs = statement.executeQuery("SELECT ID FROM EMPTY_TABLE")) {

			ScriptResultSet result = ScriptResultSet.readAll(rs);

			Assertions.assertEquals(0, result.size());
		}
	}
}
