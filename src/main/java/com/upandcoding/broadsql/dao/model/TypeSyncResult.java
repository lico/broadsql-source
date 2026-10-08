package com.upandcoding.broadsql.dao.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of {@code DatabaseDefinitionsVault#syncTypeCatalog}: which {@code TYPE} row IDs were newly
 * inserted, which already existed and were refreshed to the recognized defaults, and which were left
 * alone entirely because the recognized entry has no known driver class name. Purely a reporting
 * struct for {@code CommandSyncTypeCatalog} - carries no behavior of its own.
 */
public class TypeSyncResult {

	private final List<String> added = new ArrayList<>();
	private final List<String> updated = new ArrayList<>();
	private final List<String> skippedNoDriver = new ArrayList<>();

	public List<String> getAdded() {
		return added;
	}

	public List<String> getUpdated() {
		return updated;
	}

	public List<String> getSkippedNoDriver() {
		return skippedNoDriver;
	}
}
