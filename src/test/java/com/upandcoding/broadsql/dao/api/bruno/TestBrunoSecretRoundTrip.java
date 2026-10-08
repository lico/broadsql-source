package com.upandcoding.broadsql.dao.api.bruno;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * SPRINT XT02 verification finding 6 (HIGH, a real credential-leak bug): {@code BrunoCollectionImporter}'s
 * {@code mapHeaderOrParam} used to hardcode {@code secret=false} regardless of the node's actual
 * {@code secret} field, and {@code BrunoCollectionExporter}'s {@code paramNode} (query/path parameters)
 * neither honored {@code includeSecrets} nor ever wrote a {@code secret} flag at all - unlike
 * {@code attributeNode}, which both variables and headers already went through correctly. Together this
 * meant: (1) a secret header/parameter imported from Bruno silently lost its secret status, so a later
 * "export without secrets" would include its value in the clear after a round trip; (2) a secret query/
 * path parameter leaked its value on <em>every</em> export, even without a round trip, regardless of
 * {@code includeSecrets}.
 *
 * <p>Uses synthetic-looking secret values only (e.g. {@code "synthetic-test-token-123"}), never anything
 * resembling a real credential, per this remediation's own constraints.
 */
class TestBrunoSecretRoundTrip {

	private static final String SYNTHETIC_SECRET = "synthetic-test-token-123";

	// ------------------------------------------------------------------------------------------
	// Import: a secret-flagged header/query/path parameter node must import as secret.
	// ------------------------------------------------------------------------------------------

	@Test
	void aSecretFlaggedHeaderNodeImportsAsSecret() throws BroadSQLException {
		Map<String, Object> root = rootWithOneHttpItem(Map.of(
				"method", "GET",
				"url", "/thing",
				"headers", List.of(Map.of("name", "X-Api-Key", "value", SYNTHETIC_SECRET, "secret", true))));

		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vault).importRoot("SECRETHDR", root, "test");

