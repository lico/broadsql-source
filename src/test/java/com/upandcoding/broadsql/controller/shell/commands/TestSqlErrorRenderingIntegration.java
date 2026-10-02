package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandDefault;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * End-to-end: a real SQL statement typed at the prompt, run through {@code CommandDefault} exactly
 * as production code does, must surface as the readable {@code SqlErrorRenderer} block - not the
 * old one-line "ERROR: java.sql.SQLException: ..." - confirming the wiring in
 * {@code DatabaseConnection} (throws {@code SqlExecutionException}) and {@code ShellConsole.error}
 * (renders it) both actually fire together, not just each in isolation.
 */
class TestSqlErrorRenderingIntegration {

	private DatabaseConnection db;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void aTypedSelectWithASyntaxErrorPrintsTheStructuredDiagnosticBlock() throws BroadSQLException {
		CommandDefault cmd = CommandTestSupport.create(CommandDefault.class, db, console);
		cmd.execute("SELECT * FORM CUSTOMER");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("SQL ERROR"), output);
		Assertions.assertTrue(output.contains("SQLState"), output);
		Assertions.assertTrue(output.contains("Query:"), output);
		Assertions.assertTrue(output.contains("SELECT * FORM CUSTOMER"), output);
		Assertions.assertTrue(output.contains("^"), output);
		Assertions.assertFalse(output.startsWith("ERROR:"), "should no longer be the old flat one-line rendering:\n" + output);
	}

	@Test
	void aTypedUpdateAgainstAMissingTablePrintsTheStructuredDiagnosticBlockWithoutACaret() throws BroadSQLException {
		CommandDefault cmd = CommandTestSupport.create(CommandDefault.class, db, console);
		cmd.execute("UPDATE NOSUCHTABLE SET NAME = 'X'");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("SQL ERROR"), output);
		Assertions.assertTrue(output.contains("NOSUCHTABLE"), output);
		Assertions.assertFalse(output.contains("^"), output);
	}

	@Test
	void ordinarySuccessfulSqlIsCompletelyUnaffected() throws BroadSQLException {
		CommandDefault cmd = CommandTestSupport.create(CommandDefault.class, db, console);
		cmd.execute("INSERT INTO CUSTOMER (ID, NAME) VALUES (1, 'Alice')");
		Assertions.assertFalse(console.getOutput().contains("SQL ERROR"), console.getOutput());

		cmd.execute("SELECT * FROM CUSTOMER");
		Assertions.assertFalse(console.getOutput().contains("SQL ERROR"), console.getOutput());
	}
}
