package com.upandcoding.broadsql.dao.api.tabular;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.execution.ApiExecutionPolicy;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * Builds the {@link ApiResultTable} behind the single canonical {@code SHOW ENDPOINTS [API <apiId>]}
 * command (API Quality and UX Consolidation sprint; formerly shared between the now-retired {@code SHOW
 * API ENDPOINTS} and {@code SHOW ENDPOINTS}). Columns: {@code ID}, {@code VERB}, {@code FOLDER},
 * {@code NAME}, {@code ALIAS} - a compact catalog/navigation view. {@code PATH} (the URL) and
 * {@code EXECUTABLE} were removed from this table (API Quality and UX Consolidation sprint, section 3)
 * - the full URL and executability of one endpoint are available via {@code SHOW ENDPOINT <id>}'s
 * detail view instead; a catalog table listing many endpoints stays compact.
 *
 * <p>Sort order is deterministic (folder, then path, then verb, then id - docs/SPRINT XT02-7B, section
 * 21), never left to database retrieval order - path remains the tiebreaker internally even though it
 * is no longer a displayed column, so removing it from {@link #COLUMNS} does not change row ordering.
 * {@code verbFilter} is an exact, case-insensitive HTTP verb match; {@code matchFilter} is a
 * case-insensitive substring search over folder path, path, name, and alias combined (section 18);
 * either may be {@code null}/blank to skip that filter. Filtering never errors on zero matches - it
 * simply returns a table with zero rows.
 */
public final class ApiEndpointListingBuilder {

	private static final List<String> COLUMNS = List.of("ID", "VERB", "FOLDER", "NAME", "ALIAS");

	private ApiEndpointListingBuilder() {
	}

	public static ApiResultTable build(ApiDefinitionsVault vault, String apiId, String verbFilter, String matchFilter) throws BroadSQLException {
		ApiVersion version = vault.getDefaultVersion(apiId);
		List<ApiEndpoint> endpoints = version == null ? List.of() : vault.getEndpointsForVersion(version.getId());

		Map<Integer, String> folderPathCache = new HashMap<>();
		List<Map<String, String>> rows = new ArrayList<>();
		List<ApiEndpoint> sorted = new ArrayList<>(endpoints);
		Map<Integer, String> folderPathById = new HashMap<>();
		for (ApiEndpoint endpoint : sorted) {
			folderPathById.put(endpoint.getId(), folderPath(vault, endpoint.getGroupId(), folderPathCache));
		}
		sorted.sort(Comparator
				.comparing((ApiEndpoint e) -> StringUtils.defaultString(folderPathById.get(e.getId())))
				.thenComparing(e -> StringUtils.defaultString(e.getEndpointPath()))
				.thenComparing(e -> StringUtils.defaultString(e.getMethod()))
				.thenComparing(ApiEndpoint::getId));

		String verbNeedle = StringUtils.trimToNull(verbFilter);
		String matchNeedle = matchFilter == null ? null : matchFilter.trim().toLowerCase();

		for (ApiEndpoint endpoint : sorted) {
			String folder = folderPathById.get(endpoint.getId());
			String verb = StringUtils.defaultString(endpoint.getMethod());
			String name = StringUtils.defaultString(endpoint.getName());
			String alias = StringUtils.defaultString(endpoint.getAlias());
			String path = StringUtils.defaultString(endpoint.getEndpointPath());

			if (verbNeedle != null && !verbNeedle.equalsIgnoreCase(verb)) {
				continue;
			}
			if (matchNeedle != null) {
				String haystack = (folder + " " + path + " " + name + " " + alias).toLowerCase();
				if (!haystack.contains(matchNeedle)) {
					continue;
				}
			}

			Map<String, String> row = new LinkedHashMap<>();
			row.put("ID", String.valueOf(endpoint.getId()));
			row.put("VERB", verb);
			row.put("FOLDER", folder);
			row.put("NAME", name);
			row.put("ALIAS", alias);
			rows.add(row);
		}

		return new ApiResultTable(COLUMNS, rows, true, null);
	}

	/**
	 * Parses the optional trailing filter clauses, shared by {@code SHOW ENDPOINTS} and
	 * {@code SHOW API ENDPOINTS} (section 12/13/19). SPRINT XT02B, section 9: the canonical form is now a
	 * bare HTTP method token (e.g. {@code SHOW ENDPOINTS GET;}); the legacy {@code VERB <verb>} form
	 * remains accepted for backward compatibility, and {@code MATCH <keyword>} is unchanged. Each clause
	 * may appear at most once, in either order; an unrecognized leading token is a usage error, not
	 * silently ignored.
	 */
	public static Filters parseFilters(String[] tokens, int fromIndex) throws BroadSQLException {
		String verb = null;
		String match = null;
		int i = fromIndex;
		while (i < tokens.length) {
			String key = tokens[i];
			if ("VERB".equalsIgnoreCase(key)) {
				if (i + 1 >= tokens.length) {
					throw new BroadSQLException("VERB requires a value, e.g. VERB GET.");
				}
				verb = tokens[i + 1];
				i += 2;
			} else if ("MATCH".equalsIgnoreCase(key)) {
				if (i + 1 >= tokens.length) {
					throw new BroadSQLException("MATCH requires a value, e.g. MATCH mail.");
				}
				match = tokens[i + 1];
				i += 2;
			} else if (verb == null && ApiExecutionPolicy.isExecutable(key)) {
				// Canonical form: SHOW ENDPOINTS GET; - a bare recognized HTTP method token, not preceded by VERB.
				verb = key;
				i += 1;
			} else {
				throw new BroadSQLException("Unrecognized clause '" + key + "'. Expected an HTTP method (e.g. GET), VERB <verb>, and/or MATCH <keyword>.");
			}
		}
		return new Filters(verb, match);
	}

	/** Parsed {@code VERB}/{@code MATCH} filter values - either may be {@code null} when not supplied. */
	public static final class Filters {
		public final String verb;
		public final String match;

		public Filters(String verb, String match) {
			this.verb = verb;
			this.match = match;
		}
	}

	/** The full {@code "Parent / Child"} folder path for {@code groupId} - {@code public} (API Quality and UX Consolidation sprint) so {@code CommandShowEndpoint}'s single-endpoint detail view can reuse it instead of duplicating the walk. */
	public static String folderPath(ApiDefinitionsVault vault, Integer groupId, Map<Integer, String> cache) throws BroadSQLException {
		if (groupId == null) {
			return "";
		}
		String cached = cache.get(groupId);
		if (cached != null) {
			return cached;
		}
		ApiEndpointGroup group = vault.findGroupById(groupId);
		if (group == null) {
			return "";
		}
		String parentPath = group.getParentGroupId() == null ? "" : folderPath(vault, group.getParentGroupId(), cache);
		String path = parentPath.isEmpty() ? group.getName() : parentPath + " / " + group.getName();
		cache.put(groupId, path);
		return path;
	}
}
