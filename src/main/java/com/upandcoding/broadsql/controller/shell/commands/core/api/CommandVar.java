package com.upandcoding.broadsql.controller.shell.commands.core.api;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.ApiSessionVariablesHolder;
import com.upandcoding.broadsql.dao.api.invocation.EnvVarResolver;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * {@code VAR <name>=<value-expression> [PERSIST];}: SPRINT XT02A (URL-Native API Execution), section
 * 2.5/3.2, creates or replaces a session-scoped BroadSQL variable, looked up case-insensitively by
 * {@code RUN} for a {@code :name} path/query placeholder (section 7.3). Deliberately generic, not tied
 * to any one endpoint (section 8).
 *
 * <p>A value may reference {@code ${ENV:NAME}} (an operating-system environment variable), resolved
 * once, at assignment time (section 16, "assignment-time resolution"); the resulting session variable
 * holds the already-resolved value, not the placeholder text.
 *
 * <p><b>Quoting</b>: a value containing spaces must be double-quoted (BroadSQL's existing argument-quoting
 * convention, {@link com.upandcoding.broadsql.controller.shell.commands.CommandUtils#getArgumentsFromQuery}),
 * e.g. {@code VAR NAME="John Doe";}. The spec's own example used single quotes; this is a deliberate,
 * documented adaptation to the convention already used everywhere else in BroadSQL (docs/TECHNICAL_CHANGE.md),
 * not a new one invented for this command.
 *
 * <p><b>{@code PERSIST}</b> (SPRINT XT02B, section 5) is a new implementation, not a restore: XT02A's
 * spec explicitly rejected a flat, endpoint-ambiguous {@code VAR ... PERSIST} (section 9.3 there),
 * because a bare name could belong to many endpoints. This version targets a different, unambiguous
 * store: the current API's current environment's variable set (the exact same {@code ApiAttribute} rows
 * {@code CONFIG API}'s Environment tab already edits), which resolves that objection rather than
 * contradicting it. Requires an active {@code CONNECT API} session. Upserts the value into that store
 * (by case-insensitive name, preserving every other field of an existing row), then clears any session
 * VAR of the same name (also case-insensitive), so the persisted environment value becomes the single
 * source of truth for that name going forward, immediately visible through the normal resolution chain
 * (session VAR, then API environment variable, then endpoint-specific persisted parameter, then
 * endpoint default) without a stale session override ever masking a later edit again. Plain {@code VAR
 * name=value;} (no {@code PERSIST}) is unchanged: session-only, never touches the database.
 *
 * <p>{@code VAR} sets API variables only. They are never visible in SQL statements: SQL scripting variables,
 * used in SQL as {@code ${name}}, are set with {@code LET} and listed with {@code SHOW SCRIPT VARIABLES}.
 */
public class CommandVar extends Command {

	public CommandVar() {
		super("VAR");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		boolean persist = args != null && args.length == 2 && "PERSIST".equalsIgnoreCase(args[1]);
		if (args == null || args.length < 1 || args.length > 2 || (args.length == 2 && !persist) || !args[0].contains("=")) {
			console.error("Usage: " + getExamples());
			return;
		}
		String token = args[0];
		int eq = token.indexOf('=');
		String name = token.substring(0, eq).trim();
		String rawValue = token.substring(eq + 1);
		if (name.isEmpty()) {
			console.error("Usage: " + getExamples());
			return;
		}
		// CommandUtils.getArgumentsFromQuery already strips a trailing quote off the whole token (since
		// "NAME=\"John Doe\"" ends with '"'), but never a leading one (the token doesn't start with '"') -
		// so only a leading quote, if any, is ever left here to remove.
		if (rawValue.startsWith("\"")) {
			rawValue = rawValue.substring(1);
			if (rawValue.endsWith("\"")) {
				rawValue = rawValue.substring(0, rawValue.length() - 1);
			}
		}

		String resolvedValue = EnvVarResolver.resolve(rawValue);

		if (persist) {
			ApiSessionContext context = ApiSessionContextHolder.get();
			if (context == null) {
				console.error("No API is connected.\nUse CONNECT API <api>:<environment>; before VAR ... PERSIST.");
				return;
			}
			ApiEnvironment environment = getApiDefinitionsVault().getEnvironmentsForApi(context.getApiId()).stream()
					.filter(e -> context.getEnvironmentName().equals(e.getName())).findFirst().orElse(null);
			if (environment == null) {
				console.error("Environment '" + context.getEnvironmentName() + "' not found for API '" + context.getApiId()
						+ "'. Use SHOW API ENVIRONMENTS " + context.getApiId() + " to find it.");
				return;
			}
			getApiDefinitionsVault().upsertVariable(ApiOwnerType.ENVIRONMENT, String.valueOf(environment.getId()), name, resolvedValue);
			// The environment value just written is now the single source of truth for this name - clear
			// any session override so it doesn't keep shadowing a later CONFIG API edit or PERSIST.
			ApiSessionVariablesHolder.remove(name);
			console.println("VAR " + name + " persisted to environment '" + environment.getName() + "'.");
			return;
		}

		ApiSessionVariablesHolder.set(name, resolvedValue);
		console.println("VAR " + name + " set.");
	}

	@Override
	public String getDescription() {
		return "Sets a session-scoped BroadSQL variable (used to resolve a RUN :name placeholder), or PERSIST it to the current API environment";
	}

	@Override
	public String getDetailedDescription() {
		return "Creates or replaces a temporary, session-only variable, looked up case-insensitively by RUN for a "
				+ "matching :name path or query placeholder; this is step 1 of the resolution precedence, ahead of the "
				+ "current API environment variable, this endpoint's persisted CONFIG API value, and its default (section "
				+ "7.3, extended by SPRINT XT02B section 4.3). A value may reference ${ENV:NAME} (an operating-system "
				+ "environment variable), resolved once at assignment time; an undefined environment variable fails "
				+ "explicitly. A value containing spaces must be double-quoted. With the trailing PERSIST keyword "
				+ "(requires an active CONNECT API session): instead of a session variable, upserts the value into the "
				+ "current environment's variable set (the same store CONFIG API's Environment tab edits) by "
				+ "case-insensitive name, preserving every other field of an existing row, then clears any session VAR "
				+ "of the same name so the newly persisted environment value is what every subsequent command sees, "
				+ "immediately, through the normal resolution chain. VAR sets API variables only: they are not visible in SQL. "
				+ "SQL scripting variables, used in SQL as ${name}, are set with LET.";
	}

	@Override
	public String getArguments() {
		return "<name>=<value-expression> (mandatory, value-expression may reference ${ENV:NAME}, double-quote a value "
				+ "containing spaces); PERSIST (optional, requires an active CONNECT API session)";
	}

	@Override
	public String getExamples() {
		return "VAR ID=123; VAR COUNTRY=FR; VAR NAME=\"John Doe\"; VAR ID=${ENV:CUSTOMER_ID}; VAR ID=123 PERSIST;";
	}
}
