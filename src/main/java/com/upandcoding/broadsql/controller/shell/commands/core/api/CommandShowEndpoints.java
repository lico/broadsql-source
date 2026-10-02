package com.upandcoding.broadsql.controller.shell.commands.core.api;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.tabular.ApiEndpointListingBuilder;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultTable;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultTableRenderer;

/**
 * {@code SHOW ENDPOINTS [API <apiId>] [VERB <verb>] [MATCH <keyword>];}: API Quality and UX
 * Consolidation sprint, the single canonical endpoint-listing command, replacing the former
 * {@code SHOW API ENDPOINTS <apiId>}/{@code SHOW ENDPOINTS} duplication.
 *
 * <p>{@code API <apiId>} makes the command self-contained: it targets that API directly and does
 * <b>not</b> require an active {@code CONNECT API} session (this is what {@code SHOW API ENDPOINTS}
 * used to do alone). Omitting it falls back to the active session context (what plain
 * {@code SHOW ENDPOINTS} used to do alone). Both forms share the same listing/filtering/rendering
 * logic via {@link ApiEndpointListingBuilder}, so they can never drift apart. {@code VERB}/
 * {@code MATCH} apply identically either way, and may appear in either order after {@code API
 * <apiId>} (or first, when {@code API} is omitted).
 *
 * <p>Columns: {@code ID}, {@code VERB}, {@code FOLDER}, {@code NAME}, {@code ALIAS}: a compact
 * catalog/navigation view (URL and executability were removed; see {@code SHOW ENDPOINT <id>} for
 * the full detail view of one endpoint).
 */
public class CommandShowEndpoints extends Command {

	public CommandShowEndpoints() {
		super("SHOW ENDPOINTS", "ENDPOINTS", "ALL ENDPOINTS", "SHENDS");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (args == null) {
			args = new String[0];
		}

		String apiId;
		int filterStartIndex;
		if (args.length > 0 && "API".equalsIgnoreCase(args[0])) {
			if (args.length < 2) {
				console.error("API requires a value, e.g. API DEMO.");
				return;
			}
			apiId = args[1].toUpperCase();
			filterStartIndex = 2;
			if (!getApiDefinitionsVault().contains(apiId)) {
				// contains(apiId) only ever sees ACTIVE rows - a deactivated API "disappears" from it
				// exactly like a never-imported one would. Distinguish the two so an inactive API is
				// reported as inactive, not as not found.
				ApiDefinition apiRow = getApiDefinitionsVault().findApiById(apiId);
				if (apiRow != null) {
					console.error("API '" + apiId + "' is inactive. Reactivate it first (CONFIG API), or use SHOW ALL APIS to list active APIs.");
				} else {
					console.error("API '" + apiId + "' not found. Use SHOW ALL APIS to list imported APIs.");
				}
				return;
			}
		} else {
			ApiSessionContext context = ApiSessionContextHolder.get();
			if (context == null) {
				console.error("No active API context. Use CONNECT API <api>:<environment>; first, or SHOW ENDPOINTS API <apiId>.");
				return;
			}
			apiId = context.getApiId();
			filterStartIndex = 0;
			if (!getApiDefinitionsVault().contains(apiId)) {
				// The active context can outlive the API it points at being deactivated in the meantime -
				// CONFIG API never clears an already-established ApiSessionContext. Revalidate on every call
				// instead of trusting it (SPRINT XT02-7B corrective patch, Codex finding 3).
				ApiDefinition apiRow = getApiDefinitionsVault().findApiById(apiId);
				if (apiRow != null) {
					console.error("API '" + apiId + "' is inactive. Reactivate it first (CONFIG API), or DISCONNECT API and reconnect once active again.");
				} else {
					console.error("API '" + apiId + "' no longer exists. DISCONNECT API and use SHOW ALL APIS to list imported APIs.");
				}
				return;
			}
		}

		ApiEndpointListingBuilder.Filters filters = ApiEndpointListingBuilder.parseFilters(args, filterStartIndex);
		ApiResultTable table = ApiEndpointListingBuilder.build(getApiDefinitionsVault(), apiId, filters.verb, filters.match);
		if (table.getRows().isEmpty()) {
			console.writeln("No endpoint" + (filters.verb != null || filters.match != null ? " matches the given filter" : " defined") + " for API '" + apiId + "'.");
			return;
		}
		console.writeln("");
		console.printBlock(ApiResultTableRenderer.renderCatalog(table));
		console.writeln(table.getRows().size() + (table.getRows().size() == 1 ? " endpoint" : " endpoints"));
	}

	@Override
	public String getDescription() {
		return "Lists endpoints as a table, using the active API session context, or an explicit API, with optional VERB/MATCH filters";
	}

	@Override
	public String getDetailedDescription() {
		return "Lists endpoints as a table (ID, VERB, FOLDER, NAME, ALIAS). With API <apiId>, targets that API "
				+ "directly: no active session required. Without it, lists the active CONNECT API session's API, "
				+ "revalidating its status on every call (a session can outlive the API it points at being "
				+ "deactivated via CONFIG API in the meantime). A bare HTTP method (e.g. GET) filters on that exact, "
				+ "case-insensitive verb, the canonical form; the legacy VERB <verb> form is still accepted "
				+ "for backward compatibility. MATCH <keyword> filters on a case-insensitive substring over folder, "
				+ "name, and alias combined; both filters may be combined, in either order, after API <apiId> (or "
				+ "first, when API is omitted). The numeric ID or an assigned alias is what SHOW ENDPOINT/SYNTAX/HELP "
				+ "take to select an endpoint unambiguously, since duplicate endpoint names across different folders "
				+ "are normal. See also SHOW ENDPOINT <id> for a single endpoint's full detail (URL, headers, "
				+ "parameters, body).";
	}

	@Override
	public String getArguments() {
		return "API <apiId> (optional, targets that API directly, no active session required); "
				+ "<method> (optional, e.g. GET, canonical form; VERB <verb> also accepted); "
				+ "MATCH <keyword> (optional, either order)";
	}

	@Override
	public String getExamples() {
		return "SHOW ENDPOINTS; SHOW ENDPOINTS GET; SHOW ENDPOINTS GET MATCH mail; SHOW ENDPOINTS API DEMO; SHOW ENDPOINTS API DEMO GET MATCH mail;";
	}
}
