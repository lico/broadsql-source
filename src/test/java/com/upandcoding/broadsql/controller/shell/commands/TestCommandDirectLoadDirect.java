package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.extensions.CommandDirectLoadBatch;
import com.upandcoding.broadsql.controller.shell.commands.extensions.CommandDirectLoadDirect;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * {@code LOAD}'s plain interactive confirmation ({@code CONFIRM} mode against a ready plan) asks via
 * {@code console.inputField}, which throws {@code NullPointerException} in this environment (no real
 * console - see {@code TestCommandManageConnectionDelete}) - covered here only via {@code PREVIEW}/
 * {@code EXECUTE}, which never prompt, plus the preflight-failure short-circuit (also prompt-free,
 * since {@code CONFIRM} mode checks {@code plan.isReadyToExecute()} before ever asking).
 */
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

	private int countCustomerRows() throws SQLException {
		try (Statement statement = db.getDirectConnection().createStatement();
				ResultSet rs = statement.executeQuery("SELECT COUNT(*) AS N FROM CUSTOMER")) {
			rs.next();
			return rs.getInt("N");
		}
	}

	@Test
	void previewValidatesAndWritesNothing(@TempDir Path dir) throws BroadSQLException, IOException, SQLException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME\n1;Alice\n");
		CommandDirectLoadDirect cmd = CommandTestSupport.create(CommandDirectLoadDirect.class, db, console);

		cmd.execute("LOAD CUSTOMER " + csv + " PREVIEW");

		Assertions.assertEquals(0, countCustomerRows());
		Assertions.assertTrue(console.getOutput().contains("LOAD PREVIEW"), console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("No changes have been made"), console.getOutput());
	}

	@Test
	void executeInsertsWithoutAskingForConfirmation(@TempDir Path dir) throws BroadSQLException, IOException, SQLException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME\n1;Alice\n2;Bob\n");
		CommandDirectLoadDirect cmd = CommandTestSupport.create(CommandDirectLoadDirect.class, db, console);

		cmd.execute("LOAD CUSTOMER " + csv + " EXECUTE");

		Assertions.assertEquals(2, countCustomerRows());
	}

	@Test
	void theLegacyCreateFormStillWorks(@TempDir Path dir) throws BroadSQLException, IOException, SQLException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME\n1;Alice\n");
		CommandDirectLoadDirect cmd = CommandTestSupport.create(CommandDirectLoadDirect.class, db, console);

		cmd.execute("LOAD CREATE CUSTOMER " + csv + " EXECUTE");

		Assertions.assertEquals(1, countCustomerRows());
	}

	/**
	 * Behavior change from the pre-SPRINT-0912B command: missing arguments used to log via SLF4J only
	 * (nothing reached the console, no exception) - now they throw a clear {@link BroadSQLException},
	 * consistent with the sprint's "fail clearly" principle throughout.
	 */
	@Test
	void missingArgumentsThrowAClearException() {
		CommandDirectLoadDirect cmd = CommandTestSupport.create(CommandDirectLoadDirect.class, db, console);

		Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("LOAD"));
	}

	@Test
	void updateModeIsRejectedWithNoWrites(@TempDir Path dir) throws IOException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME\n1;Alice\n");
		CommandDirectLoadDirect cmd = CommandTestSupport.create(CommandDirectLoadDirect.class, db, console);

		Assertions.assertThrows(BroadSQLException.class, () -> cmd.execute("LOAD UPDATE CUSTOMER " + csv));
	}

	@Test
	void anUnknownSourceColumnRefusesTheWholeLoadWithZeroWrites(@TempDir Path dir) throws BroadSQLException, IOException, SQLException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME;BOGUS\n1;Alice;x\n");
		CommandDirectLoadDirect cmd = CommandTestSupport.create(CommandDirectLoadDirect.class, db, console);

		cmd.execute("LOAD CUSTOMER " + csv);

		Assertions.assertEquals(0, countCustomerRows());
		Assertions.assertTrue(console.getOutput().contains("BOGUS"), console.getOutput());
	}

	@Test
	void aPlainLoadInsideAScriptRefusesRatherThanBlockingOnAPrompt(@TempDir Path dir) throws BroadSQLException, IOException, SQLException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME\n1;Alice\n");
		CommandDirectLoadDirect cmd = CommandTestSupport.create(CommandDirectLoadDirect.class, db, console);
		CommandInterpreter interpreter = CommandTestSupport.createCommandInterpreter(TestDatabaseConnections.defaultConsoleSettings(), console, db);
		cmd.setConsoleCommandInterpreter(interpreter);
		interpreter.getScriptContext().push(java.nio.file.Path.of("script.bsql").toAbsolutePath());

		try {
			cmd.execute("LOAD CUSTOMER " + csv);
		} finally {
			interpreter.getScriptContext().pop(interpreter.getScriptContext().current());
		}

		Assertions.assertEquals(0, countCustomerRows());
		Assertions.assertTrue(console.getOutput().contains("EXECUTE"), console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("No changes have been made"), console.getOutput());
	}

	@Test
	void executeInsideAScriptWorksWithoutAnyPrompt(@TempDir Path dir) throws BroadSQLException, IOException, SQLException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME\n1;Alice\n");
		CommandDirectLoadDirect cmd = CommandTestSupport.create(CommandDirectLoadDirect.class, db, console);
		CommandInterpreter interpreter = CommandTestSupport.createCommandInterpreter(TestDatabaseConnections.defaultConsoleSettings(), console, db);
		cmd.setConsoleCommandInterpreter(interpreter);
		interpreter.getScriptContext().push(java.nio.file.Path.of("script.bsql").toAbsolutePath());

		try {
			cmd.execute("LOAD CUSTOMER " + csv + " EXECUTE");
		} finally {
			interpreter.getScriptContext().pop(interpreter.getScriptContext().current());
		}

		Assertions.assertEquals(1, countCustomerRows());
	}

	@Test
	void batchloadRoutesToTheSameEngineAndPrintsADeprecationNotice(@TempDir Path dir) throws BroadSQLException, IOException, SQLException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME\n1;Alice\n");
		CommandDirectLoadBatch cmd = CommandTestSupport.create(CommandDirectLoadBatch.class, db, console);

		cmd.execute("BATCHLOAD CUSTOMER " + csv + " EXECUTE");

		Assertions.assertEquals(1, countCustomerRows());
		Assertions.assertTrue(console.getOutput().contains("BATCHLOAD is deprecated"), console.getOutput());
	}
}
