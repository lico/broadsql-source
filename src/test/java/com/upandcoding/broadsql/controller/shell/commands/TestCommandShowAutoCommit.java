package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowAutoCommit;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandShowAutoCommit {

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
	void warnsWhenAutoCommitIsOn() throws BroadSQLException {
		db.setAutoCommit(true);
		CommandShowAutoCommit cmd = CommandTestSupport.create(CommandShowAutoCommit.class, db, console);

		cmd.execute("SHOW AUTOCOMMIT");

		Assertions.assertTrue(console.getOutput().contains("Autocommit ON"),
				"expected the autocommit-on warning, got:\n" + console.getOutput());
	}

	@Test
	void informsWhenAutoCommitIsOff() throws BroadSQLException {
		db.setAutoCommit(false);
		CommandShowAutoCommit cmd = CommandTestSupport.create(CommandShowAutoCommit.class, db, console);

		cmd.execute("SHOW AUTOCOMMIT");

		Assertions.assertTrue(console.getOutput().contains("Autocommit OFF"),
				"expected the autocommit-off message, got:\n" + console.getOutput());
	}
}
