package com.upandcoding.broadsql.dao.api;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * Covers {@link ApiVariableResolver}'s precedence chain - docs/SPRINT XT02 - Universal API Client.md,
 * section 13.4: API -> environment -> group chain (root to leaf) -> endpoint -> runtime overrides, each
 * more specific scope shadowing the same variable name at a less specific one, disabled variables never
 * contributing.
 */
class TestApiVariableResolver {

	@Test
	void mostSpecificScopeWinsAtEveryLevelOfThePrecedenceChain() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("JIRA"));

		vault.saveAttribute(new ApiAttribute(ApiOwnerType.API, "JIRA", ApiAttributeKind.VARIABLE, "region", "api-level", false));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.API, "JIRA", ApiAttributeKind.VARIABLE, "onlyAtApi", "api-only", false));

		ApiEnvironment env = new ApiEnvironment("JIRA", "Production", "https://api.example.com", 0);
		vault.saveEnvironment(env);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "region", "env-level", false));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "onlyAtEnv", "env-only", false));

		ApiEndpointGroup parentGroup = new ApiEndpointGroup(version.getId(), null, "Issues", 0);
		vault.saveEndpointGroup(parentGroup);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.GROUP, String.valueOf(parentGroup.getId()), ApiAttributeKind.VARIABLE, "region", "parent-group-level", false));

		ApiEndpointGroup childGroup = new ApiEndpointGroup(version.getId(), parentGroup.getId(), "Search", 0);
		vault.saveEndpointGroup(childGroup);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.GROUP, String.valueOf(childGroup.getId()), ApiAttributeKind.VARIABLE, "region", "child-group-level", false));

		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), childGroup.getId(), "Search issues", "GET", "/search", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.VARIABLE, "region", "endpoint-level", false));

		ApiVariableResolver resolver = new ApiVariableResolver(vault);
		Map<String, String> resolved = resolver.resolve("JIRA", env, endpoint, Map.of("region", "runtime-override"));

		Assertions.assertEquals("runtime-override", resolved.get("region"), "a runtime override must win over every persisted scope");
		Assertions.assertEquals("api-only", resolved.get("onlyAtApi"), "a variable defined only at the API scope must still resolve");
		Assertions.assertEquals("env-only", resolved.get("onlyAtEnv"), "a variable defined only at the environment scope must still resolve");
	}

	@Test
	void childGroupVariableOverridesParentGroupVariableWithoutARuntimeOverride() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("JIRA"));

		ApiEndpointGroup parentGroup = new ApiEndpointGroup(version.getId(), null, "Issues", 0);
		vault.saveEndpointGroup(parentGroup);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.GROUP, String.valueOf(parentGroup.getId()), ApiAttributeKind.VARIABLE, "tenant", "parent", false));

		ApiEndpointGroup childGroup = new ApiEndpointGroup(version.getId(), parentGroup.getId(), "Search", 0);
		vault.saveEndpointGroup(childGroup);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.GROUP, String.valueOf(childGroup.getId()), ApiAttributeKind.VARIABLE, "tenant", "child", false));

		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), childGroup.getId(), "Search issues", "GET", "/search", 0);
		vault.saveEndpoint(endpoint);

		Map<String, String> resolved = new ApiVariableResolver(vault).resolve("JIRA", null, endpoint, null);

		Assertions.assertEquals("child", resolved.get("tenant"), "the endpoint's own (leaf) folder must override an ancestor folder's variable of the same name");
	}

	@Test
	void disabledVariablesNeverContributeToTheResolvedMap() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition("JIRA"));

		ApiAttribute disabled = new ApiAttribute(ApiOwnerType.API, "JIRA", ApiAttributeKind.VARIABLE, "flag", "should-not-appear", false);
		disabled.setEnabled(false);
		vault.saveAttribute(disabled);

		Map<String, String> resolved = new ApiVariableResolver(vault).resolve("JIRA", null, null, null);

		Assertions.assertFalse(resolved.containsKey("flag"), "a disabled attribute must never appear in the resolved variable map");
	}
}
