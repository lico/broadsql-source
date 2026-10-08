package com.upandcoding.broadsql.dao.api.bruno;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

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
 * The sub-sprint's two mandatory round-trip acceptance scenarios (docs/SPRINT XT02-sub sprint 5 - API
 * Configuration GUI + Bruno YAML Round-trip.md, sections 39/40) - deliberately <b>semantic</b>, never
 * byte-for-byte YAML comparison (see {@link ApiDefinitionAssertions}):
 *
 * <ul>
 * <li>Scenario A: import the bundled realistic fixture -&gt; export -&gt; import the exported file into a
 * genuinely separate, fresh {@link ApiDefinitionsVault} (a different physical CDF temp file, not just a
 * re-{@code load()} of the same one) -&gt; assert the two APIs are semantically equal.</li>
 * <li>Scenario B: build an API purely via {@code save*} calls, no importer involved at all -&gt; export
 * -&gt; fresh-import -&gt; assert equal. Proves the exporter has no hidden dependency on importer-only state
 * (e.g. {@code sourceType}/{@code sourceKey}) - a manually-configured API must export and re-import just
 * as faithfully as an imported one.</li>
 * </ul>
 */
class TestBrunoRoundTripSemantic {

	@Test
	void scenarioA_importFixtureExportThenReimportIntoAFreshVaultIsSemanticallyEqual() throws BroadSQLException, IOException {
		ApiDefinitionsVault vaultA = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vaultA).importFile("DEMO", fixture("sample-collection.yml"));

		// includeSecrets=true: this scenario's goal is to prove full structural fidelity of the
		// configuration model end to end. The dedicated "excluded by default" secret policy already has
		// its own coverage (TestBrunoCollectionExporter#exportsApiNameAndEnvironmentsWithSecretsExcludedByDefault) -
		// with the default (false) here, every secret variable/property would legitimately come back
		// null after re-import (the value was never written), which is correct exporter behavior, not
		// something this semantic-equality check should have to special-case around.
		BrunoExportOptions options = new BrunoExportOptions();
		options.setIncludeSecrets(true);
		File exported = newTempYamlFile();
		BrunoExportResult exportResult = new BrunoCollectionExporter(vaultA).exportToFile("DEMO", exported, options);
		Assertions.assertFalse(exportResult.hasWarnings(), "the realistic fixture uses only supported auth types - no export warning expected: " + exportResult);

