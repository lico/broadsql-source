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
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.auth.TestLocalHttpServer;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiAuthType;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * Regression coverage for two of the ten SPRINT XT02 independent-audit findings that both live in
 * {@link CommandApiExecuteEndpoint}:
 * <ul>
 * <li>Finding 1 (HIGH) - a stale, previously-successful {@link com.upandcoding.broadsql.dao.LastApiExecutionResult}
 * must never survive a later failed execution attempt (policy refusal, unresolved variable, unsupported
 * authentication) - {@code PULL API RESULT} must have nothing to export afterward.</li>
 * <li>Finding 4 (HIGH) - an inactive API, endpoint, or environment must refuse to execute, reported as
 * "inactive" rather than "not found" where practical, without requiring the vault's own
 * {@code getEndpointsForVersion}/{@code getEnvironmentsForApi} (which the CONFIG API GUI/SHOW commands
 * still need to see every status through) to be narrowed.</li>
 * </ul>
 */
class TestCommandApiExecuteEndpointVerificationFixes {

	private static final String API_ID = "VERIFY01";

	private TestLocalHttpServer server;
	private ApiDefinitionsVault vault;
	private ApiVersion version;
	private ApiEnvironment environment;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws IOException, BroadSQLException {
		server = new TestLocalHttpServer();
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		environment = new ApiEnvironment(API_ID, "Test", null, 0);
		vault.setEnvironmentBaseUrl(environment, server.baseUrl());
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() {
		server.close();
		LastApiExecutionResultHolder.set(null);
	}

	private CommandApiExecuteEndpoint newCommand() {
		CommandApiExecuteEndpoint command = CommandTestSupport.create(CommandApiExecuteEndpoint.class, console);
		command.setApiDefinitionsVault(vault);
		return command;
	}

	// ------------------------------------------------------------------------------------------
	// Finding 1 - stale result lifecycle
	// ------------------------------------------------------------------------------------------

	// SPRINT XT02-8 made POST executable - OPTIONS is now the still-refused verb this test uses to prove
	// a policy-refused execution clears a previously-held result.
	@Test
	void aSuccessfulExecutionFollowedByAPolicyRefusedExecutionClearsTheHeldResult() throws BroadSQLException {
		server.setRoute("/ok", 200, "[{\"id\":1}]");
		ApiEndpoint getEndpoint = new ApiEndpoint(version.getId(), null, "Get ok", "GET", "${baseUrl}/ok", 0);
		vault.saveEndpoint(getEndpoint);
		ApiEndpoint optionsEndpoint = new ApiEndpoint(version.getId(), null, "Unsupported thing", "OPTIONS", "${baseUrl}/things", 1);
		vault.saveEndpoint(optionsEndpoint);

		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + getEndpoint.getId() + " Test");
		Assertions.assertNotNull(LastApiExecutionResultHolder.get(), "the successful GET must populate the holder");

		Assertions.assertThrows(BroadSQLException.class,
				() -> newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + optionsEndpoint.getId() + " Test"),
				"an OPTIONS endpoint must be refused by the execution policy");

