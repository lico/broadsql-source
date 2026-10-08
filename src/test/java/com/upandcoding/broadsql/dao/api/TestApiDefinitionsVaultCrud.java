package com.upandcoding.broadsql.dao.api;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiAuthType;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiImportSource;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Covers {@link ApiDefinitionsVault}'s CRUD methods across every table in the section 13.1 model, plus
 * the idempotent-reimport upsert paths ({@code findEnvironmentBySource}/{@code findGroupBySource}/
 * {@code findEndpointBySource}, {@code saveAuth}/{@code saveImportSource}'s "at most one row" semantics,
 * {@code replaceAttributes}) sub-sprint 2's importer will rely on.
 */
class TestApiDefinitionsVaultCrud {

	@Test
	void createsAnApiWithItsDefaultVersion() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();

		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("GITHUB"));

		Assertions.assertTrue(vault.contains("GITHUB"));
		Assertions.assertTrue(version.isDefault());
		Assertions.assertEquals("GITHUB", version.getApiId());
		ApiDefinition reloaded = vault.getApi("GITHUB");
		Assertions.assertEquals(version.getId(), reloaded.getDefaultVersionId(), "API.DEFAULT_VERSION_ID must point at the created version");
		Assertions.assertEquals(version.getId(), vault.getDefaultVersion("GITHUB").getId());
	}

	@Test
	void persistsAcrossASeparateVaultInstanceLoadedFromTheSameFile() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition("GITHUB"));

		ApiDefinitionsVault reopened = new ApiDefinitionsVault(vault.getFileName(), vault.getPassword());
		reopened.load();

		Assertions.assertTrue(reopened.contains("GITHUB"), "a fresh vault instance pointed at the same CDF file must see previously saved data");
	}

	@Test
	void savesAndUpdatesAnEnvironmentByImportSourceIdentity() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition("GITHUB"));

		ApiEnvironment env = new ApiEnvironment("GITHUB", "Development", "https://dev.example.com", 0);
		env.setSourceType("BRUNO_YAML");
		env.setSourceKey("environments/development.yml");
		vault.saveEnvironment(env);

		// Simulate a re-import: resolve by source identity, update in place, save again.
		ApiEnvironment found = vault.findEnvironmentBySource("GITHUB", "BRUNO_YAML", "environments/development.yml");
		Assertions.assertNotNull(found);
		found.setBaseUrl("https://dev2.example.com");
		vault.saveEnvironment(found);

		List<ApiEnvironment> environments = vault.getEnvironmentsForApi("GITHUB");
		Assertions.assertEquals(1, environments.size(), "re-import must update in place, never duplicate");
		Assertions.assertEquals("https://dev2.example.com", environments.get(0).getBaseUrl());
	}

	@Test
	void savesHierarchicalEndpointGroupsAndResolvesThemBySourceIdentity() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("JIRA"));

		ApiEndpointGroup root = new ApiEndpointGroup(version.getId(), null, "Issues", 0);
		root.setSourceType("BRUNO_YAML");
		root.setSourceKey("Issues");
		vault.saveEndpointGroup(root);

		ApiEndpointGroup child = new ApiEndpointGroup(version.getId(), root.getId(), "Search", 0);
		child.setSourceType("BRUNO_YAML");
		child.setSourceKey("Issues/Search");
		vault.saveEndpointGroup(child);

		List<ApiEndpointGroup> groups = vault.getGroupsForVersion(version.getId());
		Assertions.assertEquals(2, groups.size());

		ApiEndpointGroup resolvedChild = vault.findGroupBySource(version.getId(), "BRUNO_YAML", "Issues/Search");
		Assertions.assertNotNull(resolvedChild);
		Assertions.assertEquals(root.getId(), resolvedChild.getParentGroupId());
	}

	@Test
	void savesEndpointsOfEveryHttpVerbWithBodyIntact() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("JIRA"));

		ApiEndpoint get = new ApiEndpoint(version.getId(), null, "Search issues", "GET", "/rest/api/3/search", 0);
		vault.saveEndpoint(get);

		ApiEndpoint post = new ApiEndpoint(version.getId(), null, "Create issue", "POST", "/rest/api/3/issue", 1);
		post.setBodyMode("json");
		post.setBodyContent("{\"fields\":{\"summary\":\"${title}\"}}");
		vault.saveEndpoint(post);

		List<ApiEndpoint> endpoints = vault.getEndpointsForVersion(version.getId());
		Assertions.assertEquals(2, endpoints.size(), "every HTTP verb must be persisted, not just GET/HEAD");
		ApiEndpoint reloadedPost = endpoints.stream().filter(e -> "POST".equals(e.getMethod())).findFirst().orElseThrow();
		Assertions.assertEquals("{\"fields\":{\"summary\":\"${title}\"}}", reloadedPost.getBodyContent(), "the request body must be preserved even though Release 1 cannot execute POST");
	}

	@Test
	void savesAndReplacesAuthConfigurationWithoutAccumulatingRows() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition("GITHUB"));

		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.API, "GITHUB", ApiAuthType.BEARER));
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.API, "GITHUB", ApiAuthType.API_KEY_HEADER));

		ApiAuthConfig auth = vault.getAuth(ApiOwnerType.API, "GITHUB");
		Assertions.assertEquals(ApiAuthType.API_KEY_HEADER, auth.getAuthType(), "saving auth for the same owner twice must update, not create a second row");
	}

	@Test
	void replacesAttributesAtomicallyForReimport() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition("GITHUB"));

		vault.replaceAttributes(ApiOwnerType.API, "GITHUB", ApiAttributeKind.VARIABLE,
				List.of(new ApiAttribute(ApiOwnerType.API, "GITHUB", ApiAttributeKind.VARIABLE, "org", "acme", false)));
		vault.replaceAttributes(ApiOwnerType.API, "GITHUB", ApiAttributeKind.VARIABLE,
				List.of(new ApiAttribute(ApiOwnerType.API, "GITHUB", ApiAttributeKind.VARIABLE, "org", "acme-2", false),
						new ApiAttribute(ApiOwnerType.API, "GITHUB", ApiAttributeKind.VARIABLE, "region", "eu", false)));

		List<ApiAttribute> attrs = vault.getAttributes(ApiOwnerType.API, "GITHUB", ApiAttributeKind.VARIABLE);
		Assertions.assertEquals(2, attrs.size(), "a second replaceAttributes call must wipe the first set, never accumulate");
	}

	@Test
	void savesAndUpdatesImportSourceProvenanceForAnApi() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition("GITHUB"));

		vault.saveImportSource(new ApiImportSource("GITHUB", ApiImportSource.SOURCE_TYPE_BRUNO_YAML, "C:/collections/github.yml", null));
		vault.saveImportSource(new ApiImportSource("GITHUB", ApiImportSource.SOURCE_TYPE_BRUNO_YAML, "C:/collections/github-v2.yml", null));

		ApiImportSource source = vault.getImportSource("GITHUB");
		Assertions.assertEquals("C:/collections/github-v2.yml", source.getSourceLocation(), "re-importing must update the single provenance row for this API, not add another");
		Assertions.assertNotNull(source.getLastImportedAt());
	}

	@Test
	void duplicateEnvironmentCopiesBaseUrlAndVariablesIncludingSecrets() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition("GITHUB"));
		ApiEnvironment dev = new ApiEnvironment("GITHUB", "Development", "https://dev.example.com", 0);
		vault.saveEnvironment(dev);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(dev.getId()), ApiAttributeKind.VARIABLE, "tenant", "dev-tenant", false));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(dev.getId()), ApiAttributeKind.VARIABLE, "token", "s3cr3t", true));

		ApiEnvironment prod = vault.duplicateEnvironment(dev.getId(), "Production");

		Assertions.assertNotEquals(dev.getId(), prod.getId());
		Assertions.assertEquals("Production", prod.getName());
		Assertions.assertEquals("https://dev.example.com", prod.getBaseUrl(), "duplicate must start with the same baseUrl - the user edits it afterward");
		List<ApiAttribute> prodVars = vault.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(prod.getId()), ApiAttributeKind.VARIABLE);
		Assertions.assertEquals(2, prodVars.size());
		ApiAttribute token = prodVars.stream().filter(a -> "token".equals(a.getName())).findFirst().orElseThrow();
		Assertions.assertTrue(token.isSecret());
		Assertions.assertEquals("s3cr3t", token.getValue(), "secrets are copied on duplicate, not cleared - a deliberate, documented choice");

		// The original environment's own data must be completely unaffected by the duplication.
		List<ApiAttribute> devVars = vault.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(dev.getId()), ApiAttributeKind.VARIABLE);
		Assertions.assertEquals(2, devVars.size());
	}

	@Test
	void endpointAliasIsSavedReloadedAndEnforcedUniquePerApi() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("ORDERS"));

		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Create order", "POST", "/orders", 0);
		endpoint.setAlias("DO_ORDER");
		vault.saveEndpoint(endpoint);

		ApiEndpoint reloaded = vault.findEndpointById(endpoint.getId());
		Assertions.assertEquals("DO_ORDER", reloaded.getAlias(), "the alias must survive save/reload");
		Assertions.assertEquals(endpoint.getId(), vault.findEndpointByAlias("ORDERS", "do_order").getId(), "alias lookup must be case-insensitive");

		ApiEndpoint another = new ApiEndpoint(version.getId(), null, "Create order v2", "POST", "/v2/orders", 1);
		another.setAlias("DO_ORDER");
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.saveEndpoint(another));
		Assertions.assertEquals("Alias DO_ORDER is already assigned to another API endpoint.", ex.getMessage());

		// Saving the SAME endpoint again under its own existing alias must never conflict with itself.
		endpoint.setName("Create order (renamed)");
		vault.saveEndpoint(endpoint);
		Assertions.assertEquals("DO_ORDER", vault.findEndpointById(endpoint.getId()).getAlias());
	}

	@Test
	void theSameAliasIsAllowedInTwoDifferentApis() throws BroadSQLException {
		// SPRINT XT02-7B revises the Release-1 "globally unique" decision (docs/Amendment - Endpoint
		// Aliases and Future Scriptability.md, section 6) to per-API scope: interactive execution
		// (RUN <alias>) always resolves within an explicitly active CONNECT API context, so two
		// different APIs sharing an alias is no longer ambiguous.
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion orders = vault.createApiWithDefaultVersion(new ApiDefinition("ORDERS"));
		ApiVersion legacyOrders = vault.createApiWithDefaultVersion(new ApiDefinition("LEGACY_ORDERS"));

		ApiEndpoint ordersEndpoint = new ApiEndpoint(orders.getId(), null, "Create order", "POST", "/orders", 0);
		ordersEndpoint.setAlias("DO_ORDER");
		vault.saveEndpoint(ordersEndpoint);

		ApiEndpoint legacyEndpoint = new ApiEndpoint(legacyOrders.getId(), null, "Create order (legacy)", "POST", "/orders", 0);
		legacyEndpoint.setAlias("DO_ORDER");
		vault.saveEndpoint(legacyEndpoint);

		Assertions.assertEquals(ordersEndpoint.getId(), vault.findEndpointByAlias("ORDERS", "DO_ORDER").getId());
		Assertions.assertEquals(legacyEndpoint.getId(), vault.findEndpointByAlias("LEGACY_ORDERS", "DO_ORDER").getId());
		Assertions.assertNull(vault.findEndpointByAlias("GITHUB", "DO_ORDER"), "an API that never defined this alias must not resolve it");
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02B, section 6: findEndpointByName - unlike findEndpointByAlias, names are not
	// guaranteed unique within one API, so this returns every match instead of the first one.
	// ------------------------------------------------------------------------------------------

	@Test
	void findEndpointByNameIsCaseInsensitiveAndScopedToOneApi() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion ordersVersion = vault.createApiWithDefaultVersion(new ApiDefinition("ORDERS"));
		ApiEndpoint endpoint = new ApiEndpoint(ordersVersion.getId(), null, "findAll", "GET", "/orders", 0);
		vault.saveEndpoint(endpoint);
		vault.createApiWithDefaultVersion(new ApiDefinition("OTHER"));

		Assertions.assertEquals(1, vault.findEndpointByName("ORDERS", "findall").size());
		Assertions.assertEquals(endpoint.getId(), vault.findEndpointByName("ORDERS", "FINDALL").get(0).getId());
		Assertions.assertTrue(vault.findEndpointByName("OTHER", "findAll").isEmpty());
	}

	@Test
	void findEndpointByNameReturnsEveryMatchWhenNotUnique() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("ORDERS"));
		ApiEndpoint first = new ApiEndpoint(version.getId(), null, "findAll", "GET", "/customers", 0);
		vault.saveEndpoint(first);
		ApiEndpoint second = new ApiEndpoint(version.getId(), null, "findAll", "GET", "/orders", 1);
		vault.saveEndpoint(second);

		Assertions.assertEquals(2, vault.findEndpointByName("ORDERS", "findAll").size());
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02B, section 5: upsertVariable - the safe VAR ... PERSIST primitive, deliberately not
	// replaceAttributes (which would wipe every sibling variable of that owner).
	// ------------------------------------------------------------------------------------------

	@Test
	void upsertVariableInsertsWhenNoneExistsAndUpdatesByCaseInsensitiveNameOtherwise() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition("ORDERS"));

		vault.upsertVariable(ApiOwnerType.API, "ORDERS", "key", "first");
		var afterInsert = vault.getAttributes(ApiOwnerType.API, "ORDERS", ApiAttributeKind.VARIABLE);
		Assertions.assertEquals(1, afterInsert.size());
		Assertions.assertEquals("first", afterInsert.get(0).getValue());

		vault.upsertVariable(ApiOwnerType.API, "ORDERS", "KEY", "second");
		var afterUpdate = vault.getAttributes(ApiOwnerType.API, "ORDERS", ApiAttributeKind.VARIABLE);
		Assertions.assertEquals(1, afterUpdate.size(), "must update the same row by case-insensitive name, not insert a second one");
		Assertions.assertEquals("second", afterUpdate.get(0).getValue());
	}

	@Test
	void findEndpointByNameExcludesInactiveEndpoints() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("ORDERS"));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "findAll", "GET", "/orders", 0);
		vault.saveEndpoint(endpoint);
		vault.deactivateEndpoint(endpoint.getId());

		Assertions.assertTrue(vault.findEndpointByName("ORDERS", "findAll").isEmpty());
	}

	@Test
	void endpointAliasRejectsAnInvalidIdentifier() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("ORDERS"));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Create order", "POST", "/orders", 0);
		endpoint.setAlias("1-not-an-identifier");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.saveEndpoint(endpoint));
		Assertions.assertTrue(ex.getMessage().contains("not a valid BroadSQL identifier"));
	}

	@Test
	void softDeletedStatusIsPersistedAndExcludedFromTheActiveCache() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiDefinition api = new ApiDefinition("GITHUB");
		vault.createApiWithDefaultVersion(api);

		api.setStatusId(DatabaseDefinition.STATUS_INACTIVE);
		vault.saveApi(api);

		Assertions.assertFalse(vault.contains("GITHUB"), "an inactive API must drop out of the active cache, same convention as CONNECTIONS/INSTANCE/ENVIRONMENT");
	}
}
