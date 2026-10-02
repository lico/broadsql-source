package com.upandcoding.broadsql.dao.api.invocation;

import java.util.ArrayList;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Matches an HTTP method + incoming path against the active API's configured endpoints - SPRINT
 * XT02A (URL-Native API Execution), section 5/6.2: matching is purely structural (segment count,
 * literal-segment equality), entirely independent of whether an incoming segment is a literal value
 * or a {@code :name} placeholder - endpoint identity must be established <i>before</i> any
 * placeholder is resolved (section 6.2's "critical architectural rule").
 */
public class ApiEndpointResolver {

	private final ApiDefinitionsVault vault;

	public ApiEndpointResolver(ApiDefinitionsVault vault) {
		this.vault = vault;
	}

	/**
	 * Route specificity ranking (all candidates already match the request structurally): a static segment
	 * outranks a placeholder at the same position ({@link ApiPathTemplate#isMoreSpecificThan}). The winner
	 * is the one candidate strictly more specific than every other; {@code null} when no such unique best
	 * exists (equally specific or incomparable routes), which the caller reports as ambiguous. Never depends
	 * on insertion order, row id, name, or literal text such as "list".
	 */
	static ApiEndpoint mostSpecific(List<ApiEndpoint> matches) {
		for (ApiEndpoint candidate : matches) {
			ApiPathTemplate template = ApiPathTemplate.parse(candidate.getEndpointPath());
			boolean beatsAll = true;
			for (ApiEndpoint other : matches) {
				if (other != candidate && !template.isMoreSpecificThan(ApiPathTemplate.parse(other.getEndpointPath()))) {
					beatsAll = false;
					break;
				}
			}
			if (beatsAll) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * @param method           the HTTP method - already defaulted to {@code GET} by the caller if omitted
	 * @param incomingSegments  the RUN URL's base path, already split via {@link ApiPathTemplate#segmentize}
	 * @throws BroadSQLException with a user-facing diagnostic (section 21) when there is no active/matching
	 *         endpoint, or more than one configured endpoint matches (ambiguous configuration - section 5's
	 *         "prevent or flag the configuration as invalid", checked defensively here too)
	 */
	public ApiEndpoint resolve(String apiId, String method, List<String> incomingSegments) throws BroadSQLException {
		ApiVersion version = vault.getDefaultVersion(apiId);
		List<ApiEndpoint> candidates = version == null ? List.of() : vault.getEndpointsForVersion(version.getId());

		List<ApiEndpoint> methodMatches = new ArrayList<>();
		List<ApiEndpoint> pathOnlyMatches = new ArrayList<>();
		for (ApiEndpoint candidate : candidates) {
			if (!DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(candidate.getStatusId())) {
				continue;
			}
			if (!ApiPathTemplate.parse(candidate.getEndpointPath()).matches(incomingSegments)) {
				continue;
			}
			pathOnlyMatches.add(candidate);
			if (method.equalsIgnoreCase(candidate.getMethod())) {
				methodMatches.add(candidate);
			}
		}

		if (methodMatches.size() == 1) {
			return methodMatches.get(0);
		}
		if (methodMatches.size() > 1) {
			ApiEndpoint best = mostSpecific(methodMatches);
			if (best != null) {
				return best;
			}
			StringBuilder tied = new StringBuilder();
			for (ApiEndpoint candidate : methodMatches) {
				tied.append("\n  ").append(candidate.getMethod()).append(" ")
						.append(ApiPathTemplate.parse(candidate.getEndpointPath()).toCanonicalDisplay());
			}
			throw new BroadSQLException("Ambiguous endpoint configuration: more than one " + method + " endpoint matches this URL for API '"
					+ apiId + "'. Fix the duplicate endpoint definitions in CONFIG API. Equally specific candidates:" + tied);
		}
		String requestedPath = "/" + String.join("/", incomingSegments);
		if (!pathOnlyMatches.isEmpty()) {
			StringBuilder suggestions = new StringBuilder();
			for (ApiEndpoint candidate : pathOnlyMatches) {
				suggestions.append("\n  ").append(candidate.getMethod()).append(" ")
						.append(ApiPathTemplate.parse(candidate.getEndpointPath()).toCanonicalDisplay());
			}
			throw new BroadSQLException("No " + method + " endpoint matches:\n  " + requestedPath + "\n\nPossible matches:" + suggestions);
		}
		throw new BroadSQLException("No endpoint matches:\n  " + requestedPath
				+ "\n\nUse SHOW ENDPOINTS API " + apiId + " to see configured endpoints for this API.");
	}
}
