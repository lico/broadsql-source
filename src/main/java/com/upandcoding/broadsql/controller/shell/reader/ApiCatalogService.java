package com.upandcoding.broadsql.controller.shell.reader;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.invocation.ApiEndpointResolver;
import com.upandcoding.broadsql.dao.api.invocation.ApiPathTemplate;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Read-only catalog access for interactive completion - SPRINT XT02A (URL-Native API Execution),
 * section 13's {@code ApiCatalogService}. A thin, JLine-free wrapper over {@link ApiDefinitionsVault}
 * and the existing {@link ApiEndpointResolver} (reused, not reimplemented, for query-completion's
 * endpoint matching) - kept separate from {@link CompletionService} purely so the vault-access layer
 * can be swapped/mocked independently of completion-dispatch logic in tests. Never performs a network
 * request (section 13.12) - everything here is local metadata already loaded in the vault.
 */
public class ApiCatalogService {

	private final ApiDefinitionsVault vault;

	public ApiCatalogService(ApiDefinitionsVault vault) {
		this.vault = vault;
	}

	public List<ApiSummary> listApis() {
		List<ApiSummary> result = new ArrayList<>();
		for (ApiDefinition api : vault.getApis()) {
			result.add(new ApiSummary(api.getId(), api.getDescr()));
		}
		return result;
	}

	/** Every active endpoint of {@code apiId}'s default version - {@link EndpointSummary#alias} may be blank (path completion needs every endpoint; alias completion filters non-blank itself). */
	public List<EndpointSummary> listEndpoints(String apiId) throws BroadSQLException {
		if (StringUtils.isBlank(apiId)) {
			return List.of();
		}
		ApiVersion version = vault.getDefaultVersion(apiId);
		if (version == null) {
			return List.of();
		}
		List<EndpointSummary> result = new ArrayList<>();
		for (ApiEndpoint endpoint : vault.getEndpointsForVersion(version.getId())) {
			if (!DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(endpoint.getStatusId())) {
				continue;
			}
			String canonicalPath = ApiPathTemplate.parse(endpoint.getEndpointPath()).toCanonicalDisplay();
			result.add(new EndpointSummary(String.valueOf(endpoint.getId()), endpoint.getAlias(), endpoint.getMethod(), canonicalPath, endpoint.getName()));
		}
		return result;
	}

	/** Every configured query-parameter name for the endpoint matching {@code method}+{@code pathText}, or an empty list if none matches. */
	public List<String> queryParameterNames(String apiId, String method, String pathText) throws BroadSQLException {
		ApiEndpoint endpoint = matchEndpoint(apiId, method, pathText);
		if (endpoint == null) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		for (ApiAttribute attr : vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER)) {
			names.add(attr.getName());
		}
		return names;
	}

	/** {@code paramName}'s configured allowed values for the endpoint matching {@code method}+{@code pathText}, or an empty list if none matches/none configured. */
	public List<String> allowedValues(String apiId, String method, String pathText, String paramName) throws BroadSQLException {
		ApiEndpoint endpoint = matchEndpoint(apiId, method, pathText);
		if (endpoint == null) {
			return List.of();
		}
		for (ApiAttribute attr : vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER)) {
			if (attr.getName() != null && attr.getName().equalsIgnoreCase(paramName)) {
				return attr.getAllowedValuesList();
			}
		}
		return List.of();
	}

	/** {@code null} (never throws) when nothing matches - completion has nothing useful to report either way, unlike RUN's own hard error. */
	private ApiEndpoint matchEndpoint(String apiId, String method, String pathText) {
		if (StringUtils.isBlank(apiId)) {
			return null;
		}
		try {
			List<String> segments = ApiPathTemplate.segmentize(pathText);
			return new ApiEndpointResolver(vault).resolve(apiId, method, segments);
		} catch (BroadSQLException e) {
			return null;
		}
	}

	public static final class ApiSummary {
		public final String id;
		public final String description;

		public ApiSummary(String id, String description) {
			this.id = id;
			this.description = description;
		}
	}

	public static final class EndpointSummary {
		public final String id;
		public final String alias;
		public final String method;
		public final String canonicalPath;
		public final String name;

		public EndpointSummary(String id, String alias, String method, String canonicalPath, String name) {
			this.id = id;
			this.alias = alias;
			this.method = method;
			this.canonicalPath = canonicalPath;
			this.name = name;
		}
	}
}
