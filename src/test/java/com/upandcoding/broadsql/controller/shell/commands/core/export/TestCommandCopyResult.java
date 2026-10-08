package com.upandcoding.broadsql.controller.shell.commands.core.export;

import java.awt.GraphicsEnvironment;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandTestSupport;
import com.upandcoding.broadsql.controller.shell.commands.listsource.ClipboardAccess;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.LastApiExecutionResult;
import com.upandcoding.broadsql.dao.LastCopyableResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultTable;

/**
 * SPRINT XT02B acceptance correction, item 6: {@code COPY RESULT} must consume the most recently
 * produced copyable result, SQL or API, regardless of execution order - not a hard-coded SQL-first/
 * API-fallback priority (the earlier, rejected behavior; see git history for the previous version of
 * this test class). {@link LastCopyableResultHolder} is the new mechanism under test here, driven
 * directly (mirroring the two production call sites: {@code CommandInterpreter}'s SQL-statement
 * completion, {@code CommandRun}/{@code CommandApiExecuteEndpoint}'s successful-execution capture) since
 * {@link CommandCopyResult} is exercised in isolation via {@link CommandTestSupport}, not through a real
 * {@code CommandInterpreter}.
 */
class TestCommandCopyResult {

	@AfterEach
	void tearDown() {
		LastCopyableResultHolder.clear();
	}

	@Test
	void printsNoQueryInMemoryWhenNothingHasBeenRunYet() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandCopyResult cmd = CommandTestSupport.create(CommandCopyResult.class, null, console);

		cmd.execute("COPY RESULT");

