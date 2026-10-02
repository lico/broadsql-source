package com.upandcoding.broadsql.dao.api.invocation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A single-segment-per-parameter model of an endpoint's stored path template (e.g.
 * {@code /api/customer/${id}}) - SPRINT XT02A (URL-Native API Execution), used for endpoint matching
 * (docs/SPRINT_XT02A_URL_NATIVE_API_EXECUTION.md, section 5/6.2) and canonical colon-style display
 * (section 2.3/3.4). A path segment is a parameter only when it is <i>exactly</i> {@code ${name}},
 * {@code {{name}}}, or {@code :name} (SPRINT XT02B acceptance correction: real Bruno {@code .bru}
 * files use the bare colon form natively for a path parameter - distinct from Bruno's own mustache
 * {@code {{name}}} variable interpolation - and {@link com.upandcoding.broadsql.dao.api.bruno.BrunoPlaceholderNormalizer}
 * only ever normalized the latter, so an imported endpoint's stored path can legitimately still
 * contain a literal {@code :name} segment; this class must recognize it as a parameter, not a
 * literal, or endpoint matching/binding silently treats it as opaque text - see
 * {@code ApiParameterBinder} and {@code ApiEndpointRequestBuilder#normalizeColonSegments}). A
 * partial-segment template (e.g. {@code cust-${id}}) is treated as a literal segment for matching
 * purposes, a documented simplification (see docs/TECHNICAL_CHANGE.md).
 *
 * <p>A Bruno-imported endpoint's template may already be absolute, prefixed with the structural
 * {@code baseUrl} variable - either {@code ${baseUrl}} or Bruno's own mustache spelling
 * {@code {{baseUrl}}} (both are accepted throughout this codebase, see
 * {@code ApiVariableSubstitutor} and docs/TECHNICAL_CHANGE.md, SPRINT XT02-7B; e.g.
 * {@code {{baseUrl}}/users/{{id}}} or {@code ${baseUrl}/users/${id}} - see
 * {@code ApiEndpointRequestBuilder}'s own class javadoc). That prefix is not a user-facing RUN
 * placeholder, so it is stripped before segmenting; a {@code {{name}}}/{@code ${name}}/{@code :name}
 * segment elsewhere in the path is likewise recognized as a parameter either way - all combinations
 * match and display identically for the URL-native engine.
 */
public final class ApiPathTemplate {

	private static final Pattern FULL_SEGMENT_PARAM = Pattern.compile("^(?:\\$\\{([A-Za-z0-9_]+)\\}|\\{\\{([A-Za-z0-9_]+)\\}\\}|:([A-Za-z0-9_]+))$");
	private static final Pattern BASE_URL_PREFIX = Pattern.compile("^(?:\\$\\{baseUrl\\}|\\{\\{baseUrl\\}\\})");

	private final List<String> segments;

	private ApiPathTemplate(List<String> segments) {
		this.segments = segments;
	}

	public static ApiPathTemplate parse(String basePath) {
		String normalized = basePath == null ? "" : basePath.trim();
		normalized = BASE_URL_PREFIX.matcher(normalized).replaceFirst("");
		// Route identity is path-only: a stored path may still embed query text ("...?key=") or a fragment
		// (legacy/unmigrated data, see JApiEndpointEditorPanel), which must never take part in matching.
		int query = normalized.indexOf('?');
		int fragment = normalized.indexOf('#');
		int suffixStart = query < 0 ? fragment : (fragment < 0 ? query : Math.min(query, fragment));
		if (suffixStart >= 0) {
			normalized = normalized.substring(0, suffixStart);
		}
		return new ApiPathTemplate(segmentize(normalized));
	}

	/**
	 * Splits a path string into its {@code /}-separated segments (leading/trailing slash ignored,
	 * consecutive separators preserved as empty segments) - the same rule an incoming RUN URL's base
	 * path is segmented with, so a template and a RUN-typed URL are always compared on equal terms.
	 */
	public static List<String> segmentize(String path) {
		String normalized = path == null ? "" : path.trim();
		if (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		if (normalized.endsWith("/") && normalized.length() > 1) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		List<String> segments = new ArrayList<>();
		if (!normalized.isEmpty()) {
			for (String segment : normalized.split("/", -1)) {
				segments.add(segment);
			}
		}
		return segments;
	}

	public int segmentCount() {
		return segments.size();
	}

	/**
	 * Rewrites every bare {@code :name} colon-style path segment in {@code path} to BroadSQL's internal
	 * {@code ${name}} form, leaving every other segment (including a {@code ${baseUrl}}/{@code {{baseUrl}}}
	 * prefix) untouched - used by {@code ApiEndpointRequestBuilder} right before
	 * {@code ApiVariableSubstitutor#substitute}, which only recognizes {@code ${name}}/{@code {{name}}}
	 * and has no idea a colon-style segment is a placeholder at all. Without this, a stored path
	 * template using Bruno's native {@code :name} path-parameter syntax (see this class's own javadoc)
	 * would resolve its parameter value correctly for matching/binding purposes but the literal
	 * {@code :name} text would still be sent to the remote API verbatim, unsubstituted, in the actual
	 * outgoing request URL.
	 */
	public static String normalizeColonSegments(String path) {
		if (path == null) {
			return null;
		}
		String[] parts = path.split("/", -1);
		for (int i = 0; i < parts.length; i++) {
			Matcher m = FULL_SEGMENT_PARAM.matcher(parts[i]);
			if (m.matches() && m.group(3) != null) {
				parts[i] = "${" + m.group(3) + "}";
			}
		}
		return String.join("/", parts);
	}

	public boolean isParameterSegment(int index) {
		return parameterName(index) != null;
	}

	/** The raw segment text at {@code index}, exactly as stored (e.g. {@code "customer"} or {@code "${id}"}). */
	public String segment(int index) {
		return segments.get(index);
	}

	/** The parameter name at {@code index} (without {@code ${}}/{@code {{}}}/{@code :}), or {@code null} if that segment is literal. */
	public String parameterName(int index) {
		Matcher m = FULL_SEGMENT_PARAM.matcher(segments.get(index));
		if (!m.matches()) {
			return null;
		}
		if (m.group(1) != null) {
			return m.group(1);
		}
		return m.group(2) != null ? m.group(2) : m.group(3);
	}

	/** Every parameter name, in template order (left to right). */
	public List<String> parameterNamesInOrder() {
		List<String> names = new ArrayList<>();
		for (int i = 0; i < segments.size(); i++) {
			String name = parameterName(i);
			if (name != null) {
				names.add(name);
			}
		}
		return names;
	}

	/**
	 * Structural match against an incoming path's segments (section 6.2: endpoint resolution happens
	 * on structure alone - segment count and literal-segment equality - never on placeholder values).
	 * An incoming segment may be a literal value, a {@code :name} placeholder, or an already-resolved
	 * {@code ${ENV:...}} value; all three are structurally opaque to this check.
	 */
	public boolean matches(List<String> incomingSegments) {
		if (incomingSegments.size() != segments.size()) {
			return false;
		}
		for (int i = 0; i < segments.size(); i++) {
			if (!isParameterSegment(i) && !segments.get(i).equals(incomingSegments.get(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Route specificity: {@code true} when this template is strictly more specific than {@code other}, i.e.
	 * it has a literal segment at every position where {@code other} has one, and at least one more
	 * literal segment where {@code other} has a parameter placeholder. This is a partial order: two
	 * routes that each pin a literal where the other has a placeholder (e.g. {@code /a/b/:x} and
	 * {@code /a/:y/c}) are incomparable, and so are two routes with the same literal positions (e.g.
	 * {@code /s/:sender} and {@code /s/:id}); neither is "more specific", so a request matching both
	 * stays ambiguous. Only meaningful for two templates of the same segment count.
	 */
	public boolean isMoreSpecificThan(ApiPathTemplate other) {
		if (segments.size() != other.segments.size()) {
			return false;
		}
		boolean strictlyBetter = false;
		for (int i = 0; i < segments.size(); i++) {
			boolean literal = !isParameterSegment(i);
			boolean otherLiteral = !other.isParameterSegment(i);
			if (otherLiteral && !literal) {
				return false;
			}
			if (literal && !otherLiteral) {
				strictlyBetter = true;
			}
		}
		return strictlyBetter;
	}

	/** The canonical, colon-style display form (section 2.3) - {@code ${id}}/{@code {{id}}} becomes {@code :id}. */
	public String toCanonicalDisplay() {
		if (segments.isEmpty()) {
			return "/";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < segments.size(); i++) {
			sb.append('/');
			String name = parameterName(i);
			sb.append(name != null ? ":" + name : segments.get(i));
		}
		return sb.toString();
	}
}