		// A genuinely separate vault instance backed by a different physical CDF temp file, per this
		// scenario's own requirement - not merely a second load() of vaultA's file.
		ApiDefinitionsVault vaultB = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vaultB).importFile("DEMO_REIMPORTED", exported);

		ApiDefinitionAssertions.assertSemanticallyEqual(vaultA, "DEMO", vaultB, "DEMO_REIMPORTED");
	}

	@Test
	void scenarioB_manuallyBuiltApiExportsAndReimportsSemanticallyEqualWithNoImporterInvolved() throws BroadSQLException, IOException {
		ApiDefinitionsVault vaultA = TestApiDefinitionsVaults.newFileBackedVault();
		ApiDefinition api = new ApiDefinition("MANUAL");
		api.setName("Manually Configured API");
		ApiVersion version = vaultA.createApiWithDefaultVersion(api);

		ApiEnvironment env = new ApiEnvironment("MANUAL", "Production", null, 0);
		vaultA.saveEnvironment(env);
		vaultA.setEnvironmentBaseUrl(env, "https://api.example.com");
		vaultA.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "tenant", "acme", false));

		vaultA.saveAuth(new ApiAuthConfig(ApiOwnerType.API, "MANUAL", ApiAuthType.BEARER));
		ApiAuthConfig apiAuth = vaultA.getAuth(ApiOwnerType.API, "MANUAL");
		vaultA.saveAttribute(new ApiAttribute(ApiOwnerType.AUTH, String.valueOf(apiAuth.getId()), ApiAttributeKind.PROPERTY, "token", "${token}", true));

		ApiEndpointGroup group = new ApiEndpointGroup(version.getId(), null, "Orders", 0);
		vaultA.saveEndpointGroup(group);

		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), group.getId(), "Create order", "POST", "${baseUrl}/orders", 0);
		endpoint.setBodyMode("json");
		endpoint.setBodyContent("{\"tenant\":\"${tenant}\"}");
		vaultA.saveEndpoint(endpoint);
		String endpointOwnerId = String.valueOf(endpoint.getId());
		vaultA.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, endpointOwnerId, ApiAttributeKind.HEADER, "Content-Type", "application/json", false));
		vaultA.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, endpointOwnerId, ApiAttributeKind.QUERY_PARAMETER, "dryRun", "true", false));

		BrunoExportOptions options = new BrunoExportOptions();
		options.setIncludeSecrets(true); // see scenario A's comment on why this scenario tests full-fidelity round-trip, secrets included
		File exported = newTempYamlFile();
		new BrunoCollectionExporter(vaultA).exportToFile("MANUAL", exported, options);

		ApiDefinitionsVault vaultB = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vaultB).importFile("MANUAL_REIMPORTED", exported);

		ApiDefinitionAssertions.assertSemanticallyEqual(vaultA, "MANUAL", vaultB, "MANUAL_REIMPORTED");
	}

	@Test
	void anEndpointAliasDoesNotSurviveExportIntoAFreshInstallation() throws BroadSQLException, IOException {
		// The amendment's explicit, documented limitation (section 11): alias is BroadSQL-owned local
		// metadata, never written to standard Bruno YAML, so it cannot survive a trip through a fresh
		// installation - only through re-import into the SAME vault (already covered by
		// TestBrunoAliasSurvival).
		ApiDefinitionsVault vaultA = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vaultA.createApiWithDefaultVersion(new ApiDefinition("ALIASROUNDTRIP"));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Create order", "POST", "/orders", 0);
		endpoint.setAlias("DO_ORDER");
		vaultA.saveEndpoint(endpoint);

		File exported = newTempYamlFile();
		new BrunoCollectionExporter(vaultA).exportToFile("ALIASROUNDTRIP", exported, new BrunoExportOptions());

		ApiDefinitionsVault vaultB = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vaultB).importFile("ALIASROUNDTRIP2", exported);

		ApiVersion versionB = vaultB.getDefaultVersion("ALIASROUNDTRIP2");
		ApiEndpoint reimported = vaultB.getEndpointsForVersion(versionB.getId()).get(0);
		Assertions.assertNull(reimported.getAlias(), "a fresh installation must never inherit an alias it never assigned");
	}

	// ------------------------------------------------------------------------------------------
	// API Quality and UX Consolidation sprint: a url with a query string embedded literally,
	// alongside structured params covering part of it, must not accumulate duplication across
	// repeated import/export cycles.
	// ------------------------------------------------------------------------------------------

	@Test
	void aQueryStringEmbeddedInTheUrlAlongsideStructuredParamsDoesNotDuplicateAcrossExportAndReimport() throws BroadSQLException, IOException {
		ApiDefinitionsVault vaultA = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vaultA).importFile("QS", fixture("query-string-duplication.yml"));
		ApiVersion versionA = vaultA.getDefaultVersion("QS");
		ApiEndpoint productsA = vaultA.getEndpointsForVersion(versionA.getId()).get(0);
		Assertions.assertEquals("${baseUrl}/products?type=widget", productsA.getEndpointPath(),
				"import must already have stripped the params covered structurally ('expand', 'limit'), keeping only the uncovered 'type'");

		File exported = newTempYamlFile();
		new BrunoCollectionExporter(vaultA).exportToFile("QS", exported, new BrunoExportOptions());

		ApiDefinitionsVault vaultB = TestApiDefinitionsVaults.newFileBackedVault();
		new BrunoCollectionImporter(vaultB).importFile("QS_REIMPORTED", exported);
		ApiVersion versionB = vaultB.getDefaultVersion("QS_REIMPORTED");
		ApiEndpoint productsB = vaultB.getEndpointsForVersion(versionB.getId()).get(0);

		Assertions.assertEquals(productsA.getEndpointPath(), productsB.getEndpointPath(),
				"a full export/reimport cycle must not reintroduce or accumulate duplicated query text");
		List<ApiAttribute> paramsB = vaultB.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(productsB.getId()), ApiAttributeKind.QUERY_PARAMETER);
		Assertions.assertEquals(2, paramsB.size(), "exactly 'expand' and 'limit' - re-importing must not duplicate or drop structured query parameters");

		ApiDefinitionAssertions.assertSemanticallyEqual(vaultA, "QS", vaultB, "QS_REIMPORTED");
	}

	private File fixture(String name) throws BroadSQLException {
		try {
			return new File(getClass().getResource("/bruno/" + name).toURI());
		} catch (URISyntaxException | NullPointerException e) {
			throw new BroadSQLException("Missing test fixture '" + name + "'", e);
		}
	}

	private File newTempYamlFile() throws IOException {
		File file = File.createTempFile("bruno_export_" + UUID.randomUUID(), ".yml");
		file.deleteOnExit();
		Files.deleteIfExists(file.toPath());
		return file;
	}
}
