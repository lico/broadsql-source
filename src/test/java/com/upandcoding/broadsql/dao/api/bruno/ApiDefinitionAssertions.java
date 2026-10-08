package com.upandcoding.broadsql.dao.api.bruno;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Assertions;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Compares two {@link ApiDefinitionsVault} APIs for <b>semantic</b> equality - docs/SPRINT XT02-sub
 * sprint 5 - API Configuration GUI + Bruno YAML Round-trip.md, section 39: "The representation does not
 * need to be byte-identical. It must be semantically equivalent." Used by
 * {@link TestBrunoRoundTripSemantic} to compare an API against a fresh re-import of its own export.
 *
 * <p>Deliberately ignores generated IDs, {@code sourceType}/{@code sourceKey}, and endpoint
 * {@code alias} (per docs/Amendment - Endpoint Aliases and Future Scriptability.md, section 11, alias is
 * never expected to survive a round trip through a fresh installation) - everything else meaningful
 * (name, methods, URLs, headers, parameters, variables, authentication, bodies, folder hierarchy,
 * environments) must match. Folders and endpoints are matched by a name-based path (folder hierarchy) or
 * (folder path, method, URL) key, since generated IDs necessarily differ between two separately-populated
 * vaults.
 */
final class ApiDefinitionAssertions {

	private ApiDefinitionAssertions() {
	}

	static void assertSemanticallyEqual(ApiDefinitionsVault vaultA, String apiIdA, ApiDefinitionsVault vaultB, String apiIdB) throws BroadSQLException {
		ApiDefinition a = vaultA.getApi(apiIdA);
		ApiDefinition b = vaultB.getApi(apiIdB);
		Assertions.assertNotNull(a, "API '" + apiIdA + "' not found in vault A");
		Assertions.assertNotNull(b, "API '" + apiIdB + "' not found in vault B");
		Assertions.assertEquals(a.getName(), b.getName(), "API name");

		assertEnvironmentsEqual(vaultA, apiIdA, vaultB, apiIdB);

		ApiVersion versionA = vaultA.getDefaultVersion(apiIdA);
		ApiVersion versionB = vaultB.getDefaultVersion(apiIdB);

		Map<String, ApiEndpointGroup> groupsA = indexGroups(vaultA, versionA.getId());
		Map<String, ApiEndpointGroup> groupsB = indexGroups(vaultB, versionB.getId());
		Assertions.assertEquals(groupsA.keySet(), groupsB.keySet(), "folder hierarchy (by name path)");
		for (String path : groupsA.keySet()) {
			assertOwnerAttributesEqual(vaultA, ApiOwnerType.GROUP, String.valueOf(groupsA.get(path).getId()),
					vaultB, ApiOwnerType.GROUP, String.valueOf(groupsB.get(path).getId()), "folder '" + path + "'");
		}

		Map<String, ApiEndpoint> endpointsA = indexEndpoints(vaultA, versionA.getId());
		Map<String, ApiEndpoint> endpointsB = indexEndpoints(vaultB, versionB.getId());
		Assertions.assertEquals(endpointsA.keySet(), endpointsB.keySet(), "endpoint set (folder path + method + URL)");
		for (String key : endpointsA.keySet()) {
			ApiEndpoint endpointA = endpointsA.get(key);
			ApiEndpoint endpointB = endpointsB.get(key);
			Assertions.assertEquals(endpointA.getName(), endpointB.getName(), key + " display name");
			Assertions.assertEquals(endpointA.getBodyMode(), endpointB.getBodyMode(), key + " body mode");
			Assertions.assertEquals(endpointA.getBodyContent(), endpointB.getBodyContent(), key + " body content");
			assertOwnerAttributesEqual(vaultA, ApiOwnerType.ENDPOINT, String.valueOf(endpointA.getId()),
					vaultB, ApiOwnerType.ENDPOINT, String.valueOf(endpointB.getId()), key);
		}

		assertOwnerAttributesEqual(vaultA, ApiOwnerType.API, apiIdA, vaultB, ApiOwnerType.API, apiIdB, "API '" + apiIdA + "'");
	}

