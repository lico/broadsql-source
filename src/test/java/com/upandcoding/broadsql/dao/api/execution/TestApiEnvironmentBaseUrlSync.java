package com.upandcoding.broadsql.dao.api.execution;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
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
 * SPRINT XT02 sub-sprint 5's blocking regression test (see the sub-sprint plan's gate condition 4):
 * proves {@link ApiDefinitionsVault#setEnvironmentBaseUrl} keeps <b>both</b> live representations of
 * {@code baseUrl} in sync - the {@code API_ENVIRONMENT.BASE_URL} column ({@link ApiEndpointRequestBuilder}'s
 * {@code joinBaseUrl} fallback for a bare relative path) and the {@code (ENVIRONMENT, VARIABLE, baseUrl)}
 * attribute (ordinary variable resolution, the path every Bruno-imported {@code ${baseUrl}}-templated
 * endpoint actually uses) - and that a real HTTP execution against a {@code ${baseUrl}}-templated
 * endpoint actually reaches the new URL after the change. Before this fix existed, a caller that wrote
 * only the column (exactly what a naive GUI "Base URL" field would do) left this - the dominant - path
 * completely unaffected, so this is the test that would have caught that.
 */
class TestApiEnvironmentBaseUrlSync {

	private static final String API_ID = "BASEURLSYNC";

	private TestLocalHttpServer oldServer;
	private TestLocalHttpServer newServer;
	private ApiDefinitionsVault vault;
	private ApiVersion version;
	private ApiEnvironment environment;

	@BeforeEach
	void setUp() throws IOException, BroadSQLException {
		oldServer = new TestLocalHttpServer();
		newServer = new TestLocalHttpServer();
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		environment = new ApiEnvironment(API_ID, "Env", null, 0);
		vault.saveEnvironment(environment);
	}

	@AfterEach
	void tearDown() {
		oldServer.close();
		newServer.close();
	}

	@Test
	void setEnvironmentBaseUrlUpdatesBothTheColumnAndTheVariableAttribute() throws BroadSQLException {
		vault.setEnvironmentBaseUrl(environment, oldServer.baseUrl());

		ApiEnvironment reloaded = vault.findEnvironmentById(environment.getId());
		Assertions.assertEquals(oldServer.baseUrl(), reloaded.getBaseUrl(), "the API_ENVIRONMENT.BASE_URL column must be updated");

		List<ApiAttribute> variables = vault.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(environment.getId()), ApiAttributeKind.VARIABLE);
		ApiAttribute baseUrlAttr = variables.stream().filter(a -> "baseUrl".equalsIgnoreCase(a.getName())).findFirst().orElse(null);
		Assertions.assertNotNull(baseUrlAttr, "the (ENVIRONMENT, VARIABLE, baseUrl) attribute must exist");
		Assertions.assertEquals(oldServer.baseUrl(), baseUrlAttr.getValue());

		vault.setEnvironmentBaseUrl(environment, newServer.baseUrl());
		List<ApiAttribute> updatedVariables = vault.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(environment.getId()), ApiAttributeKind.VARIABLE);
		Assertions.assertEquals(1, updatedVariables.stream().filter(a -> "baseUrl".equalsIgnoreCase(a.getName())).count(),
				"changing the base URL a second time must update the existing attribute row, never add a second one");
		Assertions.assertEquals(newServer.baseUrl(), updatedVariables.stream().filter(a -> "baseUrl".equalsIgnoreCase(a.getName())).findFirst().get().getValue());
	}

	@Test
	void aTemplatedBaseUrlEndpointActuallyReachesTheNewServerAfterChangingTheEnvironmentBaseUrl() throws BroadSQLException {
		vault.setEnvironmentBaseUrl(environment, oldServer.baseUrl());
		oldServer.setRoute("/users", 200, "[{\"id\":1}]");
		newServer.setRoute("/users", 200, "[{\"id\":2}]");

		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "List users", "GET", "${baseUrl}/users", 0);
		vault.saveEndpoint(endpoint);

		ApiExecutionResult before = new ApiEndpointExecutor(vault).execute(API_ID, environment, endpoint, null);
		Assertions.assertTrue(before.getBody().contains("\"id\":1"), "before the change, the request must reach the old server");

		vault.setEnvironmentBaseUrl(environment, newServer.baseUrl());
		// Re-fetch the environment exactly like a real caller (e.g. the CONFIG API GUI, or a fresh
		// EXECUTE API ENDPOINT invocation) would after the change, rather than reusing the in-memory
		// instance whose baseUrl field setEnvironmentBaseUrl already mutated directly - the point of this
		// test is to prove the *persisted* state is correct, not just the object reference in hand.
		ApiEnvironment reloadedEnvironment = vault.findEnvironmentById(environment.getId());

		ApiExecutionResult after = new ApiEndpointExecutor(vault).execute(API_ID, reloadedEnvironment, endpoint, null);
		Assertions.assertTrue(after.getBody().contains("\"id\":2"), "after the change, the ${baseUrl}-templated request must reach the new server - "
				+ "this is exactly the case a column-only write would have missed");
	}

	@Test
	void aBareRelativePathEndpointAlsoReachesTheNewServerAfterChangingTheEnvironmentBaseUrl() throws BroadSQLException {
		vault.setEnvironmentBaseUrl(environment, oldServer.baseUrl());
		newServer.setRoute("/status", 200, "{\"ok\":true}");

		// A manually-created endpoint's bare relative path (no ${baseUrl} template) - the other live
		// resolution path, joinBaseUrl, which reads the API_ENVIRONMENT.BASE_URL column directly.
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Status", "GET", "/status", 0);
		vault.saveEndpoint(endpoint);

		vault.setEnvironmentBaseUrl(environment, newServer.baseUrl());
		ApiEnvironment reloadedEnvironment = vault.findEnvironmentById(environment.getId());

		ApiExecutionResult result = new ApiEndpointExecutor(vault).execute(API_ID, reloadedEnvironment, endpoint, null);
		Assertions.assertEquals(200, result.getStatusCode());
		Assertions.assertEquals(1, newServer.countRequestsTo("/status"));
	}

	/**
	 * SPRINT XT02 verification finding 2: {@code JApiEnvironmentsPanel.save} used to call
	 * {@code setEnvironmentBaseUrl(...)} followed immediately by a plain {@code replaceAttributes(...)}
	 * call carrying only the variables-table content - which, by the panel's own design (see
	 * {@code ApiEnvironmentFormModel#variablesForOwner}), deliberately excludes {@code baseUrl} (kept in
	 * its own dedicated field). That second call replaced the *entire* attribute set, silently deleting
	 * the {@code baseUrl} attribute the first call had just written. {@link ApiDefinitionsVault#saveEnvironmentVariablesAndBaseUrl}
	 * is the fix - this proves it keeps the baseUrl attribute alive and a {@code ${baseUrl}}-templated
	 * endpoint still resolves, even when the caller's variable list never itself mentions baseUrl.
	 */
	@Test
	void savingOtherVariablesTogetherWithBaseUrlNeverDeletesTheBaseUrlAttribute() throws BroadSQLException {
		vault.setEnvironmentBaseUrl(environment, oldServer.baseUrl());

		ApiAttribute otherVariable = new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(environment.getId()), ApiAttributeKind.VARIABLE,
				"apiVersion", "v2", false);
		vault.saveEnvironmentVariablesAndBaseUrl(environment, newServer.baseUrl(), List.of(otherVariable));

		List<ApiAttribute> variables = vault.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(environment.getId()), ApiAttributeKind.VARIABLE);
		ApiAttribute baseUrlAttr = variables.stream().filter(a -> "baseUrl".equalsIgnoreCase(a.getName())).findFirst().orElse(null);
		Assertions.assertNotNull(baseUrlAttr, "saving other variables together with the base URL must never delete the baseUrl attribute");
		Assertions.assertEquals(newServer.baseUrl(), baseUrlAttr.getValue());
		Assertions.assertTrue(variables.stream().anyMatch(a -> "apiVersion".equals(a.getName())), "the caller's other variable must still be saved");

		ApiEnvironment reloadedEnvironment = vault.findEnvironmentById(environment.getId());
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "List users again", "GET", "${baseUrl}/users", 0);
		vault.saveEndpoint(endpoint);
		newServer.setRoute("/users", 200, "[{\"id\":3}]");
		ApiExecutionResult result = new ApiEndpointExecutor(vault).execute(API_ID, reloadedEnvironment, endpoint, null);
		Assertions.assertTrue(result.getBody().contains("\"id\":3"),
				"a ${baseUrl}-templated endpoint must still resolve after saving other variables together with the base URL");
	}
}
