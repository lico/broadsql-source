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
import com.upandcoding.broadsql.controller.shell.commands.extensions.CommandDirectLoadBatch;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandDirectLoadBatch {

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
	void loadsRowsFromACsvFileAsOneBatch(@TempDir Path dir) throws BroadSQLException, IOException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME\n1;Alice\n2;Bob\n");
		CommandDirectLoadBatch cmd = CommandTestSupport.create(CommandDirectLoadBatch.class, db, console);

		cmd.execute("BATCHLOAD CREATE CUSTOMER " + csv);

		db.executeSelectQuery("SELECT COUNT(*) FROM CUSTOMER");
		Assertions.assertTrue(console.getOutput().contains("2"), "expected 2 rows loaded, got:\n" + console.getOutput());
	}

	/**
	 * Unlike {@code LOAD}, {@code BATCHLOAD} does not check the table exists before starting - the
	 * failure surfaces from inside {@code directLoadByBatch()} itself, caught and printed via
	 * {@code console.error()}, so (unlike the missing-argument case, which only logs via SLF4J and
	 * never reaches the console) this one IS user-visible.
	 */
	@Test
	void reportsAnErrorForATableThatDoesNotExist(@TempDir Path dir) throws IOException {
		Path csv = dir.resolve("nope.csv");
		Files.writeString(csv, "ID;NAME\n1;Alice\n");
		CommandDirectLoadBatch cmd = CommandTestSupport.create(CommandDirectLoadBatch.class, db, console);

		Assertions.assertDoesNotThrow(() -> cmd.execute("BATCHLOAD CREATE DOESNOTEXIST " + csv));

		Assertions.assertTrue(console.getOutput().contains("ERROR"), "expected an error to surface, got:\n" + console.getOutput());
	}
}
