package com.upandcoding.broadsql.dao.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/** SPRINT XT02B, section 6 - the shared endpoint ID/alias/unique-NAME resolver for SHOW ENDPOINT/SYNTAX/HELP. */
class TestApiEndpointReferenceResolver {

	@Test
	void resolvesByNumericIdWithNoApiIdScope() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("DESK"));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/todos", 0);
		vault.saveEndpoint(endpoint);

		ApiEndpointReferenceResolver.Result result = ApiEndpointReferenceResolver.resolve(vault, null, String.valueOf(endpoint.getId()));

		Assertions.assertInstanceOf(ApiEndpointReferenceResolver.Found.class, result);
		Assertions.assertEquals(endpoint.getId(), ((ApiEndpointReferenceResolver.Found) result).endpoint().getId());
	}

	@Test
	void aNumericIdBelongingToAnotherApiIsNotFoundWhenScoped() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion deskVersion = vault.createApiWithDefaultVersion(new ApiDefinition("DESK"));
		ApiEndpoint deskEndpoint = new ApiEndpoint(deskVersion.getId(), null, "findAll", "GET", "${baseUrl}/api/todos", 0);
		vault.saveEndpoint(deskEndpoint);
		vault.createApiWithDefaultVersion(new ApiDefinition("OTHER"));

		ApiEndpointReferenceResolver.Result result = ApiEndpointReferenceResolver.resolve(vault, "OTHER", String.valueOf(deskEndpoint.getId()));

		Assertions.assertInstanceOf(ApiEndpointReferenceResolver.NotFound.class, result);
	}

	@Test
	void resolvesByAlias() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("DESK"));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/todos", 0);
		endpoint.setAlias("PINGMAIL");
		vault.saveEndpoint(endpoint);

		ApiEndpointReferenceResolver.Result result = ApiEndpointReferenceResolver.resolve(vault, "DESK", "PINGMAIL");

		Assertions.assertInstanceOf(ApiEndpointReferenceResolver.Found.class, result);
	}

	@Test
	void resolvesByUniqueName() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("DESK"));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/todos", 0);
		vault.saveEndpoint(endpoint);

		ApiEndpointReferenceResolver.Result result = ApiEndpointReferenceResolver.resolve(vault, "DESK", "findAll");

		Assertions.assertInstanceOf(ApiEndpointReferenceResolver.Found.class, result);
	}

	@Test
	void aDuplicateNameIsAmbiguousNotGuessed() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("DESK"));
		vault.saveEndpoint(new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/customers", 0));
		vault.saveEndpoint(new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/orders", 1));

		ApiEndpointReferenceResolver.Result result = ApiEndpointReferenceResolver.resolve(vault, "DESK", "findAll");

		Assertions.assertInstanceOf(ApiEndpointReferenceResolver.Ambiguous.class, result);
		Assertions.assertEquals(2, ((ApiEndpointReferenceResolver.Ambiguous) result).candidates().size());
	}

	@Test
	void anAliasOrNameWithNoApiIdScopeIsNotFound() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("DESK"));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/todos", 0);
		endpoint.setAlias("PINGMAIL");
		vault.saveEndpoint(endpoint);

		Assertions.assertInstanceOf(ApiEndpointReferenceResolver.NotFound.class,
				ApiEndpointReferenceResolver.resolve(vault, null, "PINGMAIL"));
	}

	@Test
	void unmatchedTokenIsNotFound() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition("DESK"));

		Assertions.assertInstanceOf(ApiEndpointReferenceResolver.NotFound.class,
				ApiEndpointReferenceResolver.resolve(vault, "DESK", "DOES_NOT_EXIST"));
	}

	@Test
	void renderAmbiguousIncludesEveryCandidatesIdAndTheUseIdOrAliasHint() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("DESK"));
		ApiEndpoint first = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/customers", 0);
		vault.saveEndpoint(first);
		ApiEndpoint second = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/orders", 1);
		vault.saveEndpoint(second);

		String rendered = ApiEndpointReferenceResolver.renderAmbiguous(vault, java.util.List.of(first, second));

		Assertions.assertTrue(rendered.contains(String.valueOf(first.getId())), rendered);
		Assertions.assertTrue(rendered.contains(String.valueOf(second.getId())), rendered);
		Assertions.assertTrue(rendered.toLowerCase().contains("use the id or alias"), rendered);
	}
}
