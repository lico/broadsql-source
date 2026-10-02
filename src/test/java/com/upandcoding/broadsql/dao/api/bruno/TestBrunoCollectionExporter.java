package com.upandcoding.broadsql.dao.api.bruno;

import java.util.List;
import java.util.Map;

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
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * Unit-level coverage of {@link BrunoCollectionExporter} - hand-built {@link ApiDefinition} trees (no
 * importer involved), asserting the emitted generic {@code Map}/{@code List} tree shape per concept and
 * per {@link ApiAuthType}. The end-to-end import-then-export-then-reimport guarantee is covered
 * separately by {@link TestBrunoRoundTripSemantic}.
 */
@SuppressWarnings("unchecked")
class TestBrunoCollectionExporter {

	private static final String API_ID = "EXPORTTEST";

	@Test
	void exportsApiNameAndEnvironmentsWithSecretsExcludedByDefault() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiDefinition api = new ApiDefinition(API_ID);
		api.setName("Demo API");
		vault.createApiWithDefaultVersion(api);
		ApiEnvironment env = new ApiEnvironment(API_ID, "Production", "https://api.example.com", 0);
		vault.saveEnvironment(env);
		vault.setEnvironmentBaseUrl(env, "https://api.example.com");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "tenant", "acme", false));
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "token", "s3cr3t", true));

		Map<String, Object> root = new BrunoCollectionExporter(vault).exportRoot(API_ID, new BrunoExportOptions(), new BrunoExportResult());

		Assertions.assertEquals("1.0.0", root.get("opencollection"));
		Assertions.assertEquals(Boolean.TRUE, root.get("bundled"));
		Assertions.assertEquals("Demo API", ((Map<String, Object>) root.get("info")).get("name"));

		List<Object> environments = (List<Object>) ((Map<String, Object>) root.get("config")).get("environments");
		Assertions.assertEquals(1, environments.size());
		Map<String, Object> envNode = (Map<String, Object>) environments.get(0);
		Assertions.assertEquals("Production", envNode.get("name"));
		List<Object> vars = (List<Object>) envNode.get("variables");
		Map<String, Object> tokenVar = findByName(vars, "token");
		Assertions.assertFalse(tokenVar.containsKey("value"), "a secret value must be omitted entirely, never an empty string or a fake mask");
		Assertions.assertEquals(Boolean.TRUE, tokenVar.get("secret"));
		Map<String, Object> tenantVar = findByName(vars, "tenant");
		Assertions.assertEquals("acme", tenantVar.get("value"));
		Assertions.assertNull(tenantVar.get("secret"), "a non-secret variable must not carry a spurious secret flag");
	}

	@Test
	void includesSecretValuesOnlyWhenExplicitlyRequested() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = new ApiEnvironment(API_ID, "Production", null, 0);
		vault.saveEnvironment(env);
		vault.setEnvironmentBaseUrl(env, "https://api.example.com");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "token", "s3cr3t", true));

		BrunoExportOptions options = new BrunoExportOptions();
		options.setIncludeSecrets(true);
		Map<String, Object> root = new BrunoCollectionExporter(vault).exportRoot(API_ID, options, new BrunoExportResult());

		List<Object> environments = (List<Object>) ((Map<String, Object>) root.get("config")).get("environments");
		Map<String, Object> tokenVar = findByName((List<Object>) ((Map<String, Object>) environments.get(0)).get("variables"), "token");
		Assertions.assertEquals("s3cr3t", tokenVar.get("value"));
	}

	@Test
	void neverExportsTheEndpointAlias() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Create order", "POST", "/orders", 0);
		endpoint.setAlias("DO_ORDER");
		vault.saveEndpoint(endpoint);

		Map<String, Object> root = new BrunoCollectionExporter(vault).exportRoot(API_ID, new BrunoExportOptions(), new BrunoExportResult());

		String dumped = new org.yaml.snakeyaml.Yaml().dump(root);
		Assertions.assertFalse(dumped.contains("DO_ORDER"), "the BroadSQL-owned alias must never appear anywhere in the exported YAML");
	}

	@Test
	void deNormalizesDollarPlaceholdersBackToMustacheSyntax() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "List users", "GET", "${baseUrl}/${resource}", 0);
		vault.saveEndpoint(endpoint);

		Map<String, Object> root = new BrunoCollectionExporter(vault).exportRoot(API_ID, new BrunoExportOptions(), new BrunoExportResult());
		Map<String, Object> item = (Map<String, Object>) ((List<Object>) root.get("items")).get(0);
		Map<String, Object> http = (Map<String, Object>) item.get("http");
		Assertions.assertEquals("{{baseUrl}}/{{resource}}", http.get("url"), "BroadSQL's ${...} internal syntax must round-trip back to Bruno's {{...}} syntax");
	}

	@Test
	void exportsNestedFoldersAndPreservesHttpMethodAndBody() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpointGroup parent = new ApiEndpointGroup(version.getId(), null, "Orders", 0);
		vault.saveEndpointGroup(parent);
		ApiEndpointGroup child = new ApiEndpointGroup(version.getId(), parent.getId(), "Admin", 0);
		vault.saveEndpointGroup(child);
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), child.getId(), "Create order", "POST", "/orders", 0);
		endpoint.setBodyMode("json");
		endpoint.setBodyContent("{\"name\":\"${orderName}\"}");
		vault.saveEndpoint(endpoint);

		Map<String, Object> root = new BrunoCollectionExporter(vault).exportRoot(API_ID, new BrunoExportOptions(), new BrunoExportResult());
		List<Object> topItems = (List<Object>) root.get("items");
		Assertions.assertEquals(1, topItems.size());
		Map<String, Object> ordersFolder = (Map<String, Object>) topItems.get(0);
		Assertions.assertEquals("folder", ((Map<String, Object>) ordersFolder.get("info")).get("type"));
		Assertions.assertEquals("Orders", ((Map<String, Object>) ordersFolder.get("info")).get("name"));
		List<Object> ordersItems = (List<Object>) ordersFolder.get("items");
		Map<String, Object> adminFolder = (Map<String, Object>) ordersItems.get(0);
		Assertions.assertEquals("Admin", ((Map<String, Object>) adminFolder.get("info")).get("name"));
		List<Object> adminItems = (List<Object>) adminFolder.get("items");
		Map<String, Object> endpointItem = (Map<String, Object>) adminItems.get(0);
		Map<String, Object> http = (Map<String, Object>) endpointItem.get("http");
		Assertions.assertEquals("POST", http.get("method"));
		Map<String, Object> body = (Map<String, Object>) http.get("body");
		Assertions.assertEquals("json", body.get("type"));
		Assertions.assertEquals("{\"name\":\"{{orderName}}\"}", body.get("data"));
	}

	@Test
	void authNodeIsOmittedEntirelyWhenNoAuthRowExists() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveEndpoint(new ApiEndpoint(version.getId(), null, "Get", "GET", "/x", 0));

		Map<String, Object> root = new BrunoCollectionExporter(vault).exportRoot(API_ID, new BrunoExportOptions(), new BrunoExportResult());
		Map<String, Object> item = (Map<String, Object>) ((List<Object>) root.get("items")).get(0);
		Map<String, Object> http = (Map<String, Object>) item.get("http");
		Assertions.assertFalse(http.containsKey("auth"), "no API_AUTH row means 'inherit' - the auth key must be omitted, never a guessed value");
	}

	@Test
	void exportsEveryAuthTypeWithItsExpectedShape() throws BroadSQLException {
		assertAuthShape(ApiAuthType.NONE, List.of(), auth -> Assertions.assertEquals("none", auth.get("type")));

		assertAuthShape(ApiAuthType.BASIC, List.of(
				prop("username", "${user}", false), prop("password", "s3cr3t", true)), auth -> {
					Assertions.assertEquals("basic", auth.get("type"));
					Assertions.assertEquals("{{user}}", auth.get("username"));
					Assertions.assertFalse(auth.containsKey("password"), "secret excluded by default");
				});

		assertAuthShape(ApiAuthType.BEARER, List.of(prop("token", "s3cr3t", true)), auth -> {
			Assertions.assertEquals("bearer", auth.get("type"));
			Assertions.assertFalse(auth.containsKey("token"));
		});

		assertAuthShape(ApiAuthType.API_KEY_HEADER, List.of(prop("name", "X-Api-Key", false), prop("value", "s3cr3t", true)), auth -> {
			Assertions.assertEquals("apikey", auth.get("type"));
			Assertions.assertEquals("header", auth.get("placement"));
			Assertions.assertEquals("X-Api-Key", auth.get("key"));
		});

		assertAuthShape(ApiAuthType.API_KEY_QUERY, List.of(prop("name", "api_key", false), prop("value", "s3cr3t", true)), auth ->
				Assertions.assertEquals("query", auth.get("placement")));

		assertAuthShape(ApiAuthType.OAUTH2_CLIENT_CREDENTIALS, List.of(
				prop("accessTokenUrl", "${baseUrl}/oauth/token", false),
				prop("clientId", "${clientId}", false),
				prop("clientSecret", "s3cr3t", true),
				prop("tokenPlacement", "basic_auth_header", false),
				prop("scope", "admin.read", false)), auth -> {
					Assertions.assertEquals("oauth2", auth.get("type"));
					Assertions.assertEquals("client_credentials", auth.get("flow"));
					Assertions.assertEquals("{{baseUrl}}/oauth/token", auth.get("accessTokenUrl"));
					Map<String, Object> credentials = (Map<String, Object>) auth.get("credentials");
					Assertions.assertEquals("{{clientId}}", credentials.get("clientId"));
					Assertions.assertFalse(credentials.containsKey("clientSecret"));
					Assertions.assertEquals("basic_auth_header", credentials.get("placement"));
					Assertions.assertEquals("admin.read", auth.get("scope"));
				});

		assertAuthShape(ApiAuthType.UNSUPPORTED, List.of(prop("unsupportedSourceAuthType", "digest", false)), auth -> {
			Assertions.assertEquals("digest", auth.get("type"));
		});
	}

	private void assertAuthShape(ApiAuthType type, List<ApiAttribute> properties, java.util.function.Consumer<Map<String, Object>> assertion) throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.API, API_ID, type));
		ApiAuthConfig saved = vault.getAuth(ApiOwnerType.API, API_ID);
		for (ApiAttribute p : properties) {
			vault.saveAttribute(new ApiAttribute(ApiOwnerType.AUTH, String.valueOf(saved.getId()), ApiAttributeKind.PROPERTY, p.getName(), p.getValue(), p.isSecret()));
		}
		BrunoExportResult result = new BrunoExportResult();
		Map<String, Object> root = new BrunoCollectionExporter(vault).exportRoot(API_ID, new BrunoExportOptions(), result);
		Map<String, Object> auth = (Map<String, Object>) ((Map<String, Object>) root.get("request")).get("auth");
		assertion.accept(auth);
		if (type == ApiAuthType.UNSUPPORTED) {
			Assertions.assertTrue(result.hasWarnings(), "an UNSUPPORTED auth type must always surface an export warning");
		}
	}

	private static ApiAttribute prop(String name, String value, boolean secret) {
		return new ApiAttribute(ApiOwnerType.AUTH, null, ApiAttributeKind.PROPERTY, name, value, secret);
	}

	private static Map<String, Object> findByName(List<Object> nodes, String name) {
		for (Object node : nodes) {
			Map<String, Object> map = (Map<String, Object>) node;
			if (name.equals(map.get("name"))) {
				return map;
			}
		}
		throw new AssertionError("no node named '" + name + "' found");
	}
}
