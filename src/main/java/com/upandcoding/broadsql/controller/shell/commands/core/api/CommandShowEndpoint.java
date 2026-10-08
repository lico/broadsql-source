package com.upandcoding.broadsql.controller.shell.commands.core.api;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.api.ApiEndpointReferenceResolver;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.ApiUrlQueryStringSync;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;
import com.upandcoding.broadsql.dao.api.tabular.ApiEndpointListingBuilder;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;

/**
 * {@code SHOW ENDPOINT <id|alias|name>;}: the single-object detail view for one endpoint, following
 * the same {@code collection => table, single object => vertical detail view} convention as
 * {@code SHOW CONNECTION} (whose hand-rolled {@code "LABEL : value"} idiom this class matches; no
 * shared detail-renderer exists yet in this codebase).
 *
 * <p>SPRINT XT02B, section 6: the token is resolved via the shared
 * {@link com.upandcoding.broadsql.dao.api.ApiEndpointReferenceResolver}, where a numeric token is a plain,
 * global {@code API_ENDPOINT.ID} lookup (unique across every API, so this still needs no active
 * {@code CONNECT API} session for that case, exactly like before this sprint); a non-numeric token is
 * tried as an alias, then as a name, both scoped to the active session's API (so a session is required
 * for that case). A name that matches more than one endpoint is reported as an ambiguous-candidates
 * table (ID/METHOD/FOLDER/NAME/ALIAS/PATH) rather than guessed at.
 *
 * <p>The displayed {@code URL} is the <b>composed effective URL</b>: base path plus enabled query
 * parameters plus fragment, via {@link ApiUrlQueryStringSync#compose}, the same helper the endpoint
 * editor GUI and the request builder use, so all three surfaces agree on what "the URL" is. A query/
 * path parameter or header value is masked ({@code ******}) when its {@link ApiAttribute#isSecret()}
 * flag is set, the same, already-established secret convention {@code JApiAttributeTablePanel}'s
 * "Secret" column and {@link ApiAttribute#toString()} use; this command does not invent a new
 * classification scheme, and does not detect a hardcoded secret that was never flagged as one.
 * Authentication is summarized by type only (inherited, or which type is configured), never a
 * resolved credential value.
 */
public class CommandShowEndpoint extends Command {

	private static final String MASK = "******";

