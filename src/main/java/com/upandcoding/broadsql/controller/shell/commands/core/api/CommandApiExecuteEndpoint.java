package com.upandcoding.broadsql.controller.shell.commands.core.api;


import java.time.Instant;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.LastApiExecutionResult;
import com.upandcoding.broadsql.dao.LastApiExecutionResultHolder;
import com.upandcoding.broadsql.dao.LastCopyableResultHolder;
import com.upandcoding.broadsql.dao.api.execution.ApiEndpointExecutor;
import com.upandcoding.broadsql.dao.api.execution.ApiExecutionResult;
import com.upandcoding.broadsql.dao.api.execution.ApiResponseRenderer;
import com.upandcoding.broadsql.dao.api.execution.ApiResultDisplayMode;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;
import com.upandcoding.broadsql.dao.api.tabular.ApiJsonTabularizer;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultTable;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;

/**
 * Executes one imported/defined endpoint under a selected environment:
 * {@code EXECUTE API ENDPOINT <apiId> <endpointId-or-alias> <environmentName> [RAW]} - the MVP vertical slice of
 * SPRINT XT02 (Universal API Client): resolves variables (API -&gt; environment -&gt; folder chain -&gt;
 * endpoint), applies the effective authentication (inherited or explicit, including OAuth2 Client
 * Credentials), and displays the resulting status/headers/body. See docs/SPRINT XT02 - Universal API
 * Client.md, section 21.
 *
 * <p>{@code GET}/{@code HEAD}/{@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE} endpoints can execute
 * (SPRINT XT02-8 completed write-verb execution); {@code OPTIONS} and any other/unrecognized method are
 * refused before any network request, with an explicit message naming the method (the endpoint itself
 * remains fully visible via {@code SHOW ENDPOINTS} regardless). A configured request body (only
 * {@code json}/{@code text}/{@code xml}/{@code sparql} modes can be sent - a structured mode such as
 * {@code form-urlencoded}/{@code multipart-form} is refused the same way, since what is stored for those
 * modes is Bruno's own structural representation, not real wire bytes) is interpolated through the same
 * variable scope as the URL/headers and attached to the request. No URL, header, authentication, body, or
 * variable needs to be re-entered - everything comes from what was already imported/configured; switching
 * {@code <environmentName>} alone is enough to run the same endpoint against a different server/credential
 * set.
 *
 * <p>Since the XT02 overnight batch's sub-sprint 6 ("API results as tables"), a JSON body whose shape is
 * tabular (an array of objects, a single object, an empty array, or an array of primitives) is
 * automatically displayed as a console table instead of pretty-printed JSON - see
 * {@link com.upandcoding.broadsql.dao.api.tabular.ApiJsonTabularizer}. The original raw body is never
 * discarded either way. Appending the optional trailing {@code RAW} keyword forces the previous
 * pretty-JSON/raw-text rendering regardless of the body's shape; the plain 3-argument form is unchanged
 * from earlier releases whenever the body does not happen to be tabular JSON.
 *
 * <p>Since the XT02 overnight batch's sub-sprint 7 ("API result export and local snapshot"), every
 * successful execution also populates {@link com.upandcoding.broadsql.dao.LastApiExecutionResultHolder} with
 * the tabularized result, the raw body, and lightweight execution metadata - independently of {@code RAW},
 * which only affects what is displayed on screen. {@code PULL API RESULT TO <name>[.<table>] AS H2 |
 * XLSX | ODS | CSV | TXT | JSON | MD | HTML} then materializes that snapshot into whichever destination is
 * named, exactly like {@code PULL / TO ...} does for the last SQL query held in memory - see
 * {@code com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull}.
 *
 * <p><b>Retired as a public command (API Quality and UX Consolidation sprint)</b>: {@code RUN
 * <endpoint> [API <apiId>] [ENV <environment>] [RAW]} is now the sole canonical execution command,
 * fully absorbing this class's targeting capability (explicit {@code API <apiId>}/{@code ENV
 * <environment>} clauses mean {@code RUN} no longer needs an active {@code CONNECT API} session
 * either). This class remains the shared execution engine both {@code RUN} and this class's own
 * (now hidden, undocumented) {@code EXECUTE API ENDPOINT} keyword call into - {@link #executeEndpoint}
 * is the one real implementation; {@link #execute(String)} still parses the legacy 3/4-argument form
 * for whatever internal/compatibility value that retains, but is no longer registered in {@code HELP},
 * the generated command reference, or {@link com.upandcoding.broadsql.controller.shell.commands.CommandCategoryCatalog}
 * ({@link #hidden} is {@code true}) - see {@code docs/TECHNICAL_CHANGE.md}, 2026-09-14. Both paths
 * call the exact same {@link #executeEndpoint} method, so authentication, variable resolution, tabular
 * rendering, {@code RAW}, and {@code PULL API RESULT} capture always behave identically either way.
 */
public class CommandApiExecuteEndpoint extends Command {

