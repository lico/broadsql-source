package com.upandcoding.broadsql.dao.api.auth;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiAuthType;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * Covers {@link ApiEffectiveAuthResolver}'s inheritance algorithm - docs/SPRINT XT02 - Universal API
 * Client.md, section 19.2: endpoint -&gt; nearest parent group -&gt; ... -&gt; API -&gt; NONE, with an explicit
 * {@code NONE} row at any level stopping the walk immediately (never falling through to a less specific
 * owner's real authentication), and an absent row (no {@code API_AUTH} row at all - "inherit") correctly
 * continuing the walk instead.
 */
class TestApiEffectiveAuthResolver {

	private static final String API_ID = "AUTHTEST";

	@Test
	void apiLevelAuthAppliesWhenNothingMoreSpecificExists() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.API, API_ID, ApiAuthType.BEARER));
		ApiEndpoint endpoint = endpoint(vault, version, null);

		EffectiveAuth effective = new ApiEffectiveAuthResolver(vault).resolve(API_ID, endpoint);

		Assertions.assertEquals(ApiAuthType.BEARER, effective.getAuthType());
		Assertions.assertEquals(ApiOwnerType.API, effective.getSourceOwnerType());
	}

	@Test
	void groupAuthOverridesApiAuthWhenEndpointItselfHasNone() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.API, API_ID, ApiAuthType.BEARER));
		ApiEndpointGroup group = group(vault, version, null);
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.GROUP, String.valueOf(group.getId()), ApiAuthType.BASIC));
		ApiEndpoint endpoint = endpoint(vault, version, group.getId());

		EffectiveAuth effective = new ApiEffectiveAuthResolver(vault).resolve(API_ID, endpoint);

		Assertions.assertEquals(ApiAuthType.BASIC, effective.getAuthType(), "the nearer owner (group) must win over the API");
		Assertions.assertEquals(ApiOwnerType.GROUP, effective.getSourceOwnerType());
	}

	@Test
	void nestedGroupWithNoAuthOfItsOwnInheritsFromItsParentGroup() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.API, API_ID, ApiAuthType.BEARER));
		ApiEndpointGroup parentGroup = group(vault, version, null);
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.GROUP, String.valueOf(parentGroup.getId()), ApiAuthType.BASIC));
		ApiEndpointGroup childGroup = group(vault, version, parentGroup.getId());
		// childGroup has no API_AUTH row at all - "inherit" - must walk up to parentGroup, not to API.
		ApiEndpoint endpoint = endpoint(vault, version, childGroup.getId());

		EffectiveAuth effective = new ApiEffectiveAuthResolver(vault).resolve(API_ID, endpoint);

		Assertions.assertEquals(ApiAuthType.BASIC, effective.getAuthType());
		Assertions.assertEquals(ApiOwnerType.GROUP, effective.getSourceOwnerType());
		Assertions.assertEquals(String.valueOf(parentGroup.getId()), effective.getSourceOwnerId());
	}

	@Test
	void explicitNoneAtEndpointStopsInheritanceEvenThoughApiHasRealAuth() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.API, API_ID, ApiAuthType.BEARER));
		ApiEndpoint endpoint = endpoint(vault, version, null);
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAuthType.NONE));

		EffectiveAuth effective = new ApiEffectiveAuthResolver(vault).resolve(API_ID, endpoint);

		Assertions.assertEquals(ApiAuthType.NONE, effective.getAuthType());
		Assertions.assertEquals(ApiOwnerType.ENDPOINT, effective.getSourceOwnerType(), "must be the endpoint's own explicit NONE, not the default 'nothing found anywhere' NONE");
	}

	@Test
	void explicitNoneAtGroupStopsInheritanceFromApi() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.API, API_ID, ApiAuthType.BEARER));
		ApiEndpointGroup group = group(vault, version, null);
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.GROUP, String.valueOf(group.getId()), ApiAuthType.NONE));
		ApiEndpoint endpoint = endpoint(vault, version, group.getId());

		EffectiveAuth effective = new ApiEffectiveAuthResolver(vault).resolve(API_ID, endpoint);

		Assertions.assertEquals(ApiAuthType.NONE, effective.getAuthType());
		Assertions.assertEquals(ApiOwnerType.GROUP, effective.getSourceOwnerType());
	}

	@Test
	void noAuthConfiguredAnywhereResolvesToDefaultNone() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = endpoint(vault, version, null);

		EffectiveAuth effective = new ApiEffectiveAuthResolver(vault).resolve(API_ID, endpoint);

		Assertions.assertEquals(ApiAuthType.NONE, effective.getAuthType());
		Assertions.assertNull(effective.getSourceOwnerType(), "no row exists anywhere - this is the synthetic default NONE, not any owner's explicit one");
	}

	@Test
	void endpointPropertiesComeFromTheResolvedOwnersAuthRow() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiAuthConfig apiAuth = new ApiAuthConfig(ApiOwnerType.API, API_ID, ApiAuthType.BEARER);
		vault.saveAuth(apiAuth);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.AUTH, String.valueOf(apiAuth.getId()), ApiAttributeKind.PROPERTY, "token", "${token}", true));
		ApiEndpoint endpoint = endpoint(vault, version, null);

		EffectiveAuth effective = new ApiEffectiveAuthResolver(vault).resolve(API_ID, endpoint);

		Assertions.assertEquals("${token}", effective.getProperty("token"));
	}

	private ApiEndpointGroup group(ApiDefinitionsVault vault, ApiVersion version, Integer parentGroupId) throws BroadSQLException {
		ApiEndpointGroup group = new ApiEndpointGroup(version.getId(), parentGroupId, "Group", 0);
		vault.saveEndpointGroup(group);
		return group;
	}

	private ApiEndpoint endpoint(ApiDefinitionsVault vault, ApiVersion version, Integer groupId) throws BroadSQLException {
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), groupId, "Endpoint", "GET", "/resource", 0);
		vault.saveEndpoint(endpoint);
		return endpoint;
	}
}
