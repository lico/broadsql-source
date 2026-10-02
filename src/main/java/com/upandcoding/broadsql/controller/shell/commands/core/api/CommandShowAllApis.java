package com.upandcoding.broadsql.controller.shell.commands.core.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiImportSource;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultTable;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultTableRenderer;

/**
 * Lists every imported API: {@code SHOW ALL APIS}, no arguments, the API-catalog equivalent of
 * {@code SHOW ALL CONNECTIONS}. See the "SPRINT XT02: Universal API Client" design doc under docs/.
 *
 * <p>API Quality and UX Consolidation sprint (section 6): renders a real bordered table (headers, {@code
 * |} separators, {@code -} horizontal borders, consistent alignment) via {@link ApiResultTableRenderer},
 * replacing the previous raw {@code id\tname} lines, the same catalog/navigation convention {@code SHOW
 * ENDPOINTS} already uses, with long cells ellipsized for display only (never the persisted value).
 * Columns are actual {@link ApiDefinition} fields only: {@code ID}, {@code NAME}, {@code TYPE} (Manual,
 * or Bruno YAML, from {@link com.upandcoding.broadsql.dao.api.ApiDefinitionsVault#getImportSource}), and
 * {@code DESCRIPTION}.
 */
public class CommandShowAllApis extends Command {

	private static final List<String> COLUMNS = List.of("ID", "NAME", "TYPE", "DESCRIPTION");

	public CommandShowAllApis() {
		super("SHOW ALL APIS", "SHALAP", "ALL APIS");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		List<ApiDefinition> apis = getApiDefinitionsVault().getApis();
		if (apis.isEmpty()) {
			console.writeln("No API imported yet. Use IMPORT API BRUNO to import one.");
			return;
		}
		List<Map<String, String>> rows = new ArrayList<>();
		for (ApiDefinition api : apis) {
			Map<String, String> row = new LinkedHashMap<>();
			row.put("ID", api.getId());
			row.put("NAME", StringUtils.defaultString(api.getName()));
			row.put("TYPE", sourceTypeLabel(api.getId()));
			row.put("DESCRIPTION", StringUtils.defaultString(api.getDescr()));
			rows.add(row);
		}
		ApiResultTable table = new ApiResultTable(COLUMNS, rows, true, null);
		console.writeln("");
		console.printBlock(ApiResultTableRenderer.renderCatalog(table));
		console.writeln(apis.size() + (apis.size() == 1 ? " API" : " APIs"));
	}

	private String sourceTypeLabel(String apiId) throws BroadSQLException {
		ApiImportSource source = getApiDefinitionsVault().getImportSource(apiId);
		if (source == null) {
			return "Manual";
		}
		return ApiImportSource.SOURCE_TYPE_BRUNO_YAML.equals(source.getSourceType()) ? "Bruno YAML" : StringUtils.defaultString(source.getSourceType());
	}

	@Override
	public String getDescription() {
		return "Lists every imported API as a table (ID, NAME, TYPE, DESCRIPTION)";
	}

	@Override
	public String getDetailedDescription() {
		return "Lists every API as a bordered table: ID, NAME, TYPE (Manual, or Bruno YAML for an imported API), and "
				+ "DESCRIPTION. Long values may be ellipsized for display; the persisted value itself is never "
				+ "truncated. See SHOW ENDPOINTS to list one API's endpoints, and SHOW API ENVIRONMENTS to list its "
				+ "environments.";
	}

	@Override
	public String getArguments() {
		return "none";
	}

	@Override
	public String getExamples() {
		return "SHOW ALL APIS;";
	}
}
