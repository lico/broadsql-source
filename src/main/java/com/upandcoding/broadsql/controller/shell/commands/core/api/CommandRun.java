package com.upandcoding.broadsql.controller.shell.commands.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.LastApiExecutionResult;
import com.upandcoding.broadsql.dao.LastApiExecutionResultHolder;
import com.upandcoding.broadsql.dao.LastCopyableResultHolder;
import com.upandcoding.broadsql.dao.api.ApiEndpointReferenceResolver;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.ApiUrlQueryStringSync;
import com.upandcoding.broadsql.dao.api.ApiVariableResolver;
import com.upandcoding.broadsql.dao.api.ApiVariableSubstitutor;
import com.upandcoding.broadsql.dao.api.execution.ApiEndpointExecutor;
import com.upandcoding.broadsql.dao.api.execution.ApiExecutionResult;
import com.upandcoding.broadsql.dao.api.execution.ApiResponseRenderer;
import com.upandcoding.broadsql.dao.api.execution.ApiResultDisplayMode;
import com.upandcoding.broadsql.dao.api.invocation.ApiEndpointResolver;
import com.upandcoding.broadsql.dao.api.invocation.ApiParameterBinder;
import com.upandcoding.broadsql.dao.api.invocation.ApiPathTemplate;
import com.upandcoding.broadsql.dao.api.invocation.EnvVarResolver;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.tabular.ApiJsonTabularizer;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultTable;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * {@code RUN [HTTP_METHOD] <relative-api-url> [TABLE|RAW];} - SPRINT XT02A (URL-Native API Execution),
 * the sole canonical execution command, replacing this class's own earlier id/alias-based grammar
 * (API Quality and UX Consolidation sprint, 2026-09-14).
 *
 * <p><b>This is an intentional, documented breaking change</b> (docs/TECHNICAL_CHANGE.md): the
 * previous {@code RUN <endpointId-or-alias> [API <apiId>] [ENV <environment>] [TABLE|RAW];} grammar
 * is fully replaced, not extended - {@code RUN CUST;} (a bare alias) is no longer executed and is now
 * explicitly rejected with a hint toward {@code SYNTAX}/tab-completion (section 2.3), and {@code RUN}
 * no longer accepts explicit {@code API <apiId>}/{@code ENV <environment>} clauses - the active
 * {@code CONNECT API} session is now the sole source of execution context (section 2.4/3.1). The
 * retired, hidden {@code EXECUTE API ENDPOINT} keyword ({@link CommandApiExecuteEndpoint}) is left
 * untouched as a legacy/compatibility execution path (spec section 23, point 2/3) - it is not part of
 * this class and shares no code with it.
 *
 * <p>{@code HTTP_METHOD} is optional and defaults to {@code GET} (section 2.2). The URL is resolved
 * against the currently connected API (section 2.4) - {@code RUN} fails clearly if none is connected.
 * A {@code :name} path/query placeholder is bound via session {@code VAR} &gt; persisted {@code CONFIG
 * API} value &gt; endpoint default &gt; error (section 7.3); a literal value is used and validated
 * as-is. {@code ${ENV:NAME}} references an operating-system environment variable, resolved at
 * invocation time (section 3.3, {@link EnvVarResolver} - namespaced {@code ENV:} form, a documented
 * deviation from the spec's original {@code ${NAME}} wording to avoid colliding with the pre-existing,
 * unrelated {@code ${var}} API-variable templating already used inside a stored endpoint's own
 * definition). The optional trailing {@code TABLE}/{@code RAW} clause preserves the previous
 * rendering-mode choice, additively, on the new grammar (not present in the original spec text -
 * an explicit product decision, documented in docs/TECHNICAL_CHANGE.md).
 */
public class CommandRun extends Command {

