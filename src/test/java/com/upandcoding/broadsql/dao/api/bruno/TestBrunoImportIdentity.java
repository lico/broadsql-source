package com.upandcoding.broadsql.dao.api.bruno;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * Covers the corrected import-identity algorithm (docs/SPRINT XT02 - Universal API Client.md, section
 * 18.1): {@code seq} is sort order only, never identity; an endpoint's identity is its
 * {@code (parent folder, method, normalized path)}, which survives a rename or a reorder but - correctly
 * - cannot relate a source item to a prior one when both the method/path *and* the name change at once,
 * in which case a new endpoint is created and the unrelated existing one is left untouched rather than
 * risking an incorrect overwrite.
 *
 * <p>Uses small inline YAML documents (not the full realistic fixture) since these tests exercise one
 * mechanical property of the identity algorithm at a time - the realistic-fixture requirement is already
 * covered by {@link TestBrunoCollectionImporter} against the real Bruno-shaped collection.
 */
class TestBrunoImportIdentity {

	private static final String API_ID = "IDENTITY_TEST";

	private static final String BASE = """
			opencollection: "1.0.0"
			bundled: true
			info:
			  name: Identity Test API
			items:
			  - info:
			      name: Get User
			      type: http
			      seq: 1
			    http:
			      method: GET
			      url: "/users/{{id}}"
			  - info:
			      name: Search Users
			      type: http
			      seq: 2
			    http:
			      method: GET
			      url: "/users"
			""";

	private static final String REORDERED = """
			opencollection: "1.0.0"
			bundled: true
			info:
			  name: Identity Test API
			items:
			  - info:
			      name: Search Users
			      type: http
			      seq: 1
			    http:
			      method: GET
			      url: "/users"
			  - info:
			      name: Get User
			      type: http
			      seq: 2
			    http:
			      method: GET
			      url: "/users/{{id}}"
			""";

	private static final String RENAMED = """
			opencollection: "1.0.0"
			bundled: true
			info:
			  name: Identity Test API
			items:
			  - info:
			      name: Fetch User Detail
			      type: http
			      seq: 1
			    http:
			      method: GET
			      url: "/users/{{id}}"
			  - info:
			      name: Search Users
			      type: http
			      seq: 2
			    http:
			      method: GET
			      url: "/users"
			""";

	private static final String RENAMED_AND_REORDERED = """
			opencollection: "1.0.0"
			bundled: true
			info:
			  name: Identity Test API
			items:
			  - info:
			      name: Search Users
			      type: http
			      seq: 1
			    http:
			      method: GET
			      url: "/users"
			  - info:
			      name: Fetch User Detail
			      type: http
			      seq: 2
			    http:
			      method: GET
			      url: "/users/{{id}}"
			""";

	private static final String AMBIGUOUS_MUTATION = """
			opencollection: "1.0.0"
			bundled: true
			info:
			  name: Identity Test API
			items:
			  - info:
			      name: Create Order
			      type: http
			      seq: 1
			    http:
			      method: POST
			      url: "/orders"
			  - info:
			      name: Search Users
			      type: http
			      seq: 2
			    http:
			      method: GET
			      url: "/users"
			""";

	@Test
	void reorderingWithoutAnyOtherChangeUpdatesTheSameEndpointsAndNeverDuplicates() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		BrunoCollectionImporter importer = new BrunoCollectionImporter(vault);
		importer.importRoot(API_ID, load(BASE), "inline-test");
		ApiVersion version = vault.getDefaultVersion(API_ID);
		ApiEndpoint getUserBefore = findEndpoint(vault, version, "Get User");
		ApiEndpoint searchUsersBefore = findEndpoint(vault, version, "Search Users");

		BrunoImportResult reorderResult = importer.importRoot(API_ID, load(REORDERED), "inline-test");

		Assertions.assertEquals(0, reorderResult.getEndpointsCreated(), "reordering must never be seen as new endpoints");
		Assertions.assertEquals(2, reorderResult.getEndpointsUpdated());
		List<ApiEndpoint> endpointsAfter = vault.getEndpointsForVersion(version.getId());
		Assertions.assertEquals(2, endpointsAfter.size(), "reordering must never duplicate");

