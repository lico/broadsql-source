package com.upandcoding.broadsql.dao.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * Resolves the effective set of {@code VARIABLE}-kind {@link ApiAttribute} values for an endpoint,
 * applying the deterministic, most-specific-wins precedence documented in
 * docs/SPRINT XT02 - Universal API Client.md, section 13.4:
 *
 * <pre>
 * API variables
 *     -&gt; environment variables (selected environment only)
 *     -&gt; group/folder variables (root-to-leaf along the endpoint's group chain)
 *     -&gt; endpoint variables
 *     -&gt; runtime overrides (not persisted - supplied by the caller, sub-sprint 4)
 * </pre>
 *
 * <p>Disabled attributes ({@link ApiAttribute#isEnabled()} {@code false}) are never included - same
 * semantics as Bruno's own enabled/disabled toggle. Only {@link ApiAttributeKind#VARIABLE} rows go
 * through this resolver; headers and query parameters have their own precedence (section 5 of the
 * sprint doc), unrelated to this class.
 */
public class ApiVariableResolver {

	private final ApiDefinitionsVault vault;

	public ApiVariableResolver(ApiDefinitionsVault vault) {
		this.vault = vault;
	}

	/**
	 * @param apiId         owning API - variables at this scope apply regardless of environment/endpoint
	 * @param environment   the selected environment, or {@code null} if none is selected (that scope is
	 *                      then simply skipped)
	 * @param endpoint      the endpoint being resolved for, or {@code null} to resolve only the
	 *                      API/environment/group-independent scopes
	 * @param runtimeOverrides values supplied by the caller at execution time - always win, never persisted
	 * @return the effective variable map, most-specific value per name
	 */
	public Map<String, String> resolve(String apiId, ApiEnvironment environment, ApiEndpoint endpoint, Map<String, String> runtimeOverrides)
			throws BroadSQLException {
		Map<String, String> result = new LinkedHashMap<>();

		applyAttributes(result, vault.getAttributes(ApiOwnerType.API, apiId, ApiAttributeKind.VARIABLE));

		if (environment != null) {
			applyAttributes(result, vault.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(environment.getId()), ApiAttributeKind.VARIABLE));
		}

		if (endpoint != null && endpoint.getGroupId() != null) {
			for (ApiEndpointGroup group : vault.groupChainRootToLeaf(endpoint.getGroupId())) {
				applyAttributes(result, vault.getAttributes(ApiOwnerType.GROUP, String.valueOf(group.getId()), ApiAttributeKind.VARIABLE));
			}
		}

		if (endpoint != null) {
			applyAttributes(result, vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.VARIABLE));
		}

		if (runtimeOverrides != null) {
			result.putAll(runtimeOverrides);
		}

		return result;
	}// resolve

	private void applyAttributes(Map<String, String> result, List<ApiAttribute> attributes) {
		for (ApiAttribute attr : attributes) {
			if (attr.isEnabled()) {
				result.put(attr.getName(), attr.getValue());
			}
		}
	}
}