		Assertions.assertNull(LastApiExecutionResultHolder.get(),
				"a failed execution attempt must clear the previously-successful result - PULL API RESULT must have nothing stale to export");
	}

	@Test
	void aSuccessfulExecutionFollowedByAnUnresolvedVariableFailureClearsTheHeldResult() throws BroadSQLException {
		server.setRoute("/ok", 200, "[{\"id\":1}]");
		ApiEndpoint getEndpoint = new ApiEndpoint(version.getId(), null, "Get ok", "GET", "${baseUrl}/ok", 0);
		vault.saveEndpoint(getEndpoint);
		ApiEndpoint brokenEndpoint = new ApiEndpoint(version.getId(), null, "Broken", "GET", "${baseUrl}/${doesNotExist}", 1);
		vault.saveEndpoint(brokenEndpoint);

		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + getEndpoint.getId() + " Test");
		Assertions.assertNotNull(LastApiExecutionResultHolder.get());

		Assertions.assertThrows(BroadSQLException.class,
				() -> newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + brokenEndpoint.getId() + " Test"),
				"an unresolved ${variable} must fail before any network request");

		Assertions.assertNull(LastApiExecutionResultHolder.get(), "an unresolved-variable failure must clear the previously-successful result");
	}

	@Test
	void aSuccessfulExecutionFollowedByAnUnsupportedAuthenticationFailureClearsTheHeldResult() throws BroadSQLException {
		server.setRoute("/ok", 200, "[{\"id\":1}]");
		ApiEndpoint getEndpoint = new ApiEndpoint(version.getId(), null, "Get ok", "GET", "${baseUrl}/ok", 0);
		vault.saveEndpoint(getEndpoint);
		ApiEndpoint unsupportedAuthEndpoint = new ApiEndpoint(version.getId(), null, "Needs digest", "GET", "${baseUrl}/secure", 1);
		vault.saveEndpoint(unsupportedAuthEndpoint);
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.ENDPOINT, String.valueOf(unsupportedAuthEndpoint.getId()), ApiAuthType.UNSUPPORTED));

		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + getEndpoint.getId() + " Test");
		Assertions.assertNotNull(LastApiExecutionResultHolder.get());

		Assertions.assertThrows(BroadSQLException.class,
				() -> newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + unsupportedAuthEndpoint.getId() + " Test"),
				"UNSUPPORTED authentication must fail before any network request");

		Assertions.assertNull(LastApiExecutionResultHolder.get(), "an unsupported-authentication failure must clear the previously-successful result");
	}

	// ------------------------------------------------------------------------------------------
	// Finding 4 - inactive entities must refuse to execute
	// ------------------------------------------------------------------------------------------

	@Test
	void executionRefusesWhenTheApiItselfIsInactive() throws BroadSQLException {
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Get ok", "GET", "${baseUrl}/ok", 0);
		vault.saveEndpoint(endpoint);
		vault.deactivateApi(API_ID);

		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + endpoint.getId() + " Test");

		Assertions.assertTrue(console.getOutput().contains("is inactive"),
				"an inactive API must be reported as inactive, not merely 'not found': " + console.getOutput());
		Assertions.assertNull(LastApiExecutionResultHolder.get());
	}

	@Test
	void executionRefusesWhenTheEndpointIsInactive() throws BroadSQLException {
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Get ok", "GET", "${baseUrl}/ok", 0);
		vault.saveEndpoint(endpoint);
		vault.deactivateEndpoint(endpoint.getId());

		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + endpoint.getId() + " Test");

		Assertions.assertTrue(console.getOutput().contains("is inactive"),
				"an inactive endpoint must be reported as inactive: " + console.getOutput());
		Assertions.assertEquals(0, server.countRequestsTo("/ok"), "an inactive endpoint must never actually be called");
	}

	@Test
	void executionRefusesWhenTheEnvironmentIsInactive() throws BroadSQLException {
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Get ok", "GET", "${baseUrl}/ok", 0);
		vault.saveEndpoint(endpoint);
		vault.deactivateEnvironment(environment.getId());

		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + endpoint.getId() + " Test");

		Assertions.assertTrue(console.getOutput().contains("is inactive"),
				"an inactive environment must be reported as inactive: " + console.getOutput());
		Assertions.assertEquals(0, server.countRequestsTo("/ok"), "an inactive environment must never actually be reached");
	}

	@Test
	void executionStillSucceedsWhenEverythingIsActive() throws BroadSQLException {
		server.setRoute("/ok", 200, "[{\"id\":1}]");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Get ok", "GET", "${baseUrl}/ok", 0);
		vault.saveEndpoint(endpoint);

		newCommand().execute("EXECUTE API ENDPOINT " + API_ID + " " + endpoint.getId() + " Test");

		Assertions.assertEquals(1, server.countRequestsTo("/ok"), "the normal active/active/active case must still work");
		Assertions.assertNotNull(LastApiExecutionResultHolder.get());
	}
}
