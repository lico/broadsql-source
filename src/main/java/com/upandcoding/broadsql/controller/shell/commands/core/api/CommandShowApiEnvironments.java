package com.upandcoding.broadsql.controller.shell.commands.core.api;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultTable;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultTableRenderer;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;

/**
 * Lists the environments defined for one API: {@code SHOW API ENVIRONMENTS <apiId>}, the selectable
 * "Development"/"Staging"/"Production"-style contexts {@code RUN} chooses between. Never prints a
 * secret environment variable, only non-secret {@link ApiEnvironment} fields (id, name, base URL).
 * See the "SPRINT XT02: Universal API Client" design doc under docs/.
 *
 * <p>API Quality and UX Consolidation sprint (section 7): renders a real bordered table via {@link
 * ApiResultTableRenderer}, the same catalog/navigation convention {@code SHOW ENDPOINTS}/
 * {@code SHOW ALL APIS} use, replacing the previous raw {@code name\tbaseUrl} lines.
 */
public class CommandShowApiEnvironments extends Command {

	private static final List<String> COLUMNS = List.of("ID", "NAME", "BASE URL");

	public CommandShowApiEnvironments() {
		// "ALL ENVT" must be tried before "ALL ENV" - "ALL ENV" is a literal text-prefix of "ALL ENVT",
		// and CommandUtils.getArgumentsFromQuery's keyword loop (unlike CommandList's dispatch, which
		// requires a trailing space/exact match) does not enforce a word boundary before matching a
		// keyword, so the shorter synonym would otherwise swallow "ALL ENVT <apiId>" and pass "ENVT
		// <apiId>" through as bogus arguments.
		super("SHOW API ENVIRONMENTS", "SHAPENV", "ALL ENVT", "ALL ENV");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (!CommandUtils.isValidArgs(args)) {
			console.error("Usage: " + getExamples());
			return;
		}
		String apiId = args[0].toUpperCase();
		if (!getApiDefinitionsVault().contains(apiId)) {
			console.error("API '" + apiId + "' not found. Use SHOW ALL APIS to list imported APIs.");
			return;
		}

		List<ApiEnvironment> environments = getApiDefinitionsVault().getEnvironmentsForApi(apiId);
		if (environments.isEmpty()) {
			console.writeln("No environment defined for API '" + apiId + "'.");
			return;
		}
		List<Map<String, String>> rows = new ArrayList<>();
		for (ApiEnvironment environment : environments) {
			Map<String, String> row = new LinkedHashMap<>();
			row.put("ID", String.valueOf(environment.getId()));
			row.put("NAME", StringUtils.defaultString(environment.getName()));
			row.put("BASE URL", StringUtils.defaultString(environment.getBaseUrl()));
			rows.add(row);
		}
		ApiResultTable table = new ApiResultTable(COLUMNS, rows, true, null);
		console.writeln("");
		console.printBlock(ApiResultTableRenderer.renderCatalog(table));
		console.writeln(environments.size() + (environments.size() == 1 ? " environment" : " environments"));
	}

	@Override
	public String getDescription() {
		return "Lists the environments defined for one API as a table (ID, NAME, BASE URL)";
	}

	@Override
	public String getDetailedDescription() {
		return "Lists every environment defined for one API as a bordered table: ID, NAME, BASE URL. Never shows a "
				+ "secret environment variable, only these non-secret fields. Use CONNECT API <apiId>:<environment> "
				+ "then RUN <url> to execute against one of these. See SHOW API ENVIRONMENT for the detail of one "
				+ "environment (including its variables) instead of this list.";
	}

	@Override
	public String getArguments() {
		return "<apiId> (mandatory)";
	}

	@Override
	public String getExamples() {
		return "SHOW API ENVIRONMENTS DEMO;";
	}

	/** SPRINT 2409K: the one argument is a configured API id. */
	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.API);
	}

}
