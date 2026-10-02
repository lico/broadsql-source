package com.upandcoding.broadsql.controller.shell.commands;

import java.awt.GraphicsEnvironment;
import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandRun;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandCopyResult;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.LastApiExecutionResultHolder;
import com.upandcoding.broadsql.dao.LastCopyableResult;
import com.upandcoding.broadsql.dao.LastCopyableResultHolder;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.ApiSessionVariablesHolder;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.auth.TestLocalHttpServer;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;
import com.upandcoding.broadsql.controller.shell.commands.listsource.ClipboardAccess;

/**
 * The {@code COPY RESULT} invariant, driven through the real execution path ({@code
 * CommandInterpreter.executeMultiStatementLine} dispatching real {@code CommandDefault}/{@code CommandRun}
 * instances against a real in-memory H2 and a local HTTP server) rather than by writing to {@link
 * LastCopyableResultHolder} directly: <b>the copyable result is the most recent SUCCESSFUL SQL or API
 * execution, and a failed execution never replaces it.</b>
 *
 * <p>Definitions used here: a SQL statement is failed when it reports an error (it usually does so through
 * {@code console.error} without throwing); a successful query returning zero rows is a success. An API
 * execution is failed when no response was produced (no API session, bad usage, transport error); any HTTP
 * response, including an empty body, is a result.
 */
class TestCopyResultLifecycle {

	private static final String API_ID = "DESK";

	private DatabaseConnection db;
	private CapturingShellConsole console;
	private CommandInterpreter interpreter;
	private TestLocalHttpServer server;
	private ApiDefinitionsVault vault;

	@BeforeEach
	void setUp() throws BroadSQLException, IOException {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE T (ID INT PRIMARY KEY, NAME VARCHAR(20))",
				"INSERT INTO T VALUES (1, 'Alice')");
		console = new CapturingShellConsole();
		interpreter = CommandTestSupport.createCommandInterpreter(settings, console, db);

