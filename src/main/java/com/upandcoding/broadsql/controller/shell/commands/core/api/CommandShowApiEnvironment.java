package com.upandcoding.broadsql.controller.shell.commands.core.api;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * {@code SHOW API ENVIRONMENT [<environment-name>];}: SPRINT XT02B, section 11, the single-object
 * detail view of one environment (id, name, base URL, and its variables), companion to the existing
 * {@code SHOW API ENVIRONMENTS <apiId>} listing. Always scoped to the active {@code CONNECT API}
 * session's API, deliberately a runtime/session-scoped read only, since this sprint does not introduce
 * any persisted "current environment" concept: with no argument, shows the session's own connected
 * environment; with an argument, shows the named environment of that same API.
 *
 * <p>A variable flagged {@link ApiAttribute#isSecret()} is masked, the same established convention
 * {@code SHOW ENDPOINT}/{@code JApiAttributeTablePanel} already use: enough information to diagnose
 * substitutions without ever printing a secret's real value.
 */
public class CommandShowApiEnvironment extends Command {

	private static final String MASK = "******";

	public CommandShowApiEnvironment() {
		super("SHOW API ENVIRONMENT", "SHAPIENV");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (args != null && args.length > 1) {
			console.error("Usage: " + getExamples());
			return;
		}

		ApiSessionContext context = ApiSessionContextHolder.get();
		if (context == null) {
			console.error("No API is connected.\nUse CONNECT API <api>:<environment>; before SHOW API ENVIRONMENT.");
			return;
		}
		String apiId = context.getApiId();
		String requestedName = (args != null && args.length == 1) ? args[0].trim() : context.getEnvironmentName();

		ApiEnvironment environment = getApiDefinitionsVault().getEnvironmentsForApi(apiId).stream()
				.filter(e -> requestedName.equalsIgnoreCase(e.getName())).findFirst().orElse(null);
		if (environment == null) {
			console.error("Environment '" + requestedName + "' not found for API '" + apiId
					+ "'. Use SHOW API ENVIRONMENTS " + apiId + " to find it.");
			return;
		}

		List<ApiAttribute> variables = getApiDefinitionsVault().getAttributes(
				ApiOwnerType.ENVIRONMENT, String.valueOf(environment.getId()), ApiAttributeKind.VARIABLE);

		StringBuilder out = new StringBuilder();
		out.append("ID       : ").append(environment.getId()).append('\n');
		out.append("Name     : ").append(StringUtils.defaultString(environment.getName())).append('\n');
		out.append("Base URL : ").append(StringUtils.defaultString(environment.getBaseUrl())).append('\n');

		if (!variables.isEmpty()) {
			out.append('\n');
			out.append("Variables").append('\n');
			for (ApiAttribute variable : variables) {
				out.append("- ").append(variable.getName()).append(" = ")
						.append(variable.isSecret() ? MASK : StringUtils.defaultString(variable.getValue()))
						.append("   ").append(variable.isEnabled() ? "enabled" : "disabled").append('\n');
			}
		}

		console.printBlock(out.toString());
	}

	@Override
	public String getDescription() {
		return "Shows the detail of one environment of the currently connected API (id, name, base URL, variables)";
	}

	@Override
	public String getDetailedDescription() {
		return "Always scoped to the active CONNECT API session's API. With no argument, shows the session's own "
				+ "connected environment; with an environment name, shows that named environment of the same API "
				+ "instead. This is a runtime/session-scoped read only: it does not depend on, and this sprint does "
				+ "not introduce, any persisted 'current environment' concept; CONNECT API remains the sole source "
				+ "of which environment is active. Shows enough information to diagnose ${name}/{{name}}/:name "
				+ "variable substitution: id, name, base URL, and every variable (name, value, enabled/disabled) "
				+ "with a secret value masked, never shown in full.";
	}

	@Override
	public String getArguments() {
		return "<environment-name> (optional, defaults to the active CONNECT API session's own environment)";
	}

	@Override
	public String getExamples() {
		return "SHOW API ENVIRONMENT;\nSHOW API ENVIRONMENT PROD;";
	}
}
