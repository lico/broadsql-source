package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestScriptRunContext {

	@Test
	void emptyContextHasNoActiveConnection() {
		Assertions.assertFalse(ScriptRunContext.empty().hasActiveConnection());
	}

	@Test
	void aRealConnectedDatabaseConnectionIsReportedActive() throws BroadSQLException {
		DatabaseConnection db = TestDatabaseConnections.connectInMemory();
		try {
			ScriptRunContext context = new ScriptRunContext(db, null, null, null, null, "test");
			Assertions.assertTrue(context.hasActiveConnection());
		} finally {
			TestDatabaseConnections.close(db);
		}
	}

	@Test
	void aClosedConnectionIsNotReportedActive() throws BroadSQLException {
		DatabaseConnection db = TestDatabaseConnections.connectInMemory();
		db.close();
		ScriptRunContext context = new ScriptRunContext(db, null, null, null, null, "test");

		Assertions.assertFalse(context.hasActiveConnection());
	}
}
