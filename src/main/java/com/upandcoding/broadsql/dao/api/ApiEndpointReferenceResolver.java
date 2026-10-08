package com.upandcoding.broadsql.dao.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.tabular.ApiEndpointListingBuilder;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultTable;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultTableRenderer;

/**
 * SPRINT XT02B, section 6: the single shared "endpoint ID / alias / unique NAME" token resolver for
 * {@code SHOW ENDPOINT}, {@code SYNTAX}, and {@code HELP} - deliberately <b>not</b> wired into
 * {@code RUN}, which stays URL-only per XT02A's own "full replace" decision. Named {@code
 * ApiEndpointReferenceResolver} rather than reusing {@code dao.api.invocation.ApiEndpointResolver}'s
 * name, since that class does something unrelated: structural HTTP-method + path-segment matching for
 * {@code RUN}'s URL-native execution, not a token lookup.
 *
 * <p>Resolution order: a purely-numeric token is tried as a global {@code API_ENDPOINT.ID} first (but
 * only accepted if it actually belongs to {@code apiId} - {@link ApiDefinitionsVault#findEndpointById}
 * itself is unscoped, so a numeric id belonging to a <i>different</i> API must not silently resolve
 * here); then as an alias (unique per API); then as a name (not guaranteed unique - two or more matches
 * are reported as {@link Ambiguous} rather than guessed at).
 */
public final class ApiEndpointReferenceResolver {

	private ApiEndpointReferenceResolver() {
	}

	/** One of {@link Found}, {@link Ambiguous}, or {@link NotFound}. */
	public sealed interface Result permits Found, Ambiguous, NotFound {
	}

	public record Found(ApiEndpoint endpoint) implements Result {
	}

	public record Ambiguous(List<ApiEndpoint> candidates) implements Result {
	}

	public record NotFound() implements Result {
	}

	/**
	 * {@code apiId} may be blank/{@code null} when no {@code CONNECT API} session is active - the
	 * numeric-id path still works in that case (a plain, global {@code API_ENDPOINT.ID} lookup, exactly
	 * {@code SHOW ENDPOINT <id>}'s pre-XT02B behavior, which never required a session); alias/name
	 * resolution, by contrast, is inherently scoped to one API and returns {@link NotFound} without an
	 * {@code apiId} to resolve against.
	 */
	public static Result resolve(ApiDefinitionsVault vault, String apiId, String token) throws BroadSQLException {
		if (StringUtils.isBlank(token)) {
			return new NotFound();
		}
		String trimmed = token.trim();

		if (StringUtils.isNumeric(trimmed)) {
			ApiEndpoint byId = vault.findEndpointById(Integer.parseInt(trimmed));
			if (byId == null) {
				return new NotFound();
			}
			if (StringUtils.isBlank(apiId)) {
				// No active session to scope against - preserve the pre-XT02B behavior of a plain global lookup.
				return new Found(byId);
			}
			// A numeric id that doesn't belong to apiId falls through as NotFound - it is deliberately not
			// tried as an alias/name too, since a purely numeric alias/name would be an unusual,
			// ambiguity-inviting choice.
			return apiId.equalsIgnoreCase(vault.findApiIdForVersion(byId.getApiVersionId())) ? new Found(byId) : new NotFound();
		}

		if (StringUtils.isBlank(apiId)) {
			return new NotFound();
		}

		ApiEndpoint byAlias = vault.findEndpointByAlias(apiId, trimmed);
		if (byAlias != null) {
			return new Found(byAlias);
		}

		List<ApiEndpoint> byName = vault.findEndpointByName(apiId, trimmed);
		if (byName.size() == 1) {
			return new Found(byName.get(0));
		}
		if (byName.size() > 1) {
			return new Ambiguous(byName);
		}

		return new NotFound();
	}

	/**
	 * Renders an {@link Ambiguous} result's candidates as a bordered table (ID/METHOD/FOLDER/NAME/ALIAS/
	 * PATH), the format every caller (SHOW ENDPOINT/SYNTAX/HELP) shows when asked to resolve a name that
	 * matches more than one endpoint - one shared rendering so the three callers can never drift apart,
	 * matching a message telling the user to use the ID or alias instead.
	 */
	public static String renderAmbiguous(ApiDefinitionsVault vault, List<ApiEndpoint> candidates) throws BroadSQLException {
		List<String> columns = List.of("ID", "METHOD", "FOLDER", "NAME", "ALIAS", "PATH");
		Map<Integer, String> folderPathCache = new HashMap<>();
		List<Map<String, String>> rows = new ArrayList<>();
		for (ApiEndpoint endpoint : candidates) {
			Map<String, String> row = new LinkedHashMap<>();
			row.put("ID", String.valueOf(endpoint.getId()));
			row.put("METHOD", StringUtils.defaultString(endpoint.getMethod()));
			row.put("FOLDER", ApiEndpointListingBuilder.folderPath(vault, endpoint.getGroupId(), folderPathCache));
			row.put("NAME", StringUtils.defaultString(endpoint.getName()));
			row.put("ALIAS", StringUtils.defaultString(endpoint.getAlias()));
			row.put("PATH", StringUtils.defaultString(endpoint.getEndpointPath()));
			rows.add(row);
		}
		ApiResultTable table = new ApiResultTable(columns, rows, true, null);
		return ApiResultTableRenderer.renderCatalog(table)
				+ "\nMore than one endpoint matches that name. Use the ID or alias instead.";
	}
}
