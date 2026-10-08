package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.set.CommandSetConnectionPassword;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * {@code SET PASSWORD} is otherwise an interactive, multi-step prompt ({@code console.readLine()}/
 * {@code readPassword()}, which reliably return {@code null} here since {@code System.console()} is
 * {@code null} in this environment - see {@code CLAUDE.md}, "Environment quirks"). Only the two guard
 * clauses that run before any prompt are covered; the interactive flow itself needs a real,
 * AES-encrypted CDF fixture, out of scope for this pass (see {@code docs/TESTS_STRATEGY.md}).
 */
class TestCommandSetConnectionPassword {

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
	void refusesToRunAgainstTheCdfConnection() throws BroadSQLException {
		CommandSetConnectionPassword cmd = CommandTestSupport.create(CommandSetConnectionPassword.class, db, console);
		cmd.setPlatform("$CDF");

		Assertions.assertDoesNotThrow(() -> cmd.execute("SET PASSWORD"));

		Assertions.assertTrue(console.getOutput().contains("You must use command SET MASTER PASSWORD"),
				"expected the CDF refusal, got:\n" + console.getOutput());
	}

	@Test
	void refusesToRunWithUncommittedChangesPending() throws BroadSQLException {
		CommandSetConnectionPassword cmd = CommandTestSupport.create(CommandSetConnectionPassword.class, db, console);
		cmd.setPlatform("WORLD");
		db.setHasUncommitted(true);

		Assertions.assertDoesNotThrow(() -> cmd.execute("SET PASSWORD"));

		Assertions.assertTrue(console.getOutput().contains("Uncommitted transactions pending"),
				"expected the uncommitted-transactions refusal, got:\n" + console.getOutput());
	}
}
