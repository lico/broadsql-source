package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * The values the Metadata tab's Database Group and Environment selectors offer, and how they relate.
 * The offered values are exactly the ids Save-time validation accepts ({@code MetadataIntegrityContext.fromVault}:
 * the vault's active Database Groups and active Environments), so whatever the selectors let a user choose also
 * passes Save. The relations come from the active Connections: a Connection in Group G for Environment E makes G
 * and E compatible. Relations only order the lists (see {@link MultiSelectModel#setRelevant}); they never hide
 * or refuse a value.
 *
 * @param groups       the known Database Group ids, sorted; {@code null} when unknown (no CDF loaded)
 * @param environments the known Environment ids, sorted; {@code null} when unknown
 * @param pairs        one (Group, Environment) pair per active Connection that declares both
 */
public record MetadataChoices(List<String> groups, List<String> environments, List<Pair> pairs) {

	/** One Connection's (Database Group, Environment). */
	public record Pair(String group, String environment) {
	}

	/** No CDF: nothing is known, so the selectors accept typed values (Save does not check them either). */
	public static MetadataChoices unknown() {
		return new MetadataChoices(null, null, List.of());
	}

	/** The vault's active Database Groups, active Environments and active Connections; {@link #unknown()} without a vault. */
	public static MetadataChoices fromVault(DatabaseDefinitionsVault vault) {
		if (vault == null) {
			return unknown();
		}
		List<Pair> pairs = new ArrayList<>();
		Collection<DatabaseDefinition> connections = vault.getPlatforms() == null ? List.of() : vault.getPlatforms().values();
		for (DatabaseDefinition connection : connections) {
			if (connection.getDatabaseGroup() != null && connection.getEnvironment() != null) {
				pairs.add(new Pair(connection.getDatabaseGroup(), connection.getEnvironment()));
			}
		}
		return new MetadataChoices(sorted(vault.getGroups()), sorted(vault.getEnvironments()), pairs);
	}

	/**
	 * The Database Groups that have a Connection in at least one of {@code selectedEnvironments}, or {@code null}
	 * ("no context") when no specific Environment is selected.
	 */
	public Set<String> groupsCompatibleWith(Collection<String> selectedEnvironments) {
		if (selectedEnvironments == null || selectedEnvironments.isEmpty()) {
			return null;
		}
		Set<String> wanted = upper(selectedEnvironments);
		Set<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (Pair pair : pairs) {
			if (wanted.contains(pair.environment().toUpperCase(Locale.ROOT))) {
				result.add(pair.group());
			}
		}
		return result;
	}

	/**
	 * The Environments in which at least one of {@code selectedGroups} has a Connection, or {@code null} ("no
	 * context") when no specific Database Group is selected.
	 */
	public Set<String> environmentsCompatibleWith(Collection<String> selectedGroups) {
		if (selectedGroups == null || selectedGroups.isEmpty()) {
			return null;
		}
		Set<String> wanted = upper(selectedGroups);
		Set<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (Pair pair : pairs) {
			if (wanted.contains(pair.group().toUpperCase(Locale.ROOT))) {
				result.add(pair.environment());
			}
		}
		return result;
	}

	private static Set<String> upper(Collection<String> values) {
		Set<String> result = new java.util.HashSet<>();
		for (String value : values) {
			result.add(value.toUpperCase(Locale.ROOT));
		}
		return result;
	}

	private static List<String> sorted(Collection<String> values) {
		if (values == null) {
			return null;
		}
		List<String> result = new ArrayList<>(values);
		result.sort(String.CASE_INSENSITIVE_ORDER);
		return result;
	}
}