	public CommandApiExecuteEndpoint() {
		super("EXECUTE API ENDPOINT");
		this.hidden = true;
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (args == null || args.length < 3 || args.length > 4) {
			console.error("Usage: " + getExamples());
			return;
		}
		ApiResultDisplayMode mode = ApiResultDisplayMode.LIST;
		if (args.length == 4) {
			if ("RAW".equalsIgnoreCase(args[3])) {
				mode = ApiResultDisplayMode.RAW;
			} else if ("TABLE".equalsIgnoreCase(args[3])) {
				mode = ApiResultDisplayMode.TABLE;
			} else {
				console.error("Usage: " + getExamples());
				return;
			}
		}
		executeEndpoint(args[0], args[1], args[2], mode);
	}

	/**
	 * The structured form of {@link #execute(String)}, taking already-separated arguments instead of a
	 * command-line string - what {@code RUN} (SPRINT XT02-7B corrective patch) calls directly, so an
	 * environment name containing a space or ending in the literal word {@code RAW}/{@code TABLE} can
	 * never be misparsed the way reconstructing and re-parsing a command string would risk. This is the
	 * only execution path; {@code execute(String)} above simply parses its own command-line arguments
	 * and calls this method - {@code RUN} never gets a second, divergent implementation.
	 *
	 * @param apiId           the owning API's identifier
	 * @param endpointToken   a numeric endpoint id, or a BroadSQL alias scoped to {@code apiId}
	 * @param environmentName the environment to execute against, exactly as stored (no quoting needed -
	 *                        this is a real argument, not a token from a re-parsed command line)
	 * @param mode            how to render the response body - {@link ApiResultDisplayMode#LIST} is the
	 *                        default (API Quality and UX Consolidation sprint); {@code TABLE}/{@code RAW}
	 *                        are the explicit opt-ins the caller's own trailing keyword selects
	 */
	public void executeEndpoint(String apiId, String endpointToken, String environmentName, ApiResultDisplayMode mode) throws BroadSQLException {
		// Invalidate any previously held snapshot as soon as a syntactically valid execution attempt
		// begins, so any failure below (unknown API/endpoint/environment, policy refusal, unresolved
		// variable, unsupported authentication, network error) leaves PULL API RESULT with nothing stale
		// to export - see LastApiExecutionResultHolder#clear and SPRINT XT02 verification finding 1.
		LastApiExecutionResultHolder.clear();

		apiId = apiId.toUpperCase();
		if (!getApiDefinitionsVault().contains(apiId)) {
			// contains(apiId) only ever sees ACTIVE rows (the in-memory cache is loaded with
			// WHERE STATUS_ID='ACTIVE') - so a deactivated API "disappears" from it exactly like a
			// never-imported one would. Distinguish the two so an inactive API is reported as inactive,
			// not as not found (SPRINT XT02 verification finding 4).
			ApiDefinition apiRow = getApiDefinitionsVault().findApiById(apiId);
			if (apiRow != null) {
				console.error("API '" + apiId + "' is inactive. Reactivate it first (CONFIG API), or use SHOW ALL APIS to list active APIs.");
			} else {
				console.error("API '" + apiId + "' not found. Use SHOW ALL APIS to list imported APIs.");
			}
			return;
		}

		ApiEndpoint endpoint;
		Integer endpointId;
		try {
			endpointId = Integer.valueOf(endpointToken);
		} catch (NumberFormatException e) {
			endpointId = null;
		}
		if (endpointId != null) {
			ApiVersion version = getApiDefinitionsVault().getDefaultVersion(apiId);
			List<ApiEndpoint> endpoints = version == null ? List.of() : getApiDefinitionsVault().getEndpointsForVersion(version.getId());
			Integer finalEndpointId = endpointId;
			endpoint = endpoints.stream().filter(e -> finalEndpointId.equals(e.getId())).findFirst().orElse(null);
			if (endpoint == null) {
				console.error("Endpoint id " + endpointId + " not found for API '" + apiId + "'. Use SHOW ENDPOINTS API " + apiId + " to find it.");
				return;
			}
		} else {
			// Not a numeric id - resolve as a BroadSQL alias instead (SPRINT XT02-7B, section 10.2), scoped
			// to this API: findEndpointByAlias(apiId, alias) joins on API_VERSION so it can only ever return
			// an endpoint belonging to this API.
			endpoint = getApiDefinitionsVault().findEndpointByAlias(apiId, endpointToken);
			if (endpoint == null) {
				console.error("Endpoint '" + endpointToken + "' (id or alias) not found for API '" + apiId + "'. Use SHOW ENDPOINTS API " + apiId + " to find it.");
				return;
			}
		}
		if (!DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(endpoint.getStatusId())) {
			// getEndpointsForVersion returns every status (the CONFIG API GUI/SHOW ENDPOINTS need to
			// see inactive endpoints too) - execution specifically must still refuse them (finding 4).
			console.error("Endpoint id " + endpoint.getId() + " for API '" + apiId + "' is inactive. Reactivate it first (CONFIG API), or use SHOW ENDPOINTS API "
					+ apiId + " to check its status.");
			return;
		}

		ApiEnvironment environment = getApiDefinitionsVault().getEnvironmentsForApi(apiId).stream()
				.filter(e -> environmentName.equals(e.getName())).findFirst().orElse(null);
		if (environment == null) {
			console.error("Environment '" + environmentName + "' not found for API '" + apiId + "'. Use SHOW API ENVIRONMENTS " + apiId + " to find it.");
			return;
		}
		if (!DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(environment.getStatusId())) {
			// Same reasoning as the endpoint check above - getEnvironmentsForApi returns every status too.
			console.error("Environment '" + environmentName + "' for API '" + apiId + "' is inactive. Reactivate it first (CONFIG API), or use SHOW API ENVIRONMENTS "
					+ apiId + " to check its status.");
			return;
		}

		console.writeln("");
		console.writeln("API: " + getApiDefinitionsVault().getApi(apiId).getName());
		console.writeln("Environment: " + environment.getName());
		console.writeln("Endpoint: " + endpoint.getMethod() + " " + endpoint.getName());
		console.writeln("");

		ApiExecutionResult result = new ApiEndpointExecutor(getApiDefinitionsVault()).execute(apiId, environment, endpoint, null);
		captureLastApiExecutionResult(apiId, endpoint, environment, result);
		console.printBlock(ApiResponseRenderer.render(result, mode));
	}

