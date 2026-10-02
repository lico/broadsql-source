package com.upandcoding.broadsql.dao.api.bruno;

import java.util.Set;

/**
 * Options for one {@link BrunoCollectionExporter} run - docs/SPRINT XT02-sub sprint 5 - API
 * Configuration GUI + Bruno YAML Round-trip.md, sections 36/37 (secret export policy and export scope).
 */
public final class BrunoExportOptions {

	private boolean includeSecrets = false;
	private Set<Integer> environmentIds;

	/**
	 * Default {@code false} per section 36 - "Include secret values = OFF" is the export dialog's
	 * default, requiring an explicit opt-in plus confirmation dialog before ever writing a real secret
	 * value into the exported YAML.
	 */
	public boolean isIncludeSecrets() {
		return includeSecrets;
	}

	public void setIncludeSecrets(boolean includeSecrets) {
		this.includeSecrets = includeSecrets;
	}

	/** {@code null} (the default) exports every environment - section 37's "Default should be all environments." A non-null set restricts the export to those {@code API_ENVIRONMENT} IDs only. */
	public Set<Integer> getEnvironmentIds() {
		return environmentIds;
	}

	public void setEnvironmentIds(Set<Integer> environmentIds) {
		this.environmentIds = environmentIds;
	}

	boolean includesEnvironment(int environmentId) {
		return environmentIds == null || environmentIds.contains(environmentId);
	}
}