	public CommandRun() {
		super("RUN");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (args == null || args.length < 1) {
			console.error("Usage: " + getExamples());
			return;
		}

		String methodToken;
		String urlToken;
		int nextIndex;
		if (args[0].startsWith("/")) {
			methodToken = null;
			urlToken = args[0];
			nextIndex = 1;
		} else if (args.length >= 2 && args[1].startsWith("/")) {
			methodToken = args[0];
			urlToken = args[1];
			nextIndex = 2;
		} else {
			LastApiExecutionResultHolder.clear();
			ApiSessionContext aliasContext = ApiSessionContextHolder.get();
			if (aliasContext == null) {
				console.error("No API is connected.\nUse CONNECT API <api>:<environment>; before RUN.");
				return;
			}
			// SPRINT XT02B, section 5: reuse the same id/alias/name scope SHOW ENDPOINT/SYNTAX/HELP already
			// resolve through (ApiEndpointReferenceResolver) purely to improve this diagnostic - RUN itself
			// still never executes a bare reference directly, only its wording now covers name/id too, not
			// only alias, since most imported endpoints never have an alias set at all.
			ApiEndpointReferenceResolver.Result resolved = ApiEndpointReferenceResolver.resolve(getApiDefinitionsVault(), aliasContext.getApiId(), args[0]);
			if (resolved instanceof ApiEndpointReferenceResolver.Found) {
				console.error("'" + args[0] + "' is an endpoint reference, not a URL.\nRUN executes a URL. Use TAB to expand an "
						+ "endpoint ID, alias or name to its URL, or use SYNTAX " + args[0] + "; to see it.");
			} else if (resolved instanceof ApiEndpointReferenceResolver.Ambiguous ambiguous) {
				console.error("'" + args[0] + "' is not a URL.\n\n" + ApiEndpointReferenceResolver.renderAmbiguous(getApiDefinitionsVault(), ambiguous.candidates()));
			} else {
				console.error("Usage: " + getExamples());
			}
			return;
		}

		ApiResultDisplayMode mode = ApiResultDisplayMode.LIST;
		for (int i = nextIndex; i < args.length; i++) {
			if (i == args.length - 1 && ("TABLE".equalsIgnoreCase(args[i]) || "RAW".equalsIgnoreCase(args[i]))) {
				mode = "RAW".equalsIgnoreCase(args[i]) ? ApiResultDisplayMode.RAW : ApiResultDisplayMode.TABLE;
			} else {
				console.error("Usage: " + getExamples());
				return;
			}
		}

		String method = methodToken == null ? "GET" : methodToken.toUpperCase();

		// Invalidate any previously held snapshot as soon as a syntactically valid RUN attempt begins -
		// see the equivalent comment/finding this preserves from the previous grammar (SPRINT XT02-7B
		// corrective patch, Codex finding 2).
		LastApiExecutionResultHolder.clear();

		ApiSessionContext context = ApiSessionContextHolder.get();
		if (context == null) {
			console.error("No API is connected.\nUse CONNECT API <api>:<environment>; before RUN.");
			return;
		}
		String apiId = context.getApiId();

		if (!getApiDefinitionsVault().contains(apiId)) {
			ApiDefinition apiRow = getApiDefinitionsVault().findApiById(apiId);
			if (apiRow != null) {
				console.error("API '" + apiId + "' is inactive. Reactivate it first (CONFIG API), or use SHOW ALL APIS to list active APIs.");
			} else {
				console.error("API '" + apiId + "' not found. Use SHOW ALL APIS to list imported APIs.");
			}
			return;
		}

		// SPRINT XT02B, section 3/4: the environment must be resolved BEFORE the URL is fully resolved
		// and BEFORE endpoint matching, since a ${name}/{{name}} placeholder typed directly in the RUN
		// URL (section 4.1/4.2) is resolved against this same API+Environment variable scope, and a
		// ${var} could plausibly appear in a path segment that determines which endpoint matches.
		ApiEnvironment environment = getApiDefinitionsVault().getEnvironmentsForApi(apiId).stream()
				.filter(e -> context.getEnvironmentName().equals(e.getName())).findFirst().orElse(null);
		if (environment == null) {
			console.error("Environment '" + context.getEnvironmentName() + "' not found for API '" + apiId
					+ "'. Use SHOW API ENVIRONMENTS " + apiId + " to find it.");
			return;
		}
		if (!DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(environment.getStatusId())) {
			console.error("Environment '" + context.getEnvironmentName() + "' for API '" + apiId
					+ "' is inactive. Reactivate it first (CONFIG API), or use SHOW API ENVIRONMENTS " + apiId + " to check its status.");
			return;
		}

		String resolvedUrl;
		try {
			// ${ENV:NAME} (OS environment variables) first - it gives the more actionable "undefined env
			// var" error before any API-variable substitution runs. Order is safe either way: the two
			// patterns are textually disjoint (ApiVariableSubstitutor's placeholder regex excludes ':',
			// so it never matches ${ENV:NAME}).
			resolvedUrl = EnvVarResolver.resolve(urlToken);
			// ${name}/{{name}} (API+Environment variables) - SPRINT XT02B, section 3: previously only
			// resolved inside a stored endpoint's own definition (Stage C, ApiEndpointRequestBuilder),
			// never against literal text the user types directly in the RUN URL. Endpoint/group scopes
			// are deliberately excluded here (no endpoint is known yet at this point in RUN, and
			// resolving those specifically is ApiParameterBinder's :name job, see below).
			Map<String, String> apiEnvironmentVariables = new ApiVariableResolver(getApiDefinitionsVault()).resolve(apiId, environment, null, null);
			resolvedUrl = ApiVariableSubstitutor.substitute(resolvedUrl, apiEnvironmentVariables, "RUN URL variable");
		} catch (BroadSQLException e) {
			console.error(e.getLocalizedMessage());
			return;
		}

		ApiUrlQueryStringSync.UrlParts urlParts = ApiUrlQueryStringSync.split(resolvedUrl);
		List<String> incomingSegments = ApiPathTemplate.segmentize(urlParts.basePath);

		ApiEndpoint endpoint;
		try {
			endpoint = new ApiEndpointResolver(getApiDefinitionsVault()).resolve(apiId, method, incomingSegments);
		} catch (BroadSQLException e) {
			console.error(e.getLocalizedMessage());
			return;
		}

		ApiParameterBinder.Binding binding;
		try {
			binding = new ApiParameterBinder(getApiDefinitionsVault())
					.bind(endpoint, ApiPathTemplate.parse(endpoint.getEndpointPath()), incomingSegments, urlParts.queryParams, apiId, environment);
		} catch (BroadSQLException e) {
			console.error(e.getLocalizedMessage());
			return;
		}

		console.writeln("");
		console.writeln("API: " + getApiDefinitionsVault().getApi(apiId).getName());
		console.writeln("Environment: " + environment.getName());
		console.writeln("Endpoint: " + endpoint.getMethod() + " " + endpoint.getName());
		console.writeln("");

		ApiExecutionResult result = new ApiEndpointExecutor(getApiDefinitionsVault())
				.executeInvocation(apiId, environment, endpoint, binding.getPathValues(), binding.getQueryValues());
		captureLastApiExecutionResult(apiId, endpoint, environment, result);
		console.printBlock(ApiResponseRenderer.render(result, mode));
	}

