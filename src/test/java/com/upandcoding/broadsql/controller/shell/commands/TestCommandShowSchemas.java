package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowSchemas;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandShowSchemas {

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
	void listsAllSchemasWithNoArgument() throws BroadSQLException {
		CommandShowSchemas cmd = CommandTestSupport.create(CommandShowSchemas.class, db, console);

		cmd.execute("SHOW SCHEMAS");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("PUBLIC"), "expected PUBLIC in output, got:\n" + output);
		Assertions.assertTrue(output.contains("MYSCHEMA"), "expected MYSCHEMA in output, got:\n" + output);
	}

	@Test
	void filtersSchemasByContainsMatch() throws BroadSQLException {
		CommandShowSchemas cmd = CommandTestSupport.create(CommandShowSchemas.class, db, console);

		cmd.execute("SHOW SCHEMAS MYSCH");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("MYSCHEMA"), "expected MYSCHEMA in output, got:\n" + output);
		Assertions.assertFalse(output.contains("PUBLIC"), "did not expect PUBLIC in output, got:\n" + output);
	}
}
