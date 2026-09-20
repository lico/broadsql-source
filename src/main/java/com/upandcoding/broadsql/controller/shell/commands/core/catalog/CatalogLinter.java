package com.upandcoding.broadsql.controller.shell.commands.core.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Bulk consistency checks across a {@link FileCatalog}, used by {@code LIB LINT}/{@code SCRIPT LINT}
 * (see {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}, section 7). Reports findings as plain text lines;
 * never modifies anything.
 */
public class CatalogLinter {

	/**
	 * @param checkParamGaps whether to also check for non-contiguous {@code %N} placeholders - a LIB
	 *                       concept, not applicable to scripts (which don't do {@code %N} substitution)
	 */
	public List<String> lint(FileCatalog catalog, Set<String> knownInstances, Set<String> knownEnvironments, boolean checkParamGaps) throws BroadSQLException {
		List<String> findings = new ArrayList<>();
		Map<String, List<String>> aliasOwners = new HashMap<>();

		for (String relativeKey : new TreeSet<>(catalog.getList())) {
			EntryMetadata metadata = catalog.getEntryMetadata(relativeKey);

			for (String instance : metadata.getInstances()) {
				if (knownInstances != null && !containsIgnoreCase(knownInstances, instance)) {
					findings.add(relativeKey + ": @instance '" + instance + "' does not match any known instance id");
				}
			}

			for (String environment : metadata.getEnvironments()) {
				if (knownEnvironments != null && !containsIgnoreCase(knownEnvironments, environment)) {
					findings.add(relativeKey + ": @environment '" + environment + "' does not match any known environment id");
				}
			}

			for (String alias : metadata.getAliases()) {
				aliasOwners.computeIfAbsent(alias.toLowerCase(), key -> new ArrayList<>()).add(relativeKey);
			}

			if (checkParamGaps) {
				findings.addAll(paramGapFindings(catalog, relativeKey));
			}
		}

		for (Map.Entry<String, List<String>> entry : aliasOwners.entrySet()) {
			if (entry.getValue().size() > 1) {
				findings.add("@alias '" + entry.getKey() + "' is declared by more than one entry: " + String.join(", ", entry.getValue()));
			}
		}

		return findings;
	}

	private List<String> paramGapFindings(FileCatalog catalog, String relativeKey) throws BroadSQLException {
		List<String> findings = new ArrayList<>();
		Set<Integer> params = catalog.getParamNumbers(relativeKey);
		if (!params.isEmpty()) {
			int max = 0;
			for (Integer param : params) {
				max = Math.max(max, param);
			}
			for (int i = 1; i <= max; i++) {
				if (!params.contains(i)) {
					findings.add(relativeKey + ": uses %" + max + " but is missing %" + i + " - non-contiguous parameters");
				}
			}
		}
		return findings;
	}

	private static boolean containsIgnoreCase(Set<String> values, String value) {
		for (String candidate : values) {
			if (candidate.equalsIgnoreCase(value)) {
				return true;
			}
		}
		return false;
	}
}