	/** Mirrors {@code CommandApiExecuteEndpoint#captureLastApiExecutionResult} exactly - kept as an independent copy so the legacy/compatibility command remains fully untouched by this sprint. */
	private void captureLastApiExecutionResult(String apiId, ApiEndpoint endpoint, ApiEnvironment environment, ApiExecutionResult result) {
		byte[] bodyBytes = result.getBodyBytes();
		ApiResultTable table = (bodyBytes != null && bodyBytes.length > 0)
				? ApiJsonTabularizer.tabularize(result.getBody())
				: new ApiResultTable(List.of(), List.of(), false, result.getBody());
		LastApiExecutionResult snapshot = new LastApiExecutionResult(table, result.getBody(), apiId,
				endpoint.getMethod() + " " + endpoint.getName(), environment.getName(), Instant.now(), result.getStatusCode());
		LastApiExecutionResultHolder.set(snapshot);
		// SPRINT XT02B acceptance correction, item 6: mark this as the most recent copyable result, so
		// COPY RESULT can tell it apart from a possibly-newer SQL result - see LastCopyableResultHolder's
		// own Javadoc.
		LastCopyableResultHolder.recordApi(snapshot);
	}

	@Override
	public String getDescription() {
		return "Executes an endpoint by URL against the active CONNECT API session (GET default; :name and ${ENV:NAME} placeholders)";
	}

	@Override
	public String getDetailedDescription() {
		return "URL-native execution: the relative URL is resolved against the currently connected API "
				+ "(CONNECT API <api>:<environment>; first); RUN never takes an explicit API/environment clause of its own. "
				+ "HTTP_METHOD is optional and defaults to GET; DELETE/POST/PUT/PATCH/HEAD/OPTIONS and other configured "
				+ "methods can be given explicitly, e.g. RUN DELETE /api/customer/123;. A :name segment/query value is "
				+ "resolved, in order: a matching session VAR, the current API environment's own variable, this "
				+ "endpoint's persisted CONFIG API value, its configured default, or a clear missing-parameter error; "
				+ "a literal value (e.g. 123) is used directly. ${name}/{{name}} (API/environment variable templating, "
				+ "the same syntax already used inside a stored endpoint's own definition) also resolves directly in the "
				+ "typed URL now, against the connected API's active environment, before endpoint matching happens (so it "
				+ "can affect which endpoint a URL matches). ${ENV:NAME} reads an operating-system environment variable "
				+ "at invocation time instead (undefined fails explicitly, never silently substitutes an empty string); "
				+ "this is a different, unrelated namespace from ${name}. An endpoint's id, alias (CONFIG API), or name is a "
				+ "completion/discovery shortcut only: RUN <reference>; with no query/tab-expansion is rejected with a hint "
				+ "toward SYNTAX <reference>; or RUN <reference><TAB>, never executed directly. Most imported endpoints "
				+ "never have an alias set at all, only a name and a numeric id, and completion works from any of the "
				+ "three. Query parameters are URL-native: "
				+ "only what is written in the URL is sent (plus any required parameter with no value in the URL that can "
				+ "still be resolved via VAR/persisted/default), never a configured-but-unmentioned optional query "
				+ "parameter. The optional trailing TABLE/RAW clause selects the response rendering exactly as before; "
				+ "the default remains a complete LIST view.";
	}

	@Override
	public String getArguments() {
		return "[HTTP_METHOD] <relative-api-url> (mandatory; HTTP_METHOD optional, defaults to GET; the URL is resolved "
				+ "against the active CONNECT API session); TABLE or RAW (optional, trailing, mutually exclusive; default "
				+ "is a complete LIST view)";
	}

	@Override
	public String getExamples() {
		return "RUN /api/customer/123; RUN /api/customer/123?expand=mail&showall=true; RUN DELETE /api/customer/123; "
				+ "RUN /api/customer/:id; RUN /api/customer/${ENV:CUSTOMER_ID}; RUN /api/customer/${customerId}; "
				+ "RUN /api/customer/123 RAW;";
	}
}
