package com.upandcoding.broadsql.dao.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.upandcoding.broadsql.dao.api.model.ApiAttribute;

/**
 * Splits a URL/path template into its base path, query parameters, and fragment, and composes those
 * three parts back into a single displayed/editable string - the shared low-level string
 * manipulation both {@link com.upandcoding.broadsql.dao.api.execution.ApiEndpointRequestBuilder} (request
 * execution) and the {@code CONFIG API} endpoint editor (GUI synchronization) build on, so the two
 * can never drift apart on what "the URL" means.
 *
 * <p>Deliberately not based on {@link java.net.URI}: a {@code ${variable}}/{@code {{variable}}}
 * placeholder is not valid URI syntax in every position it can legitimately appear in a BroadSQL
 * endpoint template, and {@code URI}'s parsing/encoding would corrupt it. This class does plain,
 * conservative string splitting - no percent-decoding, no re-encoding - so every placeholder survives
 * a split/compose round-trip byte-for-byte. Percent-encoding of the final, resolved values happens
 * exactly once, later, in {@link com.upandcoding.broadsql.dao.api.http.ApiHttpRequest#buildUri()}.
 *
 * <p>Per the sprint's persistence invariant: a persisted {@code ApiEndpoint.endpointPath} is the base
 * path (plus an optional fragment - there is no separate persisted fragment column) only, never a
 * composed query string; query parameters live exclusively as structured {@code QUERY_PARAMETER}
 * {@link ApiAttribute} rows. The composed form this class produces is a display/edit convenience,
 * never itself persisted verbatim.
 */
public final class ApiUrlQueryStringSync {

	private ApiUrlQueryStringSync() {
	}

	/**
	 * Splits {@code url} into its base path (before the first {@code ?}, or before the first
	 * {@code #} if there is no {@code ?}), its query parameters (in order, duplicates preserved), and
	 * its fragment (after the first {@code #}, excluding the {@code #} itself; {@code null} if
	 * absent). No URL-decoding is performed - a raw query pair is split on its <b>first</b> {@code =}
	 * only, so a value that itself contains {@code =} (plausible in a {@code ${...}}-templated value)
	 * is never truncated. A token with no {@code =} at all yields a {@code null} value, distinct from
	 * the empty-string value an explicit {@code name=} yields.
	 */
	public static UrlParts split(String url) {
		if (url == null) {
			return new UrlParts("", List.of(), null);
		}
		int hashIndex = url.indexOf('#');
		String fragment = hashIndex >= 0 ? url.substring(hashIndex + 1) : null;
		String withoutFragment = hashIndex >= 0 ? url.substring(0, hashIndex) : url;

		int queryIndex = withoutFragment.indexOf('?');
		String basePath = queryIndex >= 0 ? withoutFragment.substring(0, queryIndex) : withoutFragment;
		String queryString = queryIndex >= 0 ? withoutFragment.substring(queryIndex + 1) : "";

		List<RawQueryParam> params = new ArrayList<>();
		if (!queryString.isEmpty()) {
			for (String pair : queryString.split("&", -1)) {
				if (pair.isEmpty()) {
					continue;
				}
				int eq = pair.indexOf('=');
				if (eq >= 0) {
					params.add(new RawQueryParam(pair.substring(0, eq), pair.substring(eq + 1)));
				} else {
					params.add(new RawQueryParam(pair, null));
				}
			}
		}
		return new UrlParts(basePath, params, fragment);
	}

	/**
	 * Rebuilds the displayed/editable URL as {@code basePath ? query # fragment}, in that fixed
	 * order - the inverse of {@link #split}. Only {@code enabled} attributes are expected to be
	 * passed in {@code enabledQueryParamsInOrder} (callers filter before calling, so this method never
	 * has to reason about enabled/disabled state itself); order is preserved exactly as given, so
	 * duplicate names stay in their original relative positions.
	 */
	public static String compose(String basePath, List<ApiAttribute> enabledQueryParamsInOrder, String fragment) {
		StringBuilder result = new StringBuilder(basePath == null ? "" : basePath);
		if (enabledQueryParamsInOrder != null && !enabledQueryParamsInOrder.isEmpty()) {
			result.append('?');
			boolean first = true;
			for (ApiAttribute param : enabledQueryParamsInOrder) {
				if (!first) {
					result.append('&');
				}
				first = false;
				result.append(param.getName()).append('=').append(param.getValue() == null ? "" : param.getValue());
			}
		}
		if (fragment != null && !fragment.isEmpty()) {
			result.append('#').append(fragment);
		}
		return result.toString();
	}

	/**
	 * Groups {@code params} by name, preserving both the relative order of distinct names and the
	 * relative order of same-named occurrences within each group - the shared building block for
	 * duplicate-name-aware reconciliation (matching an embedded/URL-typed occurrence to the
	 * corresponding structured {@code QUERY_PARAMETER} row by position within its name's group,
	 * rather than by name alone, since BroadSQL's model has no uniqueness constraint on query
	 * parameter names).
	 */
	public static Map<String, List<RawQueryParam>> groupByName(List<RawQueryParam> params) {
		Map<String, List<RawQueryParam>> grouped = new LinkedHashMap<>();
		for (RawQueryParam param : params) {
			grouped.computeIfAbsent(param.name, k -> new ArrayList<>()).add(param);
		}
		return grouped;
	}

	/** The three parts a URL/path template splits into. See {@link #split}. */
	public static final class UrlParts {
		public final String basePath;
		public final List<RawQueryParam> queryParams;
		public final String fragment;

		public UrlParts(String basePath, List<RawQueryParam> queryParams, String fragment) {
			this.basePath = basePath;
			this.queryParams = queryParams;
			this.fragment = fragment;
		}
	}

	/** One raw, undecoded {@code name=value} (or bare {@code name}) query token. See {@link #split}. */
	public static final class RawQueryParam {
		public final String name;
		public final String value;

		public RawQueryParam(String name, String value) {
			this.name = name;
			this.value = value;
		}
	}
}
