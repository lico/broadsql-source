package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;

/**
 * Save-time metadata integrity (SPRINT 0917-01 corrective pass; SPRINT 1909S: no alias check, aliases no longer exist in the Scripts Library): what must hold for an asset to be
 * persisted as a valid catalog entry, independent of whether its SQL/script body is finished. Save must let a
 * user store unfinished draft work, so this never looks at the body; {@code LIB LINT}
 * remains the deeper content check.
 *
 * <ul>
 * <li><b>Database Group ({@code @instance})</b>: each declared id must be a known Database Group;</li>
 * <li><b>Environment ({@code @environment})</b>: each declared id must be a known Environment;</li>
 * <li><b>relation</b>: when exactly one specific Group and one specific Environment are declared, that Group
 * must have a connection for that Environment (the CDF's own (Group, Environment) rule). With several values
 * or {@code ALL} the two dimensions stay independent scoping tags, as everywhere else in the catalog model, so
 * no pair is required;</li>
 * <li><b>status</b>: blank ("not specified") or one of {@link EntryMetadata#VALID_STATUSES}.</li>
 * </ul>
 * An undeclared Group/Environment ({@code NONE}) is legal and never checked.
 */
public final class MetadataIntegrity {

	private MetadataIntegrity() {
	}

	public static List<MetadataIssue> check(String content, MetadataIntegrityContext context) {
		EntryMetadata metadata = EntryMetadata.parse(content);
		List<MetadataIssue> issues = new ArrayList<>();
		checkGroupsAndEnvironments(metadata, context, issues);
		checkStatus(metadata, issues);
		return issues;
	}

	private static void checkGroupsAndEnvironments(EntryMetadata metadata, MetadataIntegrityContext context, List<MetadataIssue> issues) {
		Set<String> groups = context.knownGroups();
		Set<String> environments = context.knownEnvironments();
		boolean groupsValid = true;
		boolean environmentsValid = true;
		if (groups != null) {
			for (String group : metadata.getInstances()) {
				if (!containsIgnoreCase(groups, group)) {
					issues.add(new MetadataIssue("instance", "Unknown Database Group '" + group + "'."));
					groupsValid = false;
				}
			}
		}
		if (environments != null) {
			for (String environment : metadata.getEnvironments()) {
				if (!containsIgnoreCase(environments, environment)) {
					String forGroup = metadata.getInstances().size() == 1 && !metadata.isAllInstances() ? " for Database Group '" + metadata.getInstances().get(0) + "'" : "";
					issues.add(new MetadataIssue("environment", "Unknown environment '" + environment + "'" + forGroup + "."));
					environmentsValid = false;
				}
			}
		}
		if (groupsValid && environmentsValid && context.groupHasEnvironment() != null && !metadata.isAllInstances() && !metadata.isAllEnvironments()
				&& metadata.getInstances().size() == 1 && metadata.getEnvironments().size() == 1) {
			String group = metadata.getInstances().get(0);
			String environment = metadata.getEnvironments().get(0);
			if (!context.groupHasEnvironment().test(group, environment)) {
				issues.add(new MetadataIssue("environment", "Database Group '" + group + "' has no connection for environment '" + environment + "'."));
			}
		}
	}

	private static void checkStatus(EntryMetadata metadata, List<MetadataIssue> issues) {
		String status = metadata.getStatus();
		if (status != null && !status.isBlank() && !EntryMetadata.isValidStatus(status)) {
			issues.add(new MetadataIssue("status", "Invalid status '" + status.trim() + "'. Valid values: " + String.join(", ", EntryMetadata.VALID_STATUSES) + "."));
		}
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