	public CommandShowEndpoint() {
		super("SHOW ENDPOINT", "ENDPOINT", "SHEND");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (!CommandUtils.isValidArgs(args) || args.length != 1) {
			console.error("Usage: " + getExamples());
			return;
		}
		String token = args[0];

		ApiSessionContext context = ApiSessionContextHolder.get();
		String sessionApiId = context == null ? null : context.getApiId();

		ApiEndpointReferenceResolver.Result result = ApiEndpointReferenceResolver.resolve(getApiDefinitionsVault(), sessionApiId, token);
		ApiEndpoint endpoint;
		if (result instanceof ApiEndpointReferenceResolver.Found found) {
			endpoint = found.endpoint();
		} else if (result instanceof ApiEndpointReferenceResolver.Ambiguous ambiguous) {
			console.printBlock(ApiEndpointReferenceResolver.renderAmbiguous(getApiDefinitionsVault(), ambiguous.candidates()));
			return;
		} else {
			if (StringUtils.isNumeric(token.trim())) {
				console.error("Endpoint id " + token.trim() + " not found. Use SHOW ENDPOINTS to find it.");
			} else if (sessionApiId == null) {
				console.error("'" + token + "' is not a valid endpoint id, and no API is connected to resolve it as an alias or name. "
						+ "Use CONNECT API <api>:<environment>; first, or a numeric id from SHOW ENDPOINTS.");
			} else {
				console.error("'" + token + "' does not match any endpoint id, alias, or name for the current API. Use SHOW ENDPOINTS to find it.");
			}
			return;
		}
		if (!DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(endpoint.getStatusId())) {
			console.error("Endpoint id " + endpoint.getId() + " is inactive. Reactivate it first (CONFIG API), or use SHOW ENDPOINTS to check its status.");
			return;
		}

		String apiId = findOwningApiId(endpoint.getApiVersionId());
		String apiName = apiId == null ? "(unknown)" : StringUtils.defaultIfBlank(safeApiName(apiId), apiId);
		String folder = endpoint.getGroupId() == null ? "" : ApiEndpointListingBuilder.folderPath(getApiDefinitionsVault(), endpoint.getGroupId(), new HashMap<>());

		String ownerId = String.valueOf(endpoint.getId());
		List<ApiAttribute> queryParams = getApiDefinitionsVault().getAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.QUERY_PARAMETER);
		List<ApiAttribute> pathParams = getApiDefinitionsVault().getAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.PATH_PARAMETER);
		List<ApiAttribute> headers = getApiDefinitionsVault().getAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.HEADER);
		// Masked before composing - ApiUrlQueryStringSync.compose() is a generic display/edit helper with
		// no notion of secrecy (the GUI/request-builder callers that also use it need the real value), so
		// this command must never hand it a secret's real value, or it would leak straight into the
		// composed URL line despite the "Query parameters" section below correctly masking the same value.
		List<ApiAttribute> enabledQueryParamsForDisplay = queryParams.stream()
				.filter(ApiAttribute::isEnabled)
				.map(this::maskedCopyIfSecret)
				.toList();
		String composedUrl = ApiUrlQueryStringSync.compose(endpoint.getEndpointPath(), enabledQueryParamsForDisplay, null);

		StringBuilder out = new StringBuilder();
		out.append("ID          : ").append(endpoint.getId()).append('\n');
		out.append("API         : ").append(apiName).append('\n');
		out.append("Folder      : ").append(folder.isEmpty() ? "(top level)" : folder).append('\n');
		out.append("Name        : ").append(StringUtils.defaultString(endpoint.getName())).append('\n');
		out.append("Alias       : ").append(StringUtils.defaultIfBlank(endpoint.getAlias(), "(none)")).append('\n');
		out.append("Method      : ").append(StringUtils.defaultString(endpoint.getMethod())).append('\n');
		out.append("URL         : ").append(composedUrl).append('\n');

		if (!queryParams.isEmpty()) {
			out.append('\n');
			out.append("Query parameters").append('\n');
			for (ApiAttribute param : queryParams) {
				out.append("- ").append(param.getName()).append(" : ").append(displayValue(param))
						.append("   ").append(param.isEnabled() ? "enabled" : "disabled").append('\n');
			}
		}

		if (!pathParams.isEmpty()) {
			out.append('\n');
			out.append("Path parameters").append('\n');
			for (ApiAttribute param : pathParams) {
				out.append("- ").append(param.getName()).append(" : ").append(displayValue(param))
						.append("   ").append(param.isEnabled() ? "enabled" : "disabled").append('\n');
			}
		}

		if (!headers.isEmpty()) {
			out.append('\n');
			out.append("Headers").append('\n');
			for (ApiAttribute header : headers) {
				out.append("- ").append(header.getName()).append(" : ").append(displayValue(header))
						.append(header.isEnabled() ? "" : "   disabled").append('\n');
			}
		}

		if (endpoint.getBodyMode() != null) {
			out.append('\n');
			out.append("Body (").append(endpoint.getBodyMode()).append(")").append('\n');
			out.append(StringUtils.defaultString(endpoint.getBodyContent())).append('\n');
		}

		out.append('\n');
		out.append("Authentication: ").append(authenticationSummary(ownerId));

		console.printBlock(out.toString());
	}

	private String displayValue(ApiAttribute attribute) {
		return attribute.isSecret() ? MASK : StringUtils.defaultString(attribute.getValue());
	}

	/** A shallow copy with its value replaced by {@link #MASK} when {@link ApiAttribute#isSecret()}; never mutates the original (still needed, unmasked, wherever this command reads the same attributes again). */
	private ApiAttribute maskedCopyIfSecret(ApiAttribute attribute) {
		if (!attribute.isSecret()) {
			return attribute;
		}
		ApiAttribute masked = new ApiAttribute(attribute.getOwnerType(), attribute.getOwnerId(), attribute.getKind(), attribute.getName(), MASK, true);
		masked.setEnabled(attribute.isEnabled());
		masked.setSortOrder(attribute.getSortOrder());
		return masked;
	}

	private String authenticationSummary(String endpointOwnerId) throws BroadSQLException {
		ApiAuthConfig auth = getApiDefinitionsVault().getAuth(ApiOwnerType.ENDPOINT, endpointOwnerId);
		return auth == null ? "Inherited" : auth.getAuthType().name();
	}

	/** {@code ApiEndpoint} only stores {@code apiVersionId}, not the owning API's id directly: the reverse lookup this vault has no dedicated method for. Active APIs only (an endpoint under a deactivated API is a rare edge case, reported as "(unknown)" rather than failing the whole command). */
	private String findOwningApiId(Integer apiVersionId) throws BroadSQLException {
		if (apiVersionId == null) {
			return null;
		}
		for (ApiDefinition api : getApiDefinitionsVault().getApis()) {
			ApiVersion version = getApiDefinitionsVault().getDefaultVersion(api.getId());
			if (version != null && apiVersionId.equals(version.getId())) {
				return api.getId();
			}
		}
		return null;
	}

	private String safeApiName(String apiId) {
		try {
			ApiDefinition api = getApiDefinitionsVault().getApi(apiId);
			return api == null ? null : api.getName();
		} catch (Exception ex) {
			return null;
		}
	}

	@Override
	public String getDescription() {
		return "Shows the full detail of one endpoint (URL, query/path parameters, headers, body, authentication)";
	}

	@Override
	public String getDetailedDescription() {
		return "Shows one endpoint's full detail as a vertical view (the same collection => table, single object => "
				+ "detail-view convention as SHOW CONNECTION): ID, owning API, folder, name, alias, method, and the "
				+ "composed effective URL (base path plus enabled query parameters plus fragment, the same "
				+ "composition the CONFIG API endpoint editor and RUN's request builder use). Then, where present: "
				+ "query parameters and path parameters (name, value, enabled/disabled; a value flagged secret is "
				+ "masked), headers (same masking rule), the request body (mode and full content), and an "
				+ "authentication summary (type only, or 'Inherited', never a resolved credential value). "
				+ "The argument accepts a numeric id (global, unique across every API, no active session needed), "
				+ "an alias, or a name (both scoped to the active CONNECT API session's API). A name matching more "
				+ "than one endpoint is reported as an ambiguous-candidates table instead of being guessed at; use "
				+ "the id or alias in that case.";
	}

	@Override
	public String getArguments() {
		return "<id|alias|name> (mandatory): a numeric endpoint id (SHOW ENDPOINTS to find it, no session required), "
				+ "or an alias/name (requires an active CONNECT API session)";
	}

	@Override
	public String getExamples() {
		return "SHOW ENDPOINT 17;\nSHOW ENDPOINT PINGMAIL;\nSHOW ENDPOINT findAll;";
	}

	/** SPRINT 2409K: the one argument is an endpoint of the active CONNECT API session, offered by alias (or name). */
	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.API_ENDPOINT);
	}

}
