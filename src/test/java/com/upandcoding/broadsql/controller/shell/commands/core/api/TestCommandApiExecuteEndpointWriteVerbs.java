package com.upandcoding.broadsql.controller.shell.commands.core.api;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandTestSupport;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.LastApiExecutionResultHolder;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.auth.TestLocalHttpServer;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * SPRINT XT02-8: command-level coverage for write-verb execution that doesn't fit
 * {@link TestCommandApiExecuteEndpointAliasResolution} (alias resolution) or
 * {@link com.upandcoding.broadsql.dao.api.execution.TestApiEndpointExecutor} (wire-level HTTP behavior) -
 * the stale-result lifecycle, secret non-disclosure, and script independence from an active
 * {@code CONNECT API} context, all specifically for a mutating verb.
 */
class TestCommandApiExecuteEndpointWriteVerbs {

	private static final String API_ID = "DESK";

	private TestLocalHttpServer server;
	private ApiDefinitionsVault vault;
	private ApiVersion version;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws IOException, BroadSQLException {
		server = new TestLocalHttpServer();
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment environment = new ApiEnvironment(API_ID, "Test", null, 0);
		vault.setEnvironmentBaseUrl(environment, server.baseUrl());
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() {
		server.close();
		LastApiExecutionResultHolder.set(null);
		ApiSessionContextHolder.clear();
	}

	private CommandApiExecuteEndpoint newCommand() {
		CommandApiExecuteEndpoint command = CommandTestSupport.create(CommandApiExecuteEndpoint.class, console);
		command.setApiDefinitionsVault(vault);
		return command;
	}

	@Test
	void aSuccessfulPostFollowedByAFailedPostClearsTheHeldResult() throws BroadSQLException {
		server.setRoute("/customers", 201, "{\"id\":1}");
		ApiEndpoint createOk = new ApiEndpoint(version.getId(), null, "Create OK", "POST", "${baseUrl}/customers", 0);
		createOk.setBodyMode("json");
		createOk.setBodyContent("{\"name\": \"Alice\"}");
		vault.saveEndpoint(createOk);
		ApiEndpoint createBroken = new ApiEndpoint(version.getId(), null, "Create Broken", "POST", "${baseUrl}/customers", 1);
		createBroken.setBodyMode("json");
		createBroken.setBodyContent("{\"name\": \"{{DOES_NOT_EXIST}}\"}");
		vault.saveEndpoint(createBroken);

		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + createOk.getId() + " Test");
		Assertions.assertNotNull(LastApiExecutionResultHolder.get(), "the successful POST must populate the holder");

		Assertions.assertThrows(BroadSQLException.class,
				() -> newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + createBroken.getId() + " Test"),
				"an unresolved body variable must fail before any network request");

		Assertions.assertNull(LastApiExecutionResultHolder.get(),
				"a failed write attempt must clear the previously-successful result - PULL API RESULT must have nothing stale to export");
	}

	@Test
	void aSecretVariableUsedInTheRequestBodyNeverAppearsInConsoleOutput() throws BroadSQLException {
		server.setRoute("/customers", 201, "{\"id\":1}");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Create Customer", "POST", "${baseUrl}/customers", 0);
		endpoint.setBodyMode("json");
		endpoint.setBodyContent("{\"name\": \"Alice\", \"apiKey\": \"{{SECRET_KEY}}\"}");
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.VARIABLE, "SECRET_KEY", "sk_live_topsecret", true));

		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + endpoint.getId() + " Test");

		Assertions.assertEquals("{\"name\": \"Alice\", \"apiKey\": \"sk_live_topsecret\"}", server.lastRequestTo("/customers").body,
				"the real request sent to the server must still carry the resolved secret");
		Assertions.assertFalse(console.getOutput().contains("sk_live_topsecret"),
				"the resolved secret must never appear in console output - the request body is never echoed: " + console.getOutput());
	}

	@Test
	void executeApiEndpointWorksForAWriteEndpointWithNoActiveApiContextAtAll() throws BroadSQLException {
		server.setRoute("/customers", 201, "{\"id\":1}");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Create Customer", "POST", "${baseUrl}/customers", 0);
		endpoint.setBodyMode("json");
		endpoint.setBodyContent("{\"name\": \"Alice\"}");
		vault.saveEndpoint(endpoint);
		Assertions.assertNull(ApiSessionContextHolder.get(), "precondition: no CONNECT API session is active");

		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + endpoint.getId() + " Test");

		Assertions.assertEquals(1, server.countRequestsTo("/customers"), "the explicit form must never require an active API session context");
		Assertions.assertNull(ApiSessionContextHolder.get(), "the explicit form must never establish or infer an API session context either");
	}

	/**
	 * SPRINT XT02A (URL-Native API Execution) rewrote {@code RUN} to be URL-native only - a bare
	 * endpoint alias (the original form this test exercised, {@code RUN DELETECUSTOMER}) is now
	 * rejected rather than executed (see {@code CommandRun}'s own Javadoc for the breaking-change
	 * rationale). This test's actual purpose - proving {@code RUN} and {@code EXECUTE API ENDPOINT}
	 * reach the exact same DELETE endpoint - still holds, just through {@code RUN}'s new grammar.
	 */
	@Test
	void runAndExecuteApiEndpointResolveADeleteEndpointToTheSameTarget() throws BroadSQLException {
		server.setRoute("/customers/1", 204, "");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Delete Customer", "DELETE", "${baseUrl}/customers/1", 0);
		endpoint.setAlias("DELETECUSTOMER");
		vault.saveEndpoint(endpoint);
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "Test"));

		CommandRun runCmd = CommandTestSupport.create(CommandRun.class, console);
		runCmd.setApiDefinitionsVault(vault);
		runCmd.execute("RUN DELETE /customers/1");

		Assertions.assertEquals(1, server.countRequestsTo("/customers/1"));
		Assertions.assertEquals("DELETE", server.lastRequestTo("/customers/1").method);
	}
}