		ApiAttribute header = onlyAttribute(vault, "SECRETHDR", ApiAttributeKind.HEADER);
		Assertions.assertTrue(header.isSecret(), "a header node with secret: true must import with isSecret()==true");
		Assertions.assertEquals(SYNTHETIC_SECRET, header.getValue());
	}

	@Test
	void aNonSecretHeaderNodeStillImportsAsNonSecret() throws BroadSQLException {
		Map<String, Object> root = rootWithOneHttpItem(Map.of(
				"method", "GET",
				"url", "/thing",
				"headers", List.of(Map.of("name", "Accept", "value", "application/json"))));

		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vault).importRoot("PLAINHDR", root, "test");

		ApiAttribute header = onlyAttribute(vault, "PLAINHDR", ApiAttributeKind.HEADER);
		Assertions.assertFalse(header.isSecret(), "an ordinary header must never be marked secret");
	}

	@Test
	void aSecretFlaggedQueryParameterNodeImportsAsSecret() throws BroadSQLException {
		Map<String, Object> root = rootWithOneHttpItem(Map.of(
				"method", "GET",
				"url", "/thing",
				"params", List.of(Map.of("name", "api_key", "value", SYNTHETIC_SECRET, "type", "query", "secret", true))));

		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vault).importRoot("SECRETQP", root, "test");

		ApiAttribute param = onlyAttribute(vault, "SECRETQP", ApiAttributeKind.QUERY_PARAMETER);
		Assertions.assertTrue(param.isSecret(), "a query parameter node with secret: true must import with isSecret()==true");
	}

	@Test
	void aSecretFlaggedPathParameterNodeImportsAsSecret() throws BroadSQLException {
		Map<String, Object> root = rootWithOneHttpItem(Map.of(
				"method", "GET",
				"url", "/thing/:tenant",
				"params", List.of(Map.of("name", "tenant", "value", SYNTHETIC_SECRET, "type", "path", "secret", true))));

		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vault).importRoot("SECRETPP", root, "test");

		ApiAttribute param = onlyAttribute(vault, "SECRETPP", ApiAttributeKind.PATH_PARAMETER);
		Assertions.assertTrue(param.isSecret(), "a path parameter node with secret: true must import with isSecret()==true");
	}

	// ------------------------------------------------------------------------------------------
	// Export: a secret query/path parameter must never leak its value regardless of round trip.
	// ------------------------------------------------------------------------------------------

	@Test
	void aSecretQueryParameterNeverLeaksItsValueWhenExportedWithoutIncludeSecrets() throws BroadSQLException, IOException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("QPLEAK"));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Get thing", "GET", "/thing", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER,
				"api_key", SYNTHETIC_SECRET, true));

		File exported = newTempYamlFile();
		// Default BrunoExportOptions: includeSecrets=false.
		new BrunoCollectionExporter(vault).exportToFile("QPLEAK", exported, new BrunoExportOptions());

		String content = Files.readString(exported.toPath());
		Assertions.assertFalse(content.contains(SYNTHETIC_SECRET),
				"a secret query parameter's value must never appear in an export with includeSecrets=false, "
						+ "even without any round trip - previously paramNode wrote it unconditionally");
		Assertions.assertTrue(content.contains("secret: true") || content.contains("secret: 'true'") || content.contains("secret: \"true\""),
				"the secret flag itself must still be written so the parameter round-trips as secret: " + content);
	}

	@Test
	void aSecretQueryParameterValueIsIncludedWhenIncludeSecretsIsExplicitlyTrue() throws BroadSQLException, IOException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("QPINCLUDE"));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Get thing", "GET", "/thing", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER,
				"api_key", SYNTHETIC_SECRET, true));

		BrunoExportOptions options = new BrunoExportOptions();
		options.setIncludeSecrets(true);
		File exported = newTempYamlFile();
		new BrunoCollectionExporter(vault).exportToFile("QPINCLUDE", exported, options);

		String content = Files.readString(exported.toPath());
		Assertions.assertTrue(content.contains(SYNTHETIC_SECRET), "an explicit includeSecrets=true export may include the real value");
	}

	// ------------------------------------------------------------------------------------------
	// The audit's own repro: import secret header -> export with secrets -> re-import into a fresh
	// catalog -> export with secrets excluded -> the secret must never survive unredacted.
	// ------------------------------------------------------------------------------------------

	@Test
	void secretHeaderNeverLeaksInARedactedExportAfterARoundTrip() throws BroadSQLException, IOException {
		ApiDefinitionsVault vaultA = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vaultA.createApiWithDefaultVersion(new ApiDefinition("SECRETRT"));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Get thing", "GET", "/thing", 0);
		vaultA.saveEndpoint(endpoint);
		vaultA.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.HEADER,
				"X-Api-Key", SYNTHETIC_SECRET, true));

		BrunoExportOptions withSecrets = new BrunoExportOptions();
		withSecrets.setIncludeSecrets(true);
		File exportedWithSecrets = newTempYamlFile();
		new BrunoCollectionExporter(vaultA).exportToFile("SECRETRT", exportedWithSecrets, withSecrets);
		Assertions.assertTrue(Files.readString(exportedWithSecrets.toPath()).contains(SYNTHETIC_SECRET),
				"sanity check: an explicit includeSecrets=true export must actually contain the value");

		// Re-import into a genuinely fresh catalog - a different physical CDF, exactly like the audit's repro.
		ApiDefinitionsVault vaultB = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vaultB).importFile("SECRETRT2", exportedWithSecrets);

		ApiAttribute reimportedHeader = onlyAttribute(vaultB, "SECRETRT2", ApiAttributeKind.HEADER);
		Assertions.assertTrue(reimportedHeader.isSecret(),
				"the header must still be flagged secret after re-import - this is the root cause the fix addresses");

		File redactedExport = newTempYamlFile();
		new BrunoCollectionExporter(vaultB).exportToFile("SECRETRT2", redactedExport, new BrunoExportOptions()); // default includeSecrets=false

		String redactedContent = Files.readString(redactedExport.toPath());
		Assertions.assertFalse(redactedContent.contains(SYNTHETIC_SECRET),
				"SPRINT XT02 verification finding 6 (SECRET_LEAK_AFTER_ROUNDTRIP): a secret header's value must never "
						+ "survive a redacted export after a round trip through re-import");
	}

	// ------------------------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private Map<String, Object> rootWithOneHttpItem(Map<String, Object> http) {
		return Map.of(
				"info", Map.of("name", "Secret Attribute Test API"),
				"items", List.of(Map.of(
						"info", Map.of("name", "Get thing", "type", "http", "seq", 1),
						"http", http)));
	}

	private ApiAttribute onlyAttribute(ApiDefinitionsVault vault, String apiId, ApiAttributeKind kind) throws BroadSQLException {
		ApiVersion version = vault.getDefaultVersion(apiId);
		ApiEndpoint endpoint = vault.getEndpointsForVersion(version.getId()).get(0);
		List<ApiAttribute> attrs = vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), kind);
		Assertions.assertEquals(1, attrs.size(), "expected exactly one " + kind + " attribute, got: " + attrs);
		return attrs.get(0);
	}

	private File newTempYamlFile() throws IOException {
		File file = File.createTempFile("bruno_secret_rt_" + UUID.randomUUID(), ".yml");
		file.deleteOnExit();
		Files.deleteIfExists(file.toPath());
		return file;
	}
}
