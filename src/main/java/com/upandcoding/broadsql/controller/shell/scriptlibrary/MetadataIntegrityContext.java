package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.Set;
import java.util.function.BiPredicate;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * What Save-time metadata integrity needs to know about the connection definitions file (CDF): which
 * Database Group ids and Environment ids exist, and whether a Database Group has a connection for an
 * Environment. Any part may be {@code null} meaning "unknown, do not check" (no CDF loaded, e.g. headless);
 * alias and status integrity never depend on it.
 *
 * @param knownGroups        Database Group ids, or {@code null}
 * @param knownEnvironments  Environment ids, or {@code null}
 * @param groupHasEnvironment {@code (groupId, environmentId) -> a connection exists}, or {@code null}
 */
public record MetadataIntegrityContext(Set<String> knownGroups, Set<String> knownEnvironments, BiPredicate<String, String> groupHasEnvironment) {

	public static MetadataIntegrityContext unchecked() {
		return new MetadataIntegrityContext(null, null, null);
	}

	/** The same group/environment id sets {@code LIB LINT} uses, plus the CDF's own (Group, Environment) pair rule. */
	public static MetadataIntegrityContext fromVault(DatabaseDefinitionsVault vault) {
		if (vault == null) {
			return unchecked();
		}
		return new MetadataIntegrityContext(vault.getGroups(), vault.getEnvironments(), (group, environment) -> {
			try {
				for (DatabaseDefinition connection : vault.getConnectionsForGroup(group)) {
					if (environment.equalsIgnoreCase(connection.getEnvironment())) {
						return true;
					}
				}
			} catch (BroadSQLException e) {
				return true; // cannot tell: never block a Save on an unreadable CDF
			}
			return false;
		});
	}
}