		ApiEndpoint getUserAfter = findEndpoint(vault, version, "Get User");
		ApiEndpoint searchUsersAfter = findEndpoint(vault, version, "Search Users");
		Assertions.assertEquals(getUserBefore.getId(), getUserAfter.getId(), "the same BroadSQL identity must be retained across a pure reorder");
		Assertions.assertEquals(searchUsersBefore.getId(), searchUsersAfter.getId(), "the same BroadSQL identity must be retained across a pure reorder");
		Assertions.assertEquals(1, searchUsersAfter.getSortOrder(), "sort order must reflect the new seq");
		Assertions.assertEquals(2, getUserAfter.getSortOrder(), "sort order must reflect the new seq");
	}

	@Test
	void renamingWithoutChangingMethodOrPathUpdatesTheSameEndpoint() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		BrunoCollectionImporter importer = new BrunoCollectionImporter(vault);
		importer.importRoot(API_ID, load(BASE), "inline-test");
		ApiVersion version = vault.getDefaultVersion(API_ID);
		ApiEndpoint getUserBefore = findEndpoint(vault, version, "Get User");

		BrunoImportResult renameResult = importer.importRoot(API_ID, load(RENAMED), "inline-test");

		Assertions.assertEquals(0, renameResult.getEndpointsCreated(), "a rename with the same method/path must update, never create");
		Assertions.assertEquals(2, renameResult.getEndpointsUpdated());
		List<ApiEndpoint> endpointsAfter = vault.getEndpointsForVersion(version.getId());
		Assertions.assertEquals(2, endpointsAfter.size());
		Assertions.assertTrue(endpointsAfter.stream().noneMatch(e -> "Get User".equals(e.getName())), "the old name must be gone");
		ApiEndpoint renamed = findEndpoint(vault, version, "Fetch User Detail");
		Assertions.assertEquals(getUserBefore.getId(), renamed.getId(), "seq is not identity, and neither is the display name - method+path is, so the rename must resolve to the same row");
	}

	@Test
	void renamingAndReorderingTogetherStillUpdatesTheSameEndpointWhenMethodAndPathStayDeterministic() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		BrunoCollectionImporter importer = new BrunoCollectionImporter(vault);
		importer.importRoot(API_ID, load(BASE), "inline-test");
		ApiVersion version = vault.getDefaultVersion(API_ID);
		ApiEndpoint getUserBefore = findEndpoint(vault, version, "Get User");

		BrunoImportResult result = importer.importRoot(API_ID, load(RENAMED_AND_REORDERED), "inline-test");

		Assertions.assertEquals(0, result.getEndpointsCreated(), "method/path alone is enough to identify the object even when both name and order change simultaneously");
		Assertions.assertEquals(2, result.getEndpointsUpdated());
		List<ApiEndpoint> endpointsAfter = vault.getEndpointsForVersion(version.getId());
		Assertions.assertEquals(2, endpointsAfter.size());
		ApiEndpoint renamed = findEndpoint(vault, version, "Fetch User Detail");
		Assertions.assertEquals(getUserBefore.getId(), renamed.getId());
	}

	@Test
	void ambiguousMutationOfBothIdentityAndNameCreatesANewEndpointRatherThanRiskingAWrongOverwrite() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		BrunoCollectionImporter importer = new BrunoCollectionImporter(vault);
		importer.importRoot(API_ID, load(BASE), "inline-test");
		ApiVersion version = vault.getDefaultVersion(API_ID);
		ApiEndpoint getUserBefore = findEndpoint(vault, version, "Get User");

		// "Get User" (GET /users/{{id}}) disappears from this source entirely; "Create Order"
		// (POST /orders) shares no method, path, or name with anything previously imported - there is no
		// stable signal of any kind linking it to "Get User", so it must not be treated as a rename.
		BrunoImportResult result = importer.importRoot(API_ID, load(AMBIGUOUS_MUTATION), "inline-test");

		Assertions.assertEquals(1, result.getEndpointsCreated(), "with no surviving identity signal, the conservative choice is a new object, never a guessed overwrite");
		Assertions.assertEquals(1, result.getEndpointsUpdated(), "only Search Users, matched by its unchanged method+path");
		List<ApiEndpoint> endpointsAfter = vault.getEndpointsForVersion(version.getId());
		Assertions.assertEquals(3, endpointsAfter.size(), "Get User (untouched, orphaned) + Search Users (updated) + Create Order (new) = 3, never 2");

		ApiEndpoint getUserAfter = endpointsAfter.stream().filter(e -> e.getId().equals(getUserBefore.getId())).findFirst().orElseThrow();
		Assertions.assertEquals("Get User", getUserAfter.getName(), "the old, no-longer-referenced endpoint must be left completely untouched, never deleted or corrupted");
		Assertions.assertEquals("GET", getUserAfter.getMethod());
		Assertions.assertEquals("/users/${id}", getUserAfter.getEndpointPath());

		ApiEndpoint createOrder = findEndpoint(vault, version, "Create Order");
		Assertions.assertEquals("POST", createOrder.getMethod());
		Assertions.assertNotEquals(getUserBefore.getId(), createOrder.getId(), "must never have been (mis)assigned the old endpoint's identity");
	}

	private Map<String, Object> load(String yaml) {
		return BrunoYamlUtil.asMap(new Yaml().load(yaml));
	}

	private ApiEndpoint findEndpoint(ApiDefinitionsVault vault, ApiVersion version, String name) throws BroadSQLException {
		return vault.getEndpointsForVersion(version.getId()).stream().filter(e -> name.equals(e.getName())).findFirst()
				.orElseThrow(() -> new AssertionError("Expected endpoint '" + name + "' not found"));
	}
}