	/**
	 * Populates {@link LastApiExecutionResultHolder} right after a successful execution - the API-side
	 * sibling of where {@code QueryExtractorToScreen} populates {@code LastQueryResultHolder} for SQL
	 * results - so {@code PULL API RESULT TO ...} (XT02 overnight batch, sub-sprint 7) has something to
	 * materialize. Reuses {@link ApiJsonTabularizer#tabularize} rather than re-tabularizing separately
	 * from {@link ApiResponseRenderer}, so the captured snapshot and whatever was just displayed on
	 * screen are always derived from the exact same logic. A bodiless response (e.g. a successful
	 * {@code HEAD}) is captured as a non-tabular, zero-column table - there is nothing to tabularize.
	 */
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
		return "Executes an imported GET/HEAD/POST/PUT/PATCH/DELETE API endpoint under a selected environment";
	}

	@Override
	public String getDetailedDescription() {
		return "The endpoint may be identified either by its numeric id or by its BroadSQL alias (set in CONFIG "
				+ "API), unique within the given API. Executes one API endpoint - resolves its URL/query/path/headers/body from the effective "
				+ "variable scope (API, selected environment, folder chain, endpoint), applies the effective authentication "
				+ "(inherited from a folder/collection or set on the endpoint itself, including OAuth2 Client "
				+ "Credentials), sends the request, and displays status, headers and the response body. A JSON "
				+ "body whose shape is tabular (an array of objects, a single object, an empty array, or an array "
				+ "of primitives) is automatically displayed as a console table; any other body is pretty-printed "
				+ "JSON, or shown as raw text for a non-JSON response. Any HTTP status, 2xx through 5xx, is shown "
				+ "the same way, including a bodiless 204. The original raw body is always preserved "
				+ "regardless of how it is displayed. Append RAW to force the pretty-JSON/raw-text rendering "
				+ "regardless of shape. GET, HEAD, POST, PUT, PATCH, and DELETE can execute; OPTIONS and any other "
				+ "verb are refused before any network request, naming the method - the endpoint stays fully "
				+ "visible in the catalog regardless. A configured request body can only be sent for the json/"
				+ "text/xml/sparql body modes; a structured mode (form-urlencoded/multipart-form) is refused the "
				+ "same way, naming the endpoint and its body mode, since what CONFIG API stores for those modes "
				+ "is not literal wire bytes. An inactive API, endpoint, or "
				+ "environment (deactivated via CONFIG API) also refuses execution, naming it as inactive rather "
				+ "than not found; reactivate it first. Any execution attempt that does not complete successfully "
				+ "(a refused method or body mode, an unresolved variable, unsupported authentication, an inactive "
				+ "entity, or a network error) clears any previously held successful result, so PULL API RESULT TO ... "
				+ "correctly reports nothing to export until the next successful execution. No URL, header, "
				+ "authentication, body, or variable needs to be re-entered; changing <environmentName> alone runs the "
				+ "same endpoint against a different server/credential set.";
	}

	@Override
	public String getArguments() {
		return "<apiId> (mandatory); <endpointId-or-alias> (mandatory - a numeric id from SHOW ENDPOINTS API <apiId>, "
				+ "or an endpoint alias set in CONFIG API); "
				+ "<environmentName> (mandatory - see SHOW API ENVIRONMENTS; quote if it contains spaces); "
				+ "TABLE or RAW (optional, mutually exclusive - default LIST; TABLE forces table-when-possible "
				+ "rendering, RAW forces pretty-JSON/raw-text)";
	}

	@Override
	public String getExamples() {
		return "EXECUTE API ENDPOINT DEMO 5 Development; EXECUTE API ENDPOINT DEMO PINGMAIL Development RAW;";
	}

	/** SPRINT 2409K: the first argument is a configured API id; the endpoint and environment that follow belong to that API, not to the session, so they are not completed. */
	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.API);
	}

}
