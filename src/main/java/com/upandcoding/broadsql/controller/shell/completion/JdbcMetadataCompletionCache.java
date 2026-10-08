package com.upandcoding.broadsql.controller.shell.completion;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-connection (platform id) lazy cache of schema/table/view/column names - SPRINT 0917-02, sections
 * 25/26. Nothing is fetched until a provider actually asks for it (section 35: normal typing must
 * never touch the database); once fetched, a bucket is kept until explicitly invalidated.
 *
 * <p>One instance is shared for the whole application session (see {@code JdbcMetadataCompletionCacheHolder}) -
 * everything is scoped underneath a platform id key, so switching connections never leaks stale
 * metadata from a previous one (section 26: "Never allow metadata from database A to appear after the
 * user connects to database B") without needing a brand new cache object per connection.
 */
public final class JdbcMetadataCompletionCache {

	private final Map<String, PlatformBucket> byPlatform = new ConcurrentHashMap<>();

	/** Coarse invalidation for one platform - section 26: called after DDL and after any connection change for that platform. Correctness over saving a few JDBC calls. */
	public void invalidate(String platformId) {
		if (platformId != null) {
			byPlatform.remove(platformId);
		}
	}

	public void invalidateAll() {
		byPlatform.clear();
	}

	private PlatformBucket bucket(String platformId) {
		return byPlatform.computeIfAbsent(platformId == null ? "" : platformId, k -> new PlatformBucket());
	}

	/** @param schemaKey upper-cased schema name, or {@code ""} for "no schema filter" (every schema). */
	public List<TableEntry> tables(String platformId, String schemaKey, Supplier<List<TableEntry>> loader) {
		return bucket(platformId).tablesBySchema.computeIfAbsent(schemaKey == null ? "" : schemaKey, k -> loader.get());
	}

	public List<String> schemas(String platformId, Supplier<List<String>> loader) {
		return bucket(platformId).schemas.updateAndGet(existing -> existing != null ? existing : loader.get());
	}

	/** @param tableKey upper-cased, optionally schema-qualified ({@code "SCHEMA.TABLE"} or just {@code "TABLE"}) table key. */
	public List<String> columns(String platformId, String tableKey, Supplier<List<String>> loader) {
		return bucket(platformId).columnsByTable.computeIfAbsent(tableKey.toUpperCase(Locale.ROOT), k -> loader.get());
	}

	public static final class TableEntry {
		private final String name;
		private final boolean view;

		public TableEntry(String name, boolean view) {
			this.name = name;
			this.view = view;
		}

		public String getName() {
			return name;
		}

		public boolean isView() {
			return view;
		}
	}

	private static final class PlatformBucket {
		final Map<String, List<TableEntry>> tablesBySchema = new ConcurrentHashMap<>();
		final Map<String, List<String>> columnsByTable = new ConcurrentHashMap<>();
		final java.util.concurrent.atomic.AtomicReference<List<String>> schemas = new java.util.concurrent.atomic.AtomicReference<>();
	}
}