		Assertions.assertTrue(console.getOutput().contains("No query in memory"), "got: " + console.getOutput());
	}

	private LastApiExecutionResult sampleApiResult() {
		ApiResultTable table = new ApiResultTable(List.of("ID", "NAME"),
				List.of(Map.of("ID", "1", "NAME", "Alice"), Map.of("ID", "2", "NAME", "Bob")), true, null);
		return new LastApiExecutionResult(table, "[{\"ID\":1,\"NAME\":\"Alice\"},{\"ID\":2,\"NAME\":\"Bob\"}]",
				"DESK", "GET /api/customer", "Test", Instant.now(), 200);
	}

	@Test
	void copiesTheLastQuerysResultToTheClipboardAsTsv() throws Exception {
		Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no system clipboard available in a headless environment");
		try {
			ClipboardAccess.writeText("BROADSQL_TEST_CLIPBOARD_PROBE");
		} catch (BroadSQLException e) {
			// See TestClipboardListSource.requiresAUsableSystemClipboard: the OS clipboard can be locked
			// by something external to this session even in a non-headless environment.
			Assumptions.abort("system clipboard not currently usable in this environment: " + e.getMessage());
		}

		DatabaseConnection db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice'), (2, 'Bob')");
		try {
			CapturingShellConsole console = new CapturingShellConsole();
			CommandCopyResult cmd = CommandTestSupport.create(CommandCopyResult.class, db, console);
			LastCopyableResultHolder.recordSql("SELECT * FROM CUSTOMER ORDER BY ID");

			cmd.execute("COPY RESULT");

			String clipboardText = ClipboardAccess.readText();
			String[] lines = clipboardText.split("\r\n");
			Assertions.assertEquals("ID\tNAME", lines[0]);
			Assertions.assertEquals("1\tAlice", lines[1]);
			Assertions.assertEquals("2\tBob", lines[2]);
			Assertions.assertTrue(console.getOutput().contains("2 row(s) copied"), "got: " + console.getOutput());
		} finally {
			TestDatabaseConnections.close(db);
		}
	}

	@Test
	void theStoredQuerysVariablesAreBoundWithTheirCurrentValues() throws Exception {
		// SPRINT 0110A, spec 18.6
		Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no system clipboard available in a headless environment");
		try {
			ClipboardAccess.writeText("BROADSQL_TEST_CLIPBOARD_PROBE");
		} catch (BroadSQLException e) {
			Assumptions.abort("system clipboard not currently usable in this environment: " + e.getMessage());
		}
		DatabaseConnection db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"INSERT INTO CUSTOMER VALUES (1, 'Alice'), (2, 'Bob')");
		try {
			CapturingShellConsole console = new CapturingShellConsole();
			CommandCopyResult cmd = CommandTestSupport.create(CommandCopyResult.class, db, console);
			com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter interpreter = CommandTestSupport
					.createCommandInterpreter(TestDatabaseConnections.defaultConsoleSettings(), console, db);
			interpreter.getScriptVariables().assign("who", com.upandcoding.broadsql.controller.shell.scripts.ScriptValue.ofString("Bob"));
			cmd.setConsoleCommandInterpreter(interpreter);
			LastCopyableResultHolder.recordSql("SELECT NAME FROM CUSTOMER WHERE NAME = ${who}");

			cmd.execute("COPY RESULT");

			Assertions.assertTrue(ClipboardAccess.readText().contains("Bob"), console.getOutput());
			Assertions.assertTrue(console.getOutput().contains("1 row(s) copied"), console.getOutput());
		} finally {
			TestDatabaseConnections.close(db);
		}
	}

	@Test
	void copiesTheApiResultWhenItIsTheOnlyResultEverProduced() throws Exception {
		Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no system clipboard available in a headless environment");
		try {
			ClipboardAccess.writeText("BROADSQL_TEST_CLIPBOARD_PROBE");
		} catch (BroadSQLException e) {
			Assumptions.abort("system clipboard not currently usable in this environment: " + e.getMessage());
		}

		LastCopyableResultHolder.recordApi(sampleApiResult());
		CapturingShellConsole console = new CapturingShellConsole();
		CommandCopyResult cmd = CommandTestSupport.create(CommandCopyResult.class, null, console);

		cmd.execute("COPY RESULT");

		String clipboardText = ClipboardAccess.readText();
		String[] lines = clipboardText.split("\r\n");
		Assertions.assertEquals("ID\tNAME", lines[0]);
		Assertions.assertTrue(clipboardText.contains("Alice") && clipboardText.contains("Bob"), clipboardText);
		Assertions.assertTrue(console.getOutput().contains("2 row(s) copied"), "got: " + console.getOutput());
	}

	@Test
	void copiesTheSqlResultWhenItIsTheOnlyResultEverProduced() throws Exception {
		Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no system clipboard available in a headless environment");
		try {
			ClipboardAccess.writeText("BROADSQL_TEST_CLIPBOARD_PROBE");
		} catch (BroadSQLException e) {
			Assumptions.abort("system clipboard not currently usable in this environment: " + e.getMessage());
		}

		DatabaseConnection db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"INSERT INTO CUSTOMER VALUES (1, 'Charlie')");
		try {
			LastCopyableResultHolder.recordSql("SELECT * FROM CUSTOMER");
			CapturingShellConsole console = new CapturingShellConsole();
			CommandCopyResult cmd = CommandTestSupport.create(CommandCopyResult.class, db, console);

			cmd.execute("COPY RESULT");

			String clipboardText = ClipboardAccess.readText();
			Assertions.assertTrue(clipboardText.contains("Charlie"), "got: " + clipboardText);
		} finally {
			TestDatabaseConnections.close(db);
		}
	}

	@Test
	void aMoreRecentApiResultIsCopiedOverAnEarlierSqlResult() throws Exception {
		Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no system clipboard available in a headless environment");
		try {
			ClipboardAccess.writeText("BROADSQL_TEST_CLIPBOARD_PROBE");
		} catch (BroadSQLException e) {
			Assumptions.abort("system clipboard not currently usable in this environment: " + e.getMessage());
		}

		DatabaseConnection db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"INSERT INTO CUSTOMER VALUES (1, 'Charlie')");
		try {
			// SELECT ...; RUN /api/...; COPY RESULT; must copy the API result.
			LastCopyableResultHolder.recordSql("SELECT * FROM CUSTOMER");
			LastCopyableResultHolder.recordApi(sampleApiResult());
			CapturingShellConsole console = new CapturingShellConsole();
			CommandCopyResult cmd = CommandTestSupport.create(CommandCopyResult.class, db, console);

			cmd.execute("COPY RESULT");

			String clipboardText = ClipboardAccess.readText();
			Assertions.assertTrue(clipboardText.contains("Alice"), "expected the more recent API result: " + clipboardText);
			Assertions.assertFalse(clipboardText.contains("Charlie"), "did not expect the earlier SQL result: " + clipboardText);
		} finally {
			TestDatabaseConnections.close(db);
		}
	}

	@Test
	void aMoreRecentSqlResultIsCopiedOverAnEarlierApiResult() throws Exception {
		Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no system clipboard available in a headless environment");
		try {
			ClipboardAccess.writeText("BROADSQL_TEST_CLIPBOARD_PROBE");
		} catch (BroadSQLException e) {
			Assumptions.abort("system clipboard not currently usable in this environment: " + e.getMessage());
		}

		DatabaseConnection db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))",
				"INSERT INTO CUSTOMER VALUES (1, 'Charlie')");
		try {
			// RUN /api/...; SELECT ...; COPY RESULT; must copy the SQL result.
			LastCopyableResultHolder.recordApi(sampleApiResult());
			LastCopyableResultHolder.recordSql("SELECT * FROM CUSTOMER");
			CapturingShellConsole console = new CapturingShellConsole();
			CommandCopyResult cmd = CommandTestSupport.create(CommandCopyResult.class, db, console);

			cmd.execute("COPY RESULT");

			String clipboardText = ClipboardAccess.readText();
			Assertions.assertTrue(clipboardText.contains("Charlie"), "expected the more recent SQL result: " + clipboardText);
			Assertions.assertFalse(clipboardText.contains("Alice"), "did not expect the earlier API result: " + clipboardText);
		} finally {
			TestDatabaseConnections.close(db);
		}
	}
}
