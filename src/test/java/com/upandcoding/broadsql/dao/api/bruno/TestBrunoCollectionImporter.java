package com.upandcoding.broadsql.dao.api.bruno;

import java.io.File;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiVariableResolver;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiAuthType;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * Covers {@link BrunoCollectionImporter} against real, hand-authored OpenCollection YAML fixtures -
 * {@code src/test/resources/bruno/sample-collection.yml} (v1) and {@code sample-collection-v2.yml} (v2,
 * a modified re-export of the same collection) - deliberately not synthetic YAML written to make the
 * importer pass, per the SPRINT XT02 sub-sprint 2 acceptance requirement to prove the model against
 * realistic Bruno data: nested folders three levels deep, two environments, variables at every scope,
 * GET/HEAD/POST/PUT/DELETE, query and path parameters (including a repeated query parameter name),
 * headers, a request body, and four different authentication configurations (collection-level bearer,
 * endpoint-level API key, group-level OAuth2 client credentials, and an inherited/no-auth endpoint).
 */
class TestBrunoCollectionImporter {

	private static final String API_ID = "DEMO";

	@Test
	void importsEveryHttpVerbWithFolderHierarchyIntact() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		BrunoImportResult result = new BrunoCollectionImporter(vault).importFile(API_ID, fixture("sample-collection.yml"));

		Assertions.assertEquals("Demo API", vault.getApi(API_ID).getName());
		ApiVersion version = vault.getDefaultVersion(API_ID);

		List<ApiEndpointGroup> groups = vault.getGroupsForVersion(version.getId());
		Assertions.assertEquals(4, groups.size(), "Users, Search, Advanced Search, Administration");
		ApiEndpointGroup users = findGroup(groups, "Users");
		ApiEndpointGroup search = findGroup(groups, "Search");
		ApiEndpointGroup advancedSearch = findGroup(groups, "Advanced Search");
		Assertions.assertNull(users.getParentGroupId(), "Users is a top-level folder");
		Assertions.assertEquals(users.getId(), search.getParentGroupId(), "Search is nested under Users");
		Assertions.assertEquals(search.getId(), advancedSearch.getParentGroupId(), "Advanced Search is nested under Search - three levels deep");

		List<ApiEndpoint> endpoints = vault.getEndpointsForVersion(version.getId());
		Assertions.assertEquals(8, endpoints.size());
		Assertions.assertEquals(8, result.getEndpointsCreated());
		Assertions.assertTrue(hasMethod(endpoints, "GET"), "GET must be imported");
		Assertions.assertTrue(hasMethod(endpoints, "HEAD"), "HEAD must be imported");
		Assertions.assertTrue(hasMethod(endpoints, "POST"), "POST must be imported even though it cannot execute yet");
		Assertions.assertTrue(hasMethod(endpoints, "PUT"), "PUT must be imported even though it cannot execute yet");

