package com.upandcoding.broadsql.dao.api.bruno;

import java.util.ArrayList;
import java.util.List;

/**
 * Object counts and diagnostics from one {@link BrunoCollectionImporter} run - what the acceptance
 * checkpoint for SPRINT XT02 sub-sprint 2 (docs/SPRINT XT02 - Universal API Client.md) asks to see
 * reported ("report object counts before and after to prove [idempotent re-import]").
 */
public final class BrunoImportResult {

	private int environmentsCreated;
	private int environmentsUpdated;
	private int groupsCreated;
	private int groupsUpdated;
	private int endpointsCreated;
	private int endpointsUpdated;
	private int runtimeFeaturesIgnored;
	private final List<String> skippedItemTypes = new ArrayList<>();
	private final List<String> unsupportedAuthTypes = new ArrayList<>();

	void recordEnvironment(boolean created) {
		if (created) {
			environmentsCreated++;
		} else {
			environmentsUpdated++;
		}
	}

	void recordGroup(boolean created) {
		if (created) {
			groupsCreated++;
		} else {
			groupsUpdated++;
		}
	}

	void recordEndpoint(boolean created) {
		if (created) {
			endpointsCreated++;
		} else {
			endpointsUpdated++;
		}
	}

	void addIgnoredRuntimeFeatures(int count) {
		runtimeFeaturesIgnored += count;
	}

	void recordSkippedItem(String itemType) {
		skippedItemTypes.add(itemType);
	}

	void recordUnsupportedAuth(String sourceAuthType) {
		unsupportedAuthTypes.add(sourceAuthType);
	}

	public int getEnvironmentsCreated() {
		return environmentsCreated;
	}

	public int getEnvironmentsUpdated() {
		return environmentsUpdated;
	}

	public int getGroupsCreated() {
		return groupsCreated;
	}

	public int getGroupsUpdated() {
		return groupsUpdated;
	}

	public int getEndpointsCreated() {
		return endpointsCreated;
	}

	public int getEndpointsUpdated() {
		return endpointsUpdated;
	}

	public int getRuntimeFeaturesIgnored() {
		return runtimeFeaturesIgnored;
	}

	public List<String> getSkippedItemTypes() {
		return skippedItemTypes;
	}

	public List<String> getUnsupportedAuthTypes() {
		return unsupportedAuthTypes;
	}

	@Override
	public String toString() {
		return "environments[created=" + environmentsCreated + ", updated=" + environmentsUpdated + "], "
				+ "groups[created=" + groupsCreated + ", updated=" + groupsUpdated + "], "
				+ "endpoints[created=" + endpointsCreated + ", updated=" + endpointsUpdated + "], "
				+ "runtimeFeaturesIgnored=" + runtimeFeaturesIgnored + ", "
				+ "skippedItemTypes=" + skippedItemTypes + ", "
				+ "unsupportedAuthTypes=" + unsupportedAuthTypes;
	}
}
