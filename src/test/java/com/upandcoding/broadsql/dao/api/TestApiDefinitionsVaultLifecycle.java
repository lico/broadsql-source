package com.upandcoding.broadsql.dao.api;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Covers the deactivate/reactivate/permanently-delete lifecycle methods SPRINT XT02 sub-sprint 5 added
 * to {@link ApiDefinitionsVault} - the CRUD-completion piece the plan identified as genuinely missing
 * (a grep for delete/deactivate on this class returned zero matches before this sprint). Mirrors
 * {@code DatabaseDefinitionsVault}'s own soft-delete-then-hard-delete test coverage style.
 */
class TestApiDefinitionsVaultLifecycle {

	@Test
	void deactivatingAnApiDropsItFromTheActiveCacheAndReactivatingRestoresIt() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition("GITHUB"));

		vault.deactivateApi("GITHUB");
		Assertions.assertFalse(vault.contains("GITHUB"), "a deactivated API must drop out of the active cache");

		vault.reactivateApi("GITHUB");
		Assertions.assertTrue(vault.contains("GITHUB"), "reactivating must restore it to the active cache");
	}

	@Test
	void hardDeleteApiRefusesWhileStillActive() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition("GITHUB"));

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.hardDeleteApi("GITHUB"));
		Assertions.assertTrue(ex.getMessage().contains("still active"), "must name why it refused: " + ex.getMessage());
	}

	@Test
	void hardDeleteApiCascadesEveryEnvironmentFolderAndEndpointBeneathIt() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("JIRA"));

		ApiEnvironment env = new ApiEnvironment("JIRA", "Production", "https://prod.example.com", 0);
		vault.saveEnvironment(env);

		ApiEndpointGroup group = new ApiEndpointGroup(version.getId(), null, "Issues", 0);
		vault.saveEndpointGroup(group);

		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), group.getId(), "Get issue", "GET", "/issue/1", 0);
		vault.saveEndpoint(endpoint);

		vault.deactivateApi("JIRA");
		vault.hardDeleteApi("JIRA");

		ApiDefinitionsVault reopened = new ApiDefinitionsVault(vault.getFileName(), vault.getPassword());
		reopened.load();
		Assertions.assertFalse(reopened.contains("JIRA"));
		Assertions.assertTrue(reopened.getEnvironmentsForApi("JIRA").isEmpty(), "environments must be removed with the API");
		Assertions.assertTrue(reopened.getGroupsForVersion(version.getId()).isEmpty(), "folders must be removed with the API");
		Assertions.assertTrue(reopened.getEndpointsForVersion(version.getId()).isEmpty(), "endpoints must be removed with the API");
	}

	@Test
	void environmentDeactivateReactivateAndHardDeleteLifecycle() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition("GITHUB"));
		ApiEnvironment env = new ApiEnvironment("GITHUB", "Staging", "https://staging.example.com", 0);
		vault.saveEnvironment(env);

		vault.deactivateEnvironment(env.getId());
		ApiEnvironment reloaded = vault.findEnvironmentById(env.getId());
		Assertions.assertEquals(DatabaseDefinition.STATUS_INACTIVE, reloaded.getStatusId());

		BroadSQLException refusal = Assertions.assertThrows(BroadSQLException.class, () -> {
			// hardDelete on an active environment must refuse - reactivate first to exercise that path.
			vault.reactivateEnvironment(env.getId());
			vault.hardDeleteEnvironment(env.getId());
		});
		Assertions.assertTrue(refusal.getMessage().contains("still active"));

		vault.deactivateEnvironment(env.getId());
		vault.hardDeleteEnvironment(env.getId());
		Assertions.assertNull(vault.findEnvironmentById(env.getId()), "hard delete must actually remove the row");
	}

	@Test
	void endpointGroupHardDeleteRefusesWhileItStillHasChildrenAndSucceedsOnceEmpty() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("JIRA"));
		ApiEndpointGroup group = new ApiEndpointGroup(version.getId(), null, "Issues", 0);
		vault.saveEndpointGroup(group);
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), group.getId(), "Get issue", "GET", "/issue/1", 0);
		vault.saveEndpoint(endpoint);

		vault.deactivateEndpointGroup(group.getId());
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.hardDeleteEndpointGroup(group.getId()));
		Assertions.assertTrue(ex.getMessage().contains("endpoint 'Get issue'"), "must name the blocking endpoint: " + ex.getMessage());

		vault.deactivateEndpoint(endpoint.getId());
		vault.hardDeleteEndpoint(endpoint.getId());
		vault.hardDeleteEndpointGroup(group.getId());
		Assertions.assertNull(vault.findGroupById(group.getId()));
	}

	@Test
	void endpointDeactivationFreesItsAliasImmediately() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("ORDERS"));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Create order", "POST", "/orders", 0);
		endpoint.setAlias("DO_ORDER");
		vault.saveEndpoint(endpoint);

		Assertions.assertNotNull(vault.findEndpointByAlias("ORDERS", "DO_ORDER"));

		vault.deactivateEndpoint(endpoint.getId());
		Assertions.assertNull(vault.findEndpointByAlias("ORDERS", "DO_ORDER"), "a deactivated endpoint's alias must be immediately reusable");

		ApiEndpoint another = new ApiEndpoint(version.getId(), null, "Create order v2", "POST", "/v2/orders", 1);
		another.setAlias("DO_ORDER");
		vault.saveEndpoint(another); // must not throw a conflict

		List<ApiEndpoint> endpoints = vault.getEndpointsForVersion(version.getId());
		Assertions.assertEquals(2, endpoints.size());
	}

	@Test
	void hardDeleteEndpointRefusesWhileStillActive() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("ORDERS"));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Create order", "POST", "/orders", 0);
		vault.saveEndpoint(endpoint);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.hardDeleteEndpoint(endpoint.getId()));
		Assertions.assertTrue(ex.getMessage().contains("still active"));
	}

	/**
	 * SPRINT XT02 verification finding 3: {@code reactivateEndpoint} used to only ever flip
	 * {@code STATUS_ID} back to {@code ACTIVE}, never re-checking alias uniqueness - so deactivating
	 * endpoint A (alias {@code AuditAlias}), creating endpoint B with the case-insensitively colliding
	 * alias {@code auditalias}, then reactivating A produced two active endpoints sharing the same alias.
	 * Reactivation must now go through the exact same {@link ApiDefinitionsVault#saveEndpoint} alias check
	 * and refuse; A must stay inactive and B must be completely unaffected by the refused attempt.
	 */
	@Test
	void reactivatingAnEndpointRefusesWhenItsAliasHasSinceBeenClaimedByAnotherActiveEndpoint() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("AUDIT"));

		ApiEndpoint a = new ApiEndpoint(version.getId(), null, "Audit endpoint A", "GET", "/audit/a", 0);
		a.setAlias("AuditAlias");
		vault.saveEndpoint(a);
		vault.deactivateEndpoint(a.getId());

		ApiEndpoint b = new ApiEndpoint(version.getId(), null, "Audit endpoint B", "GET", "/audit/b", 1);
		b.setAlias("auditalias"); // case-insensitive collision with A's now-freed alias, per the amendment
		vault.saveEndpoint(b);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.reactivateEndpoint(a.getId()));
		Assertions.assertTrue(ex.getMessage().contains("already assigned to another API endpoint"), "got: " + ex.getMessage());

		ApiEndpoint reloadedA = vault.findEndpointById(a.getId());
		Assertions.assertEquals(DatabaseDefinition.STATUS_INACTIVE, reloadedA.getStatusId(), "a refused reactivation must leave A inactive");
		ApiEndpoint reloadedB = vault.findEndpointById(b.getId());
		Assertions.assertEquals(DatabaseDefinition.STATUS_ACTIVE, reloadedB.getStatusId(), "B must be completely unaffected by A's refused reactivation");
		Assertions.assertEquals("auditalias", reloadedB.getAlias());

		long activeAliasCount = vault.getEndpointsForVersion(version.getId()).stream()
				.filter(e -> DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(e.getStatusId()))
				.filter(e -> "auditalias".equalsIgnoreCase(e.getAlias()))
				.count();
		Assertions.assertEquals(1, activeAliasCount, "exactly one active endpoint may hold this alias");
	}

	@Test
	void reactivatingAnEndpointSucceedsWhenItsAliasIsStillFree() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("AUDIT2"));
		ApiEndpoint a = new ApiEndpoint(version.getId(), null, "Audit endpoint A", "GET", "/audit/a", 0);
		a.setAlias("FreeAlias");
		vault.saveEndpoint(a);
		vault.deactivateEndpoint(a.getId());

		vault.reactivateEndpoint(a.getId());

		ApiEndpoint reloaded = vault.findEndpointById(a.getId());
		Assertions.assertEquals(DatabaseDefinition.STATUS_ACTIVE, reloaded.getStatusId());
		Assertions.assertEquals("FreeAlias", reloaded.getAlias());
	}

	/**
	 * SPRINT XT02 verification finding 5: the GUI ({@code JApiEndpointTreePanel.delete}) used to call
	 * {@code deactivateEndpointGroup} then {@code hardDeleteEndpointGroup} as two separate calls - a
	 * non-empty folder's hard delete correctly refused, but the deactivation had already been committed,
	 * so a "failed" delete silently left the folder deactivated anyway.
	 * {@link ApiDefinitionsVault#deleteEndpointGroupPermanently} is the fix: it must check for blocking
	 * children <b>before</b> mutating anything, so a refused deletion leaves the folder exactly as it was.
	 */
	@Test
	void deletingANonEmptyFolderPermanentlyRefusesAndLeavesItUnchanged() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("JIRA2"));
		ApiEndpointGroup group = new ApiEndpointGroup(version.getId(), null, "Issues", 0);
		vault.saveEndpointGroup(group);
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), group.getId(), "Get issue", "GET", "/issue/1", 0);
		vault.saveEndpoint(endpoint);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> vault.deleteEndpointGroupPermanently(group.getId()));
		Assertions.assertTrue(ex.getMessage().contains("endpoint 'Get issue'"), "must name the blocking endpoint: " + ex.getMessage());

		ApiEndpointGroup reloaded = vault.findGroupById(group.getId());
		Assertions.assertEquals(DatabaseDefinition.STATUS_ACTIVE, reloaded.getStatusId(),
				"a refused deletion must leave the folder exactly as it was - still ACTIVE, not deactivated");

		// Reload from persistence too, not just the in-memory object, to prove this actually persisted.
		ApiDefinitionsVault reopened = new ApiDefinitionsVault(vault.getFileName(), vault.getPassword());
		reopened.load();
		ApiEndpointGroup reopenedGroup = reopened.findGroupById(group.getId());
		Assertions.assertEquals(DatabaseDefinition.STATUS_ACTIVE, reopenedGroup.getStatusId());
	}

	@Test
	void deletingAnEmptyFolderPermanentlySucceeds() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition("JIRA3"));
		ApiEndpointGroup group = new ApiEndpointGroup(version.getId(), null, "Empty folder", 0);
		vault.saveEndpointGroup(group);

		vault.deleteEndpointGroupPermanently(group.getId());

		Assertions.assertNull(vault.findGroupById(group.getId()), "an empty folder must be fully removed");
	}
}