		Assertions.assertEquals(2, result.getEnvironmentsCreated());
		Assertions.assertEquals(1, result.getSkippedItemTypes().size(), "the root-level ScriptFile item must be skipped, not fatal");
		Assertions.assertEquals("script", result.getSkippedItemTypes().get(0));
		Assertions.assertTrue(result.getRuntimeFeaturesIgnored() >= 1, "Get User's test script must be counted as an ignored runtime feature, never persisted");
	}

	@Test
	void preservesRequestBodiesForWriteMethods() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vault).importFile(API_ID, fixture("sample-collection.yml"));
		ApiVersion version = vault.getDefaultVersion(API_ID);

		ApiEndpoint createUser = findEndpoint(vault.getEndpointsForVersion(version.getId()), "Create User");
		Assertions.assertEquals("POST", createUser.getMethod());
		Assertions.assertEquals("json", createUser.getBodyMode());
		Assertions.assertTrue(createUser.getBodyContent().contains("${userName}"), "the body's own placeholder must also be normalized");
	}

	@Test
	void preservesFormUrlEncodedBodyLosslessly() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vault).importFile(API_ID, fixture("sample-collection.yml"));
		ApiVersion version = vault.getDefaultVersion(API_ID);
		ApiEndpoint legacyLogin = findEndpoint(vault.getEndpointsForVersion(version.getId()), "Legacy Login");

		Assertions.assertEquals("form-urlencoded", legacyLogin.getBodyMode());
		JsonArray fields = JsonParser.parseString(legacyLogin.getBodyContent()).getAsJsonObject().getAsJsonArray("data");
		Assertions.assertEquals(3, fields.size(), "every field, including the disabled one, must survive - never silently dropped");

		JsonObject username = fields.get(0).getAsJsonObject();
		Assertions.assertEquals("username", username.get("name").getAsString());
		Assertions.assertEquals("${username}", username.get("value").getAsString(), "the field's own placeholder must be normalized too");

		JsonObject password = fields.get(1).getAsJsonObject();
		Assertions.assertEquals("user's password", password.get("description").getAsString(), "description must not be lost");

		JsonObject rememberMe = fields.get(2).getAsJsonObject();
		Assertions.assertTrue(rememberMe.get("disabled").getAsBoolean(), "disabled state must not be lost");
	}

	@Test
	void preservesMultipartFormBodyLosslesslyIncludingArrayValuedFields() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vault).importFile(API_ID, fixture("sample-collection.yml"));
		ApiVersion version = vault.getDefaultVersion(API_ID);
		ApiEndpoint uploadAvatar = findEndpoint(vault.getEndpointsForVersion(version.getId()), "Upload Avatar");

		Assertions.assertEquals("multipart-form", uploadAvatar.getBodyMode());
		JsonArray fields = JsonParser.parseString(uploadAvatar.getBodyContent()).getAsJsonObject().getAsJsonArray("data");
		Assertions.assertEquals(3, fields.size());

		JsonObject filePart = fields.get(1).getAsJsonObject();
		Assertions.assertEquals("file", filePart.get("type").getAsString(), "the text/file part type must not be lost");
		Assertions.assertEquals("image/png", filePart.get("contentType").getAsString(), "contentType must not be lost - it is not representable by the generic ApiAttribute columns alone");

		JsonObject tagsPart = fields.get(2).getAsJsonObject();
		Assertions.assertTrue(tagsPart.get("value").isJsonArray(), "a multi-value field (multiple values under one field name) must round-trip as an array, not be collapsed to a single string");
		Assertions.assertEquals(2, tagsPart.get("value").getAsJsonArray().size());
		Assertions.assertTrue(tagsPart.get("disabled").getAsBoolean());
	}

	@Test
	void distinguishesQueryFromPathParametersAndPreservesRepeatedNames() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vault).importFile(API_ID, fixture("sample-collection.yml"));
		ApiVersion version = vault.getDefaultVersion(API_ID);
		ApiEndpoint advancedSearch = findEndpoint(vault.getEndpointsForVersion(version.getId()), "Advanced Search Users");

		List<ApiAttribute> queryParams = vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(advancedSearch.getId()), ApiAttributeKind.QUERY_PARAMETER);
		List<ApiAttribute> pathParams = vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(advancedSearch.getId()), ApiAttributeKind.PATH_PARAMETER);

		Assertions.assertEquals(1, pathParams.size());
		Assertions.assertEquals("id", pathParams.get(0).getName());
		Assertions.assertEquals(3, queryParams.size(), "q, and two repeated 'tag' entries - a generic name/value table must not silently collapse repeated names");
		Assertions.assertEquals(2, queryParams.stream().filter(p -> "tag".equals(p.getName())).count());
	}

	@Test
	void importsAuthenticationAtEveryOwnerLevelKeepingCredentialsEnvironmentResolved() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vault).importFile(API_ID, fixture("sample-collection.yml"));
		ApiVersion version = vault.getDefaultVersion(API_ID);

		ApiAuthConfig apiAuth = vault.getAuth(ApiOwnerType.API, API_ID);
		Assertions.assertEquals(ApiAuthType.BEARER, apiAuth.getAuthType());
		String tokenProp = attributeValue(vault.getAttributes(ApiOwnerType.AUTH, String.valueOf(apiAuth.getId()), ApiAttributeKind.PROPERTY), "token");
		Assertions.assertEquals("${token}", tokenProp, "a Bearer token referencing {{token}} must remain an unresolved, environment-resolvable placeholder");

		ApiEndpoint updateUser = findEndpoint(vault.getEndpointsForVersion(version.getId()), "Update User");
		ApiAuthConfig endpointAuth = vault.getAuth(ApiOwnerType.ENDPOINT, String.valueOf(updateUser.getId()));
		Assertions.assertEquals(ApiAuthType.API_KEY_HEADER, endpointAuth.getAuthType());

		ApiEndpointGroup administration = findGroup(vault.getGroupsForVersion(version.getId()), "Administration");
		ApiAuthConfig groupAuth = vault.getAuth(ApiOwnerType.GROUP, String.valueOf(administration.getId()));
		Assertions.assertEquals(ApiAuthType.OAUTH2_CLIENT_CREDENTIALS, groupAuth.getAuthType());
		List<ApiAttribute> oauthProps = vault.getAttributes(ApiOwnerType.AUTH, String.valueOf(groupAuth.getId()), ApiAttributeKind.PROPERTY);
		Assertions.assertEquals("${baseUrl}/oauth/token", attributeValue(oauthProps, "accessTokenUrl"));
		Assertions.assertEquals("${clientSecret}", attributeValue(oauthProps, "clientSecret"));

		ApiEndpoint healthCheck = findEndpoint(vault.getEndpointsForVersion(version.getId()), "Health Check");
		Assertions.assertNull(vault.getAuth(ApiOwnerType.ENDPOINT, String.valueOf(healthCheck.getId())), "'auth: inherit' must not write a spurious auth row at the endpoint level");
	}

	@Test
	void sameEndpointResolvesDifferentlyPerSelectedEnvironment() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vault).importFile(API_ID, fixture("sample-collection.yml"));
		ApiVersion version = vault.getDefaultVersion(API_ID);
		ApiEndpoint getUser = findEndpoint(vault.getEndpointsForVersion(version.getId()), "Get User");
		ApiEnvironment development = findEnvironment(vault.getEnvironmentsForApi(API_ID), "Development");
		ApiEnvironment production = findEnvironment(vault.getEnvironmentsForApi(API_ID), "Production");

		ApiVariableResolver resolver = new ApiVariableResolver(vault);
		Map<String, String> devVars = resolver.resolve(API_ID, development, getUser, null);
		Map<String, String> prodVars = resolver.resolve(API_ID, production, getUser, null);

		Assertions.assertEquals("https://dev.example.com", devVars.get("baseUrl"));
		Assertions.assertEquals("https://api.example.com", prodVars.get("baseUrl"));
		Assertions.assertNotEquals(devVars.get("tenant"), prodVars.get("tenant"), "the same imported endpoint must resolve differently depending on the selected environment");
		Assertions.assertEquals("dev-tenant", devVars.get("tenant"));
		Assertions.assertEquals("prod-tenant", prodVars.get("tenant"));
	}

	@Test
	void reimportingTheUnchangedCollectionUpdatesInPlaceWithoutDuplicating() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vault).importFile(API_ID, fixture("sample-collection.yml"));
		ApiVersion version = vault.getDefaultVersion(API_ID);
		int groupsBefore = vault.getGroupsForVersion(version.getId()).size();
		int endpointsBefore = vault.getEndpointsForVersion(version.getId()).size();
		int environmentsBefore = vault.getEnvironmentsForApi(API_ID).size();

		BrunoImportResult second = new BrunoCollectionImporter(vault).importFile(API_ID, fixture("sample-collection.yml"));

		Assertions.assertEquals(0, second.getGroupsCreated());
		Assertions.assertEquals(4, second.getGroupsUpdated());
		Assertions.assertEquals(0, second.getEndpointsCreated());
		Assertions.assertEquals(8, second.getEndpointsUpdated());
		Assertions.assertEquals(0, second.getEnvironmentsCreated());
		Assertions.assertEquals(2, second.getEnvironmentsUpdated());
		Assertions.assertEquals(groupsBefore, vault.getGroupsForVersion(version.getId()).size(), "re-importing an unchanged collection must never duplicate folders");
		Assertions.assertEquals(endpointsBefore, vault.getEndpointsForVersion(version.getId()).size(), "re-importing an unchanged collection must never duplicate endpoints");
		Assertions.assertEquals(environmentsBefore, vault.getEnvironmentsForApi(API_ID).size(), "re-importing an unchanged collection must never duplicate environments");
	}

	@Test
	void reimportingAModifiedCollectionUpdatesRenamesAndInsertsNewItemsWithoutTouchingUnrelatedObjects() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		BrunoImportResult first = new BrunoCollectionImporter(vault).importFile(API_ID, fixture("sample-collection.yml"));
		ApiVersion version = vault.getDefaultVersion(API_ID);

		int groupsBefore = vault.getGroupsForVersion(version.getId()).size();
		int endpointsBefore = vault.getEndpointsForVersion(version.getId()).size();
		System.out.println("Before re-import: " + first + " | groups=" + groupsBefore + " endpoints=" + endpointsBefore);

		BrunoImportResult second = new BrunoCollectionImporter(vault).importFile(API_ID, fixture("sample-collection-v2.yml"));

		List<ApiEndpoint> endpointsAfter = vault.getEndpointsForVersion(version.getId());
		System.out.println("After re-import: " + second + " | groups=" + vault.getGroupsForVersion(version.getId()).size() + " endpoints=" + endpointsAfter.size());

		// Renamed "Get User" -> "Fetch User": updated in place, not duplicated.
		Assertions.assertEquals(1, second.getEndpointsCreated(), "only Delete User is genuinely new");
		Assertions.assertEquals(6, second.getEndpointsUpdated(), "Advanced Search Users, Get/Fetch User, Create User, Update User, Head User, Health Check");
		Assertions.assertEquals(endpointsBefore + 1, endpointsAfter.size(), "exactly one net new endpoint (Delete User) - the rename must not have created a duplicate");
		Assertions.assertTrue(endpointsAfter.stream().noneMatch(e -> "Get User".equals(e.getName())), "the old name must be gone");
		ApiEndpoint fetchUser = findEndpoint(endpointsAfter, "Fetch User");
		Assertions.assertNotNull(fetchUser, "renamed via the same seq-based source key, so it must be found under its new name");

		List<ApiAttribute> fetchUserHeaders = vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(fetchUser.getId()), ApiAttributeKind.HEADER);
		Assertions.assertEquals("application/vnd.api+json", attributeValue(fetchUserHeaders, "Accept"), "an altered header value must be reflected after re-import");
		Assertions.assertEquals("true", attributeValue(fetchUserHeaders, "X-Debug"), "a newly added header must appear after re-import");
		List<ApiAttribute> fetchUserParams = vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(fetchUser.getId()), ApiAttributeKind.QUERY_PARAMETER);
		Assertions.assertEquals("profile,orders", attributeValue(fetchUserParams, "expand"), "an altered query parameter value must be reflected after re-import");

		ApiEndpoint deleteUser = findEndpoint(endpointsAfter, "Delete User");
		Assertions.assertEquals("DELETE", deleteUser.getMethod());

		// Folders: no duplication.
		Assertions.assertEquals(0, second.getGroupsCreated());
		Assertions.assertEquals(groupsBefore, vault.getGroupsForVersion(version.getId()).size(), "folder structure unchanged between v1 and v2 must not duplicate");

		// Environment variable change.
		Assertions.assertEquals(0, second.getEnvironmentsCreated());
		ApiEnvironment development = findEnvironment(vault.getEnvironmentsForApi(API_ID), "Development");
		List<ApiAttribute> devVars = vault.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(development.getId()), ApiAttributeKind.VARIABLE);
		Assertions.assertEquals("dev-tenant-v2", attributeValue(devVars, "tenant"), "an environment variable change must be reflected after re-import");

		// Unrelated endpoints must be untouched in substance (still present, same method/path).
		ApiEndpoint createUser = findEndpoint(endpointsAfter, "Create User");
		Assertions.assertEquals("POST", createUser.getMethod());
	}

	// ------------------------------------------------------------------------------------------
	// API Quality and UX Consolidation sprint: a url with a query string embedded literally must not
	// be persisted verbatim alongside structured QUERY_PARAMETER attributes that already cover it.
	// ------------------------------------------------------------------------------------------

	@Test
	void stripsQueryParametersAlreadyRepresentedStructurallyFromThePersistedPath() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vault).importFile(API_ID, fixture("query-string-duplication.yml"));
		ApiVersion version = vault.getDefaultVersion(API_ID);
		ApiEndpoint products = findEndpoint(vault.getEndpointsForVersion(version.getId()), "Products");

		Assertions.assertEquals("${baseUrl}/products?type=widget", products.getEndpointPath(),
				"'expand' and 'limit' are covered by structured params and must be stripped; 'type' has no structured counterpart and must be preserved");

		List<ApiAttribute> queryParams = vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(products.getId()), ApiAttributeKind.QUERY_PARAMETER);
		Assertions.assertEquals(2, queryParams.size());
		Assertions.assertEquals("${expand}", attributeValue(queryParams, "expand"));
		Assertions.assertEquals("10", attributeValue(queryParams, "limit"));
	}

	private File fixture(String name) throws BroadSQLException {
		try {
			return new File(getClass().getResource("/bruno/" + name).toURI());
		} catch (URISyntaxException | NullPointerException e) {
			throw new BroadSQLException("Missing test fixture '" + name + "'", e);
		}
	}

	private ApiEndpointGroup findGroup(List<ApiEndpointGroup> groups, String name) {
		return groups.stream().filter(g -> name.equals(g.getName())).findFirst()
				.orElseThrow(() -> new AssertionError("Expected group '" + name + "' not found among " + groups.size() + " groups"));
	}

	private ApiEndpoint findEndpoint(List<ApiEndpoint> endpoints, String name) {
		return endpoints.stream().filter(e -> name.equals(e.getName())).findFirst()
				.orElseThrow(() -> new AssertionError("Expected endpoint '" + name + "' not found among " + endpoints.size() + " endpoints"));
	}

	private ApiEnvironment findEnvironment(List<ApiEnvironment> environments, String name) {
		return environments.stream().filter(e -> name.equals(e.getName())).findFirst()
				.orElseThrow(() -> new AssertionError("Expected environment '" + name + "' not found"));
	}

	private boolean hasMethod(List<ApiEndpoint> endpoints, String method) {
		return endpoints.stream().anyMatch(e -> method.equals(e.getMethod()));
	}

	private String attributeValue(List<ApiAttribute> attributes, String name) {
		return attributes.stream().filter(a -> name.equals(a.getName())).findFirst().map(ApiAttribute::getValue)
				.orElse(null);
	}
}