	private static void assertEnvironmentsEqual(ApiDefinitionsVault vaultA, String apiIdA, ApiDefinitionsVault vaultB, String apiIdB) throws BroadSQLException {
		Map<String, ApiEnvironment> byNameA = new LinkedHashMap<>();
		for (ApiEnvironment env : vaultA.getEnvironmentsForApi(apiIdA)) {
			if (isActive(env)) {
				byNameA.put(env.getName(), env);
			}
		}
		Map<String, ApiEnvironment> byNameB = new LinkedHashMap<>();
		for (ApiEnvironment env : vaultB.getEnvironmentsForApi(apiIdB)) {
			if (isActive(env)) {
				byNameB.put(env.getName(), env);
			}
		}
		Assertions.assertEquals(byNameA.keySet(), byNameB.keySet(), "environment names");
		for (String name : byNameA.keySet()) {
			ApiEnvironment envA = byNameA.get(name);
			ApiEnvironment envB = byNameB.get(name);
			Assertions.assertEquals(envA.getBaseUrl(), envB.getBaseUrl(), "environment '" + name + "' baseUrl");
			List<ApiAttribute> varsA = vaultA.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(envA.getId()), ApiAttributeKind.VARIABLE);
			List<ApiAttribute> varsB = vaultB.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(envB.getId()), ApiAttributeKind.VARIABLE);
			assertAttributeListsEqual(varsA, varsB, "environment '" + name + "' variables");
		}
	}

	private static Map<String, ApiEndpointGroup> indexGroups(ApiDefinitionsVault vault, int apiVersionId) throws BroadSQLException {
		Map<String, ApiEndpointGroup> result = new LinkedHashMap<>();
		List<ApiEndpointGroup> all = vault.getGroupsForVersion(apiVersionId);
		for (ApiEndpointGroup group : all) {
			if (!isActive(group)) {
				continue;
			}
			result.put(pathOf(all, group), group);
		}
		return result;
	}

	private static String pathOf(List<ApiEndpointGroup> all, ApiEndpointGroup group) {
		List<String> segments = new ArrayList<>();
		ApiEndpointGroup current = group;
		int maxDepth = 100;
		while (current != null && maxDepth-- > 0) {
			segments.add(0, current.getName());
			Integer parentId = current.getParentGroupId();
			current = parentId == null ? null : all.stream().filter(g -> g.getId().equals(parentId)).findFirst().orElse(null);
		}
		return String.join("/", segments);
	}

	private static Map<String, ApiEndpoint> indexEndpoints(ApiDefinitionsVault vault, int apiVersionId) throws BroadSQLException {
		Map<String, ApiEndpoint> result = new LinkedHashMap<>();
		List<ApiEndpointGroup> allGroups = vault.getGroupsForVersion(apiVersionId);
		for (ApiEndpoint endpoint : vault.getEndpointsForVersion(apiVersionId)) {
			if (!isActive(endpoint)) {
				continue;
			}
			String folderPath = endpoint.getGroupId() == null ? "" : pathOf(allGroups, allGroups.stream()
					.filter(g -> g.getId().equals(endpoint.getGroupId())).findFirst().orElseThrow());
			String key = folderPath + "::" + endpoint.getMethod() + " " + endpoint.getEndpointPath();
			result.put(key, endpoint);
		}
		return result;
	}

	private static void assertOwnerAttributesEqual(ApiDefinitionsVault vaultA, ApiOwnerType ownerTypeA, String ownerIdA,
			ApiDefinitionsVault vaultB, ApiOwnerType ownerTypeB, String ownerIdB, String label) throws BroadSQLException {
		for (ApiAttributeKind kind : ApiAttributeKind.values()) {
			if (kind == ApiAttributeKind.PROPERTY) {
				continue; // AUTH-owned - compared below, alongside the auth type itself
			}
			List<ApiAttribute> a = vaultA.getAttributes(ownerTypeA, ownerIdA, kind);
			List<ApiAttribute> b = vaultB.getAttributes(ownerTypeB, ownerIdB, kind);
			assertAttributeListsEqual(a, b, label + " " + kind);
		}
		ApiAuthConfig authA = vaultA.getAuth(ownerTypeA, ownerIdA);
		ApiAuthConfig authB = vaultB.getAuth(ownerTypeB, ownerIdB);
		Assertions.assertEquals(authA == null, authB == null, label + " auth presence (row exists vs. inherit)");
		if (authA == null) {
			return;
		}
		Assertions.assertEquals(authA.getAuthType(), authB.getAuthType(), label + " auth type");
		List<ApiAttribute> propsA = vaultA.getAttributes(ApiOwnerType.AUTH, String.valueOf(authA.getId()), ApiAttributeKind.PROPERTY);
		List<ApiAttribute> propsB = vaultB.getAttributes(ApiOwnerType.AUTH, String.valueOf(authB.getId()), ApiAttributeKind.PROPERTY);
		assertAttributeListsEqual(propsA, propsB, label + " auth properties");
	}

	private static void assertAttributeListsEqual(List<ApiAttribute> a, List<ApiAttribute> b, String label) {
		Set<String> keysA = new TreeSet<>();
		for (ApiAttribute attr : a) {
			keysA.add(attrKey(attr));
		}
		Set<String> keysB = new TreeSet<>();
		for (ApiAttribute attr : b) {
			keysB.add(attrKey(attr));
		}
		Assertions.assertEquals(keysA, keysB, label);
	}

	private static String attrKey(ApiAttribute attr) {
		return attr.getName() + "=" + attr.getValue() + "|secret=" + attr.isSecret() + "|enabled=" + attr.isEnabled();
	}

	private static boolean isActive(ApiEnvironment env) {
		return DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(env.getStatusId());
	}

	private static boolean isActive(ApiEndpointGroup group) {
		return DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(group.getStatusId());
	}

	private static boolean isActive(ApiEndpoint endpoint) {
		return DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(endpoint.getStatusId());
	}
}