		server = new TestLocalHttpServer();
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveEndpoint(new ApiEndpoint(version.getId(), null, "GET customer", "GET", "${baseUrl}/api/customer/${id}", 0));
		ApiEnvironment environment = new ApiEnvironment(API_ID, "Test", null, 0);
		vault.setEnvironmentBaseUrl(environment, server.baseUrl());
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "Test"));

		CommandRun run = CommandTestSupport.create(CommandRun.class, db, console, settings);
		run.setApiDefinitionsVault(vault);
		for (String keyword : run.getKeywords()) {
			interpreter.getCommands().put(keyword, run);
		}
		LastCopyableResultHolder.clear();
		LastApiExecutionResultHolder.clear();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		server.close();
		TestDatabaseConnections.close(db);
		LastCopyableResultHolder.clear();
		LastApiExecutionResultHolder.clear();
		LastQueryResultHolder.set(null);
		ApiSessionContextHolder.clear();
		ApiSessionVariablesHolder.clearAll();
	}

	private void sql(String statement) {
		interpreter.executeMultiStatementLine(statement);
	}

	private void run(int status, String body, String id) {
		server.setRoute("/api/customer/" + id, status, body);
		interpreter.executeMultiStatementLine("RUN /api/customer/" + id);
	}

	private void assertSql(String expectedQuery) {
		LastCopyableResult latest = LastCopyableResultHolder.get();
		Assertions.assertNotNull(latest, "a copyable result was expected");
		Assertions.assertEquals(LastCopyableResult.Source.SQL, latest.getSource());
		Assertions.assertEquals(expectedQuery, latest.getSqlQuery());
	}

	private void assertApi(String expectedBodyPart) {
		LastCopyableResult latest = LastCopyableResultHolder.get();
		Assertions.assertNotNull(latest, "a copyable result was expected");
		Assertions.assertEquals(LastCopyableResult.Source.API, latest.getSource());
		Assertions.assertTrue(latest.getApiResult().getRawJson().contains(expectedBodyPart), latest.getApiResult().getRawJson());
	}

	@Test
	void nothingIsCopyableBeforeAnySuccessfulExecution() {
		Assertions.assertNull(LastCopyableResultHolder.get());
		sql("SELECT * FROM NO_SUCH_TABLE");
		Assertions.assertNull(LastCopyableResultHolder.get(), "a failed first statement must not create a copyable result");
		interpreter.executeMultiStatementLine("RUN nonsense");
		Assertions.assertNull(LastCopyableResultHolder.get());
	}

	@Test
	void sqlSuccessThenSqlSuccessKeepsTheLatest() {
		sql("SELECT * FROM T");
		sql("SELECT ID FROM T");
		assertSql("SELECT ID FROM T");
	}

	@Test
	void sqlSuccessThenSqlFailureKeepsTheSuccessfulOne() {
		sql("SELECT * FROM T");
		sql("SELECT * FROM NO_SUCH_TABLE");
		Assertions.assertTrue(console.wasErrorReported(), "the second statement must really have failed");
		assertSql("SELECT * FROM T");
	}

	@Test
	void sqlSuccessThenFailureInTheMiddleOfAMultiStatementLineKeepsTheLastSuccess() {
		sql("SELECT ID FROM T ; SELECT * FROM NO_SUCH_TABLE ; SELECT NAME FROM T ");
		assertSql("SELECT ID FROM T");
	}

	@Test
	void apiSuccessThenSqlFailureKeepsTheApiResult() {
		run(200, "[{\"ID\":1,\"NAME\":\"Alice\"}]", "1");
		assertApi("Alice");
		sql("SELECT * FROM NO_SUCH_TABLE");
		assertApi("Alice");
	}

	@Test
	void sqlSuccessThenApiSuccessMakesTheApiResultCopyable() {
		sql("SELECT * FROM T");
		run(200, "[{\"ID\":2,\"NAME\":\"Bob\"}]", "2");
		assertApi("Bob");
	}

	@Test
	void apiSuccessThenSqlSuccessMakesTheSqlResultCopyable() {
		run(200, "[{\"ID\":2,\"NAME\":\"Bob\"}]", "2");
		sql("SELECT * FROM T");
		assertSql("SELECT * FROM T");
	}

	@Test
	void apiSuccessThenApiSuccessKeepsTheLatest() {
		run(200, "[{\"ID\":2,\"NAME\":\"Bob\"}]", "2");
		run(200, "[{\"ID\":3,\"NAME\":\"Carol\"}]", "3");
		assertApi("Carol");
	}

	@Test
	void sqlSuccessThenApiFailureKeepsTheSqlResult() {
		sql("SELECT * FROM T");
		ApiSessionContextHolder.clear(); // RUN now produces no response at all: "No API is connected"
		interpreter.executeMultiStatementLine("RUN /api/customer/1");
		Assertions.assertTrue(console.getOutput().contains("No API is connected"), console.getOutput());
		assertSql("SELECT * FROM T");
	}

	@Test
	void apiSuccessThenApiFailureKeepsTheFirstApiResult() {
		run(200, "[{\"ID\":2,\"NAME\":\"Bob\"}]", "2");
		interpreter.executeMultiStatementLine("RUN nonsense"); // not a URL: refused before any request
		Assertions.assertTrue(console.wasErrorReported());
		assertApi("Bob");
	}

	@Test
	void aSuccessfulQueryReturningZeroRowsIsAResultAndReplacesThePreviousOne() {
		run(200, "[{\"ID\":2,\"NAME\":\"Bob\"}]", "2");
		sql("SELECT * FROM T WHERE 1 = 0");
		assertSql("SELECT * FROM T WHERE 1 = 0");
	}

	@Test
	void aSuccessfulApiRequestWithAnEmptyBodyIsAResultAndReplacesThePreviousOne() {
		sql("SELECT * FROM T");
		run(204, "", "9");
		LastCopyableResult latest = LastCopyableResultHolder.get();
		Assertions.assertEquals(LastCopyableResult.Source.API, latest.getSource());
		Assertions.assertEquals(204, latest.getApiResult().getHttpStatus());
	}

	@Test
	void copyResultReallyCopiesTheLastSuccessfulResultAfterAFailure() throws Exception {
		Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no system clipboard available in a headless environment");
		try {
			ClipboardAccess.writeText("BROADSQL_TEST_CLIPBOARD_PROBE");
		} catch (BroadSQLException e) {
			Assumptions.abort("system clipboard not currently usable in this environment: " + e.getMessage());
		}
		sql("SELECT * FROM T");
		sql("SELECT * FROM NO_SUCH_TABLE");

		CommandCopyResult copy = CommandTestSupport.create(CommandCopyResult.class, db, new CapturingShellConsole());
		copy.execute("COPY RESULT");

		Assertions.assertTrue(ClipboardAccess.readText().contains("Alice"), ClipboardAccess.readText());
	}
}
