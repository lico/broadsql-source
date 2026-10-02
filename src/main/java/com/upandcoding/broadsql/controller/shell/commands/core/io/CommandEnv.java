package com.upandcoding.broadsql.controller.shell.commands.core.io;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Switches to another Environment of the current Database Group: {@code ENV <environment>}. Aliases:
 * {@code ENVT}, {@code CONNECT ENVIRONMENT}.
 *
 * <p>The Environment is looked up in the Database Group of the current connection only. The connection
 * mapped to it in that group is then opened exactly as {@code CONNECT <connection>} would open it: same
 * checks, login script and prompt. After {@code ENV QA}, the session is the one {@code CONNECT} of the QA
 * connection would have produced, and the prompt shows that connection.
 *
 * <p>{@code CONNECT <name>} always means a connection and {@code ENV <name>} always means an Environment,
 * so a connection and an Environment with the same name never conflict. Other Database Groups are never
 * searched: from a connection without a Database Group, {@code ENV} fails. It also fails, leaving the
 * current connection open, when the Environment does not exist, has no connection in the group, is mapped
 * to an inactive connection, or when that connection cannot be opened.
 */
public class CommandEnv extends CommandConnect {

	// Implementation (SPRINT 2309T, #159): resolution goes through the single Group/Environment resolver,
	// DatabaseDefinitionsVault#resolveConnectionForGroupAndEnvironment (shared with "/ <environment>"), then
	// CommandConnect#connectToConnection - the one physical-connect path - so no CONNECT safeguard is skipped.

	public CommandEnv() {
		super("ENV", "ENVT", "CONNECT ENVIRONMENT");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String environment = environmentArgument(query);
		if (StringUtils.isBlank(environment)) {
			throw new BroadSQLException("You must specify an Environment, e.g. ENV QA");
		}
		connectToConnection(resolveEnvironment(environment).getId());
	}

	/**
	 * Resolves {@code environment} in the current Connection's Database Group, without connecting.
	 *
	 * @throws BroadSQLException when there is no current Connection or Database Group, or when the
	 *                           Environment cannot be resolved to exactly one active Connection
	 */
	DatabaseDefinition resolveEnvironment(String environment) throws BroadSQLException {
		String group = CommandUtils.currentInstance(this.platform, getDatabaseConnectionsVault());
		if (StringUtils.isBlank(group)) {
			throw new BroadSQLException("Cannot switch to Environment '" + environment + "': there is no current Database Group"
					+ (StringUtils.isBlank(this.platform) ? " (no current connection)." : " (connection '" + this.platform + "' belongs to none).")
					+ " Use CONNECT <connection> instead.");
		}
		try {
			return getDatabaseConnectionsVault().resolveConnectionForGroupAndEnvironment(group, environment);
		} catch (BroadSQLException e) {
			throw new BroadSQLException("Cannot switch to Environment '" + environment + "': " + e.getLocalizedMessage(), e);
		}
	}

	/**
	 * The single argument after whichever keyword was typed. Keywords are tried longest first, because
	 * {@code ENV} is a prefix of {@code ENVT} (the generic {@code parseArgs} tries them in declaration order).
	 */
	private String environmentArgument(String query) {
		String trimmed = StringUtils.trimToEmpty(query);
		String[] byLength = getKeywords().clone();
		Arrays.sort(byLength, Comparator.comparingInt(String::length).reversed());
		for (String keyword : byLength) {
			if (trimmed.equalsIgnoreCase(keyword)) {
				return null;
			}
			if (StringUtils.startsWithIgnoreCase(trimmed, keyword + " ")) {
				String[] args = CommandUtils.getArgumentsFromQuery(trimmed, new String[] { keyword });
				return CommandUtils.isValidArgs(args) ? args[0].trim() : null;
			}
		}
		return null;
	}

	@Override
	public String getDescription() {
		return "Switches to an Environment of the current Database Group";
	}

	@Override
	public String getDetailedDescription() {
		return "ENV <environment> resolves the Environment in the current connection's Database Group and connects to the "
				+ "physical Connection mapped to it, exactly as CONNECT <connection> would (same checks, prompt and login script). "
				+ "It never searches other Database Groups: without a current Database Group it fails. CONNECT <name> always means "
				+ "a physical Connection, so a Connection and an Environment with the same name never conflict. Aliases: ENVT, "
				+ "CONNECT ENVIRONMENT.";
	}

	@Override
	public String getArguments() {
		return "<environment> (mandatory) an Environment of the current Database Group, e.g. QA";
	}

	@Override
	public String getExamples() {
		return "ENV QA\n\tSwitches to the QA Connection of the current Database Group\n\tCONNECT ENVIRONMENT PROD";
	}

	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.GROUP_ENVIRONMENT);
	}
}
