package com.upandcoding.broadsql.dao.api.execution;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.http.ApiHttpRequest;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

class TestApiEndpointRequestBuilder {

	private static final String API_ID = "BUILDERTEST";

	@Test
	void resolvesAnAlreadyAbsoluteImportedUrlWithoutTouchingIt() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Users", "GET", "${baseUrl}/users", 0);
		vault.saveEndpoint(endpoint);

		ApiHttpRequest request = new ApiEndpointRequestBuilder(vault).build(API_ID, env, endpoint, null);

		Assertions.assertEquals("https://dev.example.com/users", request.buildUri().toString());
	}

	@Test
	void prependsTheEnvironmentBaseUrlForARelativePath() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Users", "GET", "/users", 0);
		vault.saveEndpoint(endpoint);

		ApiHttpRequest request = new ApiEndpointRequestBuilder(vault).build(API_ID, env, endpoint, null);

		Assertions.assertEquals("https://dev.example.com/users", request.buildUri().toString());
	}

	@Test
	void unresolvedUrlVariableFailsBeforeAnyRequestIsBuilt() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Project", "GET", "${baseUrl}/rest/api/3/project/${cloudId}", 0);
		vault.saveEndpoint(endpoint);
		ApiEndpointRequestBuilder builder = new ApiEndpointRequestBuilder(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> builder.build(API_ID, env, endpoint, null));
		Assertions.assertEquals("Unable to resolve variable: cloudId", ex.getLocalizedMessage());
	}

	@Test
	void pathParameterValueIsSubstitutedAndPercentEncodedAsAPathSegmentNotAQueryValue() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Get User", "GET", "${baseUrl}/users/${userId}", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.PATH_PARAMETER, "userId", "john doe", false));

		ApiHttpRequest request = new ApiEndpointRequestBuilder(vault).build(API_ID, env, endpoint, null);

		Assertions.assertEquals("https://dev.example.com/users/john%20doe", request.buildUri().toString(), "a space in a path segment must be %20, never the query-encoding '+'");
	}

	@Test
	void repeatedEnabledQueryParametersArePreservedAndDisabledOnesAreOmitted() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Search", "GET", "${baseUrl}/search", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "tag", "admin", false));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "tag", "verified", false));
		ApiAttribute disabled = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "hidden", "value", false);
		disabled.setEnabled(false);
		vault.saveAttribute(disabled);

		ApiHttpRequest request = new ApiEndpointRequestBuilder(vault).build(API_ID, env, endpoint, null);

		String query = request.buildUri().getRawQuery();
		Assertions.assertEquals("tag=admin&tag=verified", query);
	}

	@Test
	void headersApplyWithApiThenGroupThenEndpointPrecedence() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.API, API_ID, ApiAttributeKind.HEADER, "Accept", "text/plain", false));
		ApiEndpointGroup group = new ApiEndpointGroup(version.getId(), null, "Group", 0);
		vault.saveEndpointGroup(group);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.GROUP, String.valueOf(group.getId()), ApiAttributeKind.HEADER, "Accept", "application/xml", false));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), group.getId(), "Resource", "GET", "${baseUrl}/resource", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.HEADER, "Accept", "application/json", false));
		ApiAttribute disabledHeader = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.HEADER, "X-Disabled", "nope", false);
		disabledHeader.setEnabled(false);
		vault.saveAttribute(disabledHeader);

		ApiHttpRequest request = new ApiEndpointRequestBuilder(vault).build(API_ID, env, endpoint, null);

		Assertions.assertEquals("application/json", request.getHeaders().get("Accept"), "the most specific owner (endpoint) must win");
		Assertions.assertNull(request.getHeaders().get("X-Disabled"), "a disabled header must never be sent");
	}

	// XT02 final corrective patch (Codex finding 1): a header inherited from the API must be replaced,
	// not duplicated, by an endpoint header that differs only by case.
	@Test
	void endpointHeaderOverridesAnApiLevelHeaderEvenWhenTheCaseDiffers() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.API, API_ID, ApiAttributeKind.HEADER, "Content-Type", "application/json", false));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Patch Resource", "PATCH", "${baseUrl}/resource", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.HEADER, "content-type", "application/merge-patch+json", false));

		ApiHttpRequest request = new ApiEndpointRequestBuilder(vault).build(API_ID, env, endpoint, null);

		Assertions.assertEquals(1, request.getHeaders().size(), "only one logical Content-Type header may survive");
		Assertions.assertEquals("application/merge-patch+json", request.getHeaders().get("Content-Type"),
				"the more specific endpoint value must win, regardless of the casing either side used");
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02-8: request body attachment
	// ------------------------------------------------------------------------------------------

	@Test
	void noBodyIsAttachedWhenTheEndpointHasNoConfiguredBody() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Delete User", "DELETE", "${baseUrl}/users/1", 0);
		vault.saveEndpoint(endpoint);

		ApiHttpRequest request = new ApiEndpointRequestBuilder(vault).build(API_ID, env, endpoint, null);

		Assertions.assertNull(request.getBody(), "a DELETE (or any verb) with no configured body must send none - the endpoint decides, not the verb");
	}

	@Test
	void jsonBodyIsInterpolatedAndAttached() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Create Customer", "POST", "${baseUrl}/customers", 0);
		endpoint.setBodyMode("json");
		endpoint.setBodyContent("{\"name\": \"{{CUSTOMER_NAME}}\", \"email\": \"${CUSTOMER_EMAIL}\"}");
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.VARIABLE, "CUSTOMER_NAME", "Alice", false));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.VARIABLE, "CUSTOMER_EMAIL", "alice@example.com", false));

		ApiHttpRequest request = new ApiEndpointRequestBuilder(vault).build(API_ID, env, endpoint, null);

		Assertions.assertEquals("{\"name\": \"Alice\", \"email\": \"alice@example.com\"}", request.getBody(),
				"both {{}} and ${} must interpolate inside the body, per XT02-7B's variable syntax");
	}

	@Test
	void defaultContentTypeIsAppliedWhenNoneIsConfigured() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Create Customer", "POST", "${baseUrl}/customers", 0);
		endpoint.setBodyMode("json");
		endpoint.setBodyContent("{}");
		vault.saveEndpoint(endpoint);

		ApiHttpRequest request = new ApiEndpointRequestBuilder(vault).build(API_ID, env, endpoint, null);

		Assertions.assertEquals("application/json", request.getHeaders().get("Content-Type"));
	}

	@Test
	void anExplicitContentTypeHeaderIsNeverOverriddenByTheDefault() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Create Customer", "POST", "${baseUrl}/customers", 0);
		endpoint.setBodyMode("json");
		endpoint.setBodyContent("{}");
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.HEADER, "Content-Type", "application/json; charset=utf-8", false));

		ApiHttpRequest request = new ApiEndpointRequestBuilder(vault).build(API_ID, env, endpoint, null);

		Assertions.assertEquals("application/json; charset=utf-8", request.getHeaders().get("Content-Type"),
				"an explicit, configured Content-Type header must always win over the default");
	}

	@Test
	void aStructuredBodyModeIsRefusedBeforeAnyNetworkCall() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Upload", "POST", "${baseUrl}/upload", 0);
		endpoint.setBodyMode("multipart-form");
		endpoint.setBodyContent("{\"type\":\"multipart-form\",\"params\":[]}");
		vault.saveEndpoint(endpoint);
		ApiEndpointRequestBuilder builder = new ApiEndpointRequestBuilder(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> builder.build(API_ID, env, endpoint, null));

		Assertions.assertTrue(ex.getLocalizedMessage().contains("multipart-form"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("Upload"));
	}

	@Test
	void anUnresolvedBodyVariableFailsBeforeAnyRequestIsBuilt() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Create Customer", "POST", "${baseUrl}/customers", 0);
		endpoint.setBodyMode("json");
		endpoint.setBodyContent("{\"name\": \"{{CUSTOMER_NAME}}\"}");
		vault.saveEndpoint(endpoint);
		ApiEndpointRequestBuilder builder = new ApiEndpointRequestBuilder(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> builder.build(API_ID, env, endpoint, null));

		Assertions.assertEquals("Unable to resolve request body variable: CUSTOMER_NAME", ex.getLocalizedMessage());
	}

	// ------------------------------------------------------------------------------------------
	// API Quality and UX Consolidation sprint: disabled query parameters embedded literally in the
	// raw URL template must never be resolved - the actual bug this phase fixes.
	// ------------------------------------------------------------------------------------------

	@Test
	void aDisabledQueryParameterEmbeddedLiterallyInTheUrlIsNeverResolvedAndTheRequestSucceeds() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		// The literal URL text still embeds ?expand=${expand}, exactly like a Bruno-authored or
		// hand-typed endpoint might, even though the structured attribute below is disabled and no
		// "expand" variable exists anywhere - this must not throw.
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Products", "GET", "${baseUrl}/products?expand=${expand}", 0);
		vault.saveEndpoint(endpoint);
		ApiAttribute disabledExpand = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "expand", "${expand}", false);
		disabledExpand.setEnabled(false);
		vault.saveAttribute(disabledExpand);

		ApiHttpRequest request = new ApiEndpointRequestBuilder(vault).build(API_ID, env, endpoint, null);

		Assertions.assertEquals("https://dev.example.com/products", request.buildUri().toString(),
				"a disabled query parameter must never appear in the effective URL, and its embedded placeholder must never be resolved");
	}

	@Test
	void anEnabledQueryParameterEmbeddedLiterallyInTheUrlStillFailsWhenUnresolved() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Products", "GET", "${baseUrl}/products?expand=${expand}", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "expand", "${expand}", false));
		ApiEndpointRequestBuilder builder = new ApiEndpointRequestBuilder(vault);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> builder.build(API_ID, env, endpoint, null));
		Assertions.assertEquals("Unable to resolve variable: expand", ex.getLocalizedMessage());
	}

	@Test
	void anEnabledQueryParameterEmbeddedLiterallyInTheUrlIsSentWithItsStructuredResolvedValue() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Products", "GET", "${baseUrl}/products?expand=${expand}", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.VARIABLE, "expand", "full", false));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "expand", "${expand}", false));

		ApiHttpRequest request = new ApiEndpointRequestBuilder(vault).build(API_ID, env, endpoint, null);

		Assertions.assertEquals("expand=full", request.buildUri().getRawQuery(),
				"the structured attribute's value/enabled state is authoritative - the embedded copy is never independently appended");
	}

	@Test
	void aLiteralQueryStringWithNoStructuredCounterpartIsStillSent() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		// Legacy/unmigrated data: a literal query string with no structured QUERY_PARAMETER row at all.
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Products", "GET", "${baseUrl}/products?type=widget", 0);
		vault.saveEndpoint(endpoint);

		ApiHttpRequest request = new ApiEndpointRequestBuilder(vault).build(API_ID, env, endpoint, null);

		Assertions.assertEquals("type=widget", request.buildUri().getRawQuery(),
				"a literal query parameter that predates the structured model must not silently stop being sent");
	}

	@Test
	void duplicateNamedEmbeddedOccurrencesReconcileByOccurrenceIndexNotJustByName() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = environment(vault, "https://dev.example.com");
		// Two "tag" occurrences embedded in the URL: the first is matched/superseded by a disabled
		// structured row, the second has no structured counterpart and must still be sent.
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Search", "GET", "${baseUrl}/search?tag=admin&tag=verified", 0);
		vault.saveEndpoint(endpoint);
		ApiAttribute disabledTag = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "tag", "admin", false);
		disabledTag.setEnabled(false);
		vault.saveAttribute(disabledTag);

		ApiHttpRequest request = new ApiEndpointRequestBuilder(vault).build(API_ID, env, endpoint, null);

		Assertions.assertEquals("tag=verified", request.buildUri().getRawQuery(),
				"the first 'tag' occurrence is superseded (and disabled) by the structured row; the second, unmatched occurrence is still sent");
	}

	private ApiEnvironment environment(ApiDefinitionsVault vault, String baseUrl) throws BroadSQLException {
		ApiEnvironment env = new ApiEnvironment(API_ID, "Env", baseUrl, 0);
		vault.saveEnvironment(env);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "baseUrl", baseUrl, false));
		return env;
	}
}
