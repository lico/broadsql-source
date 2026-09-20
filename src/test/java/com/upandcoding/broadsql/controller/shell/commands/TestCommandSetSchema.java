package com.upandcoding.broadsql.controller.shell.commands;

import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.set.CommandSetSchema;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandSetSchema {

	private DatabaseConnection db;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory("CREATE SCHEMA MYSCHEMA");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void switchesToAnExistingSchema() throws BroadSQLException, SQLException {
		CommandSetSchema cmd = CommandTestSupport.create(CommandSetSchema.class, db, console);

		cmd.execute("SET SCHEMA MYSCHEMA");

		Assertions.assertEquals("MYSCHEMA", db.getDirectConnection().getSchema());
	}

	@Test
	void rejectsAnUnknownSchema() throws SQLException {
		CommandSetSchema cmd = CommandTestSupport.create(CommandSetSchema.class, db, console);
		String schemaBefore = db.getDirectConnection().getSchema();

		Assertions.assertDoesNotThrow(() -> cmd.execute("SET SCHEMA DOESNOTEXIST"));

		Assertions.assertTrue(console.getOutput().contains("Schema 'DOESNOTEXIST' does not exist"),
				"expected the unknown-schema error, got:\n" + console.getOutput());
		Assertions.assertEquals(schemaBefore, db.getDirectConnection().getSchema(), "schema must be unchanged");
	}

	@Test
	void rejectsAMissingSchemaName() {
		CommandSetSchema cmd = CommandTestSupport.create(CommandSetSchema.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("SET SCHEMA"));

		Assertions.assertTrue(console.getOutput().contains("You must specify a valid schema name"),
				"expected the missing-schema-name error, got:\n" + console.getOutput());
	}
}
