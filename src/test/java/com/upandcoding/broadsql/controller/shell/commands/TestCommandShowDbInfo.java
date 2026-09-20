package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowDbInfo;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandShowDbInfo {

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
	void displaysTheCurrentConnectionsDatabaseInfo() throws BroadSQLException {
		CommandShowDbInfo cmd = CommandTestSupport.create(CommandShowDbInfo.class, db, console);
		cmd.setPlatform(db.getPlatform().getId());

		cmd.execute("SHOW DBINFOS");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Connected to"), "expected the connection summary, got:\n" + output);
		Assertions.assertTrue(output.contains("H2"), "expected the database product name, got:\n" + output);
	}
}
