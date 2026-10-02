package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * SPRINT XT02B, section 15 - {@code CommandInterpreter.executeMultiStatementLine(String)}, the engine
 * behind a single interactive line containing more than one {@code ;}-terminated statement
 * (e.g. {@code SELECT 1; SELECT 2;}). Driven directly (package-private, same seam as
 * {@code handleSlashRerun}/{@code executeCommand}) rather than through the blocking {@link
 * CommandInterpreter#run()} console-read loop.
 */
class TestCommandInterpreterMultiStatementLine {

	private DatabaseConnection db;
	private CapturingShellConsole console;
	private ConsoleSettings settings;
	private CommandInterpreter interpreter;

	@BeforeEach
	void setUp() throws BroadSQLException {
		settings = TestDatabaseConnections.defaultConsoleSettings();
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE T (ID INT PRIMARY KEY)");
		console = new CapturingShellConsole();
		interpreter = CommandTestSupport.createCommandInterpreter(settings, console, db);
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
		LastQueryResultHolder.set(null);
	}

	@Test
	void executesBothStatementsAndShowsBothResultSets() throws BroadSQLException {
		db.executeUpdateQuery("INSERT INTO T VALUES (1)");
		db.executeUpdateQuery("INSERT INTO T VALUES (2)");

		interpreter.executeMultiStatementLine("SELECT ID FROM T WHERE ID=1 ; SELECT ID FROM T WHERE ID=2 ");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("1"), output);
		Assertions.assertTrue(output.contains("2"), output);
	}

	@Test
	void stopsAtTheFirstFailingStatementAndDoesNotRunTheRest() throws BroadSQLException {
		// Statement 2 is a primary-key violation (real, guaranteed-to-throw SQL error) against
		// statement 1's own row - statement 3 must never run.
		interpreter.executeMultiStatementLine("INSERT INTO T VALUES (1) ; INSERT INTO T VALUES (1) ; INSERT INTO T VALUES (2) ");

		Assertions.assertEquals(1, db.getNumberOfRecords("T"), "the third statement must never run once the second one failed");
		Assertions.assertTrue(console.getOutput().toUpperCase().contains("ERROR"), console.getOutput());
	}

	@Test
	void aSuccessfulSequenceRunsEveryStatement() throws BroadSQLException {
		interpreter.executeMultiStatementLine("INSERT INTO T VALUES (1) ; INSERT INTO T VALUES (2) ");

		Assertions.assertEquals(2, db.getNumberOfRecords("T"));
	}

	@Test
	void aSingleStatementBehavesExactlyAsBefore() throws BroadSQLException {
		interpreter.executeMultiStatementLine("INSERT INTO T VALUES (1) ");

		Assertions.assertEquals(1, db.getNumberOfRecords("T"));
	}

	@Test
	void aStatementThatReportsAnErrorWithoutThrowingStillStopsTheSequence() throws BroadSQLException {
		// CommandDefault (the raw-SQL fallback) catches its own SQLException/BroadSQLException and
		// reports it via console.error(...) without ever rethrowing - "BAD SQL HERE" reproduces the
		// sprint's own acceptance example (SELECT 1; BAD SQL; SELECT 2;) end-to-end.
		interpreter.executeMultiStatementLine("INSERT INTO T VALUES (1) ; BAD SQL HERE ; INSERT INTO T VALUES (2) ");

		Assertions.assertEquals(1, db.getNumberOfRecords("T"), "the third statement must never run once the second one reported an error");
		Assertions.assertTrue(console.getOutput().toUpperCase().contains("ERROR"), console.getOutput());
	}

	@Test
	void aSemicolonInsideAQuotedStringDoesNotSplitTheStatement() throws BroadSQLException {
		db.executeUpdateQuery("CREATE TABLE STR_T (ID INT PRIMARY KEY, VAL VARCHAR(50))");

		interpreter.executeMultiStatementLine("INSERT INTO STR_T VALUES (1, 'a;b') ");

		Assertions.assertEquals(1, db.getNumberOfRecords("STR_T"));
	}
}
