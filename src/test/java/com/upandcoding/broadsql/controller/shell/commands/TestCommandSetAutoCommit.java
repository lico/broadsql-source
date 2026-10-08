package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.set.CommandSetAutoCommit;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandSetAutoCommit {

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
	void turnsAutoCommitOnAndWarnsAboutRollback() throws BroadSQLException {
		CommandSetAutoCommit cmd = CommandTestSupport.create(CommandSetAutoCommit.class, db, console);

		cmd.execute("SET AUTOCOMMIT ON");

		Assertions.assertTrue(db.isAutoCommit());
		Assertions.assertTrue(console.getOutput().contains("Autocommit ON"),
				"expected the autocommit-on warning, got:\n" + console.getOutput());
	}

	@Test
	void turnsAutoCommitOffAndInforms() throws BroadSQLException {
		CommandSetAutoCommit cmd = CommandTestSupport.create(CommandSetAutoCommit.class, db, console);

		cmd.execute("SET AUTOCOMMIT OFF");

		Assertions.assertFalse(db.isAutoCommit());
		Assertions.assertTrue(console.getOutput().contains("Autocommit OFF"),
				"expected the autocommit-off message, got:\n" + console.getOutput());
	}

	@Test
	void rejectsAnInvalidMode() {
		CommandSetAutoCommit cmd = CommandTestSupport.create(CommandSetAutoCommit.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("SET AUTOCOMMIT MAYBE"));

		Assertions.assertTrue(console.getOutput().contains("You must specify a valid autocommit mode"),
				"expected the invalid-mode error, got:\n" + console.getOutput());
	}

	@Test
	void rejectsAMissingMode() {
		CommandSetAutoCommit cmd = CommandTestSupport.create(CommandSetAutoCommit.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("SET AUTOCOMMIT"));

		Assertions.assertTrue(console.getOutput().contains("You must specify a valid autocommit mode"),
				"expected the invalid-mode error, got:\n" + console.getOutput());
	}
}
