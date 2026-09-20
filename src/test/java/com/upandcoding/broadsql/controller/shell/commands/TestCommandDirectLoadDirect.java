package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.extensions.CommandDirectLoadDirect;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandDirectLoadDirect {

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
	void loadsRowsFromACsvFileIntoAnExistingTable(@TempDir Path dir) throws BroadSQLException, IOException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME\n1;Alice\n2;Bob\n");
		CommandDirectLoadDirect cmd = CommandTestSupport.create(CommandDirectLoadDirect.class, db, console);

		cmd.execute("LOAD CREATE CUSTOMER " + csv);

		db.executeSelectQuery("SELECT COUNT(*) FROM CUSTOMER");
		Assertions.assertTrue(console.getOutput().contains("2"), "expected 2 rows loaded, got:\n" + console.getOutput());
	}

	/**
	 * Every failure branch in {@code CommandDirectLoadDirect.execute()} (missing arguments, unknown
	 * table, missing file) reports through the class's own SLF4J {@code log.error(...)}, never through
	 * {@code console} - so nothing reaches the user-visible output. This only confirms the command
	 * degrades safely (no exception) with too few arguments, not that it reports anything.
	 */
	@Test
	void doesNotThrowWithMissingArguments() {
		CommandDirectLoadDirect cmd = CommandTestSupport.create(CommandDirectLoadDirect.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("LOAD"));
	}
}
