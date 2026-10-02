package com.upandcoding.broadsql.dao.api.bruno;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * Covers the alias-persistence guarantees from docs/Amendment - Endpoint Aliases and Future
 * Scriptability.md, section 12: a manually-assigned alias survives save/reload, survives a Bruno
 * re-import that updates the endpoint's other imported fields, a conflicting alias is refused with the
 * exact wording the amendment specifies, and deleting the endpoint frees its alias for reuse.
 *
 * <p>Uses small inline YAML documents fed through the package-private {@link BrunoCollectionImporter#importRoot}
 * (same idiom as {@link TestBrunoImportIdentity}) since these tests exercise the alias mechanism, not the
 * realistic-fixture import shape already covered by {@link TestBrunoCollectionImporter}.
 */
class TestBrunoAliasSurvival {

	private static final String API_ID = "ALIAS_TEST";

	private static final String BASE = """
			opencollection: "1.0.0"
			bundled: true
			info:
			  name: Alias Test API
			items:
			  - info:
			      name: Create Order
			      type: http
			      seq: 1
			    http:
			      method: POST
			      url: "/api/rest/order"
			""";

	@Test
	void manualAliasSurvivesSaveAndReload() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Create order", "POST", "/orders", 0);
		endpoint.setAlias("DO_ORDER");
		vault.saveEndpoint(endpoint);

		ApiDefinitionsVault reopened = new ApiDefinitionsVault(vault.getFileName(), vault.getPassword());
		reopened.load();
		ApiEndpoint reloaded = reopened.findEndpointById(endpoint.getId());
		Assertions.assertEquals("DO_ORDER", reloaded.getAlias());
	}

	@Test
	void aliasSurvivesBrunoReimportThatUpdatesOtherImportedFields() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		BrunoCollectionImporter importer = new BrunoCollectionImporter(vault);
		importer.importRoot(API_ID, load(BASE), "inline-test");

		ApiVersion version = vault.getDefaultVersion(API_ID);
		ApiEndpoint created = vault.getEndpointsForVersion(version.getId()).get(0);
		created.setAlias("DO_ORDER");
		vault.saveEndpoint(created);

		// Re-import a modified Bruno definition: same (method, path) identity per the corrected import
		// algorithm's endpoint source key (method never changes here, only path/name) - wait, this
		// fixture actually changes BOTH name and path, which the import-identity algorithm treats as
		// ambiguous (a new endpoint, per TestBrunoImportIdentity) - so this scenario instead keeps the
		// method/path stable and only changes the display name, which is what a real "same request,
		// renamed" re-import looks like.
		importer.importRoot(API_ID, load(RENAMED_KEEPING_PATH), "inline-test");

		ApiEndpoint updated = vault.findEndpointById(created.getId());
		Assertions.assertEquals("Create Customer Order", updated.getName(), "the display name must update on re-import");
		Assertions.assertEquals("DO_ORDER", updated.getAlias(), "the alias must survive the re-import untouched");
	}

	private static final String RENAMED_KEEPING_PATH = """
			opencollection: "1.0.0"
			bundled: true
			info:
			  name: Alias Test API
			items:
			  - info:
			      name: Create Customer Order
			      type: http
			      seq: 1
			    http:
			      method: POST
			      url: "/api/rest/order"
			""";

	@Test
	void conflictingAliasIsRefusedWithTheExactAmendmentWording() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint first = new ApiEndpoint(version.getId(), null, "Create order", "POST", "/orders", 0);
		first.setAlias("DO_ORDER");
		vault.saveEndpoint(first);

		ApiEndpoint second = new ApiEndpoint(version.getId(), null, "Legacy create order", "POST", "/legacy/orders", 1);
		second.setAlias("DO_ORDER");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.saveEndpoint(second));
		Assertions.assertEquals("Alias DO_ORDER is already assigned to another API endpoint.", ex.getMessage());
	}

	@Test
	void deletingTheEndpointFreesItsAliasForReuse() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint first = new ApiEndpoint(version.getId(), null, "Create order", "POST", "/orders", 0);
		first.setAlias("DO_ORDER");
		vault.saveEndpoint(first);

		vault.deactivateEndpoint(first.getId());
		vault.hardDeleteEndpoint(first.getId());

		ApiEndpoint second = new ApiEndpoint(version.getId(), null, "Create order v2", "POST", "/v2/orders", 1);
		second.setAlias("DO_ORDER");
		vault.saveEndpoint(second); // must not throw

		Assertions.assertEquals(second.getId(), vault.findEndpointByAlias(API_ID, "DO_ORDER").getId());
	}

	private static java.util.Map<String, Object> load(String yaml) {
		return BrunoYamlUtil.asMap(new Yaml().load(yaml));
	}
}
