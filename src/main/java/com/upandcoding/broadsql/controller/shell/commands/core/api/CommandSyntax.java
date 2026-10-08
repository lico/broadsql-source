package com.upandcoding.broadsql.controller.shell.commands.core.api;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.api.ApiEndpointReferenceResolver;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.invocation.ApiSyntaxFormatter;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;

/**
 * {@code SYNTAX <endpoint-id|alias|name>;} - SPRINT XT02A (URL-Native API Execution), section 3.4: the
 * short, execution-oriented "how do I call it?" help, generated entirely from the endpoint's own stored
 * metadata ({@link ApiSyntaxFormatter}) - never a separately hand-maintained syntax string. Scoped to
 * the active {@code CONNECT API} session's API, exactly like {@code RUN} itself.
 *
 * <p>SPRINT XT02B, section 6: the token is resolved via the shared
 * {@link com.upandcoding.broadsql.dao.api.ApiEndpointReferenceResolver} (id, alias, or unique name, not
 * alias-only as before this sprint); a name matching more than one endpoint is reported as an
 * ambiguous-candidates table rather than guessed at.
 */
public class CommandSyntax extends Command {

	public CommandSyntax() {
		super("SYNTAX");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (args == null || args.length != 1) {
			console.error("Usage: " + getExamples());
			return;
		}
		ApiSessionContext context = ApiSessionContextHolder.get();
		if (context == null) {
			console.error("No API is connected.\nUse CONNECT API <api>:<environment>; before SYNTAX.");
			return;
		}
		String apiId = context.getApiId();
		ApiEndpointReferenceResolver.Result result = ApiEndpointReferenceResolver.resolve(getApiDefinitionsVault(), apiId, args[0]);
		if (result instanceof ApiEndpointReferenceResolver.Ambiguous ambiguous) {
			console.printBlock(ApiEndpointReferenceResolver.renderAmbiguous(getApiDefinitionsVault(), ambiguous.candidates()));
			return;
		}
		if (!(result instanceof ApiEndpointReferenceResolver.Found found)) {
			console.error("'" + args[0] + "' does not match any endpoint id, alias, or name for API '" + apiId + "'. Use SHOW ENDPOINTS to find it.");
			return;
		}
		ApiEndpoint endpoint = found.endpoint();
		console.printBlock(ApiSyntaxFormatter.format(getApiDefinitionsVault(), endpoint));
	}

	@Override
	public String getDescription() {
		return "Shows how to call an endpoint by alias: its URL syntax, required/optional parameters, and RUN examples";
	}

	@Override
	public String getDetailedDescription() {
		return "Generated entirely from the endpoint's own stored parameter metadata (CONFIG API), never a separately "
				+ "maintained syntax string, so it can never drift from what RUN actually accepts. Requires an active "
				+ "CONNECT API session (unlike SHOW ENDPOINT, even a numeric id is scoped to that session's API here). "
				+ "Shows the canonical colon-style URL, every required path/query parameter, optional query parameters "
				+ "with their allowed values, and both a literal and a parameterized (VAR-based) RUN example. The "
				+ "argument accepts a numeric id, an alias, or a name; a name matching more than one endpoint is "
				+ "reported as an ambiguous-candidates table instead of being guessed at, same as SHOW ENDPOINT.";
	}

	@Override
	public String getArguments() {
		return "<id|alias|name> (mandatory, requires an active CONNECT API session): a numeric endpoint id, alias, or name, "
				+ "all scoped to that session's API";
	}

	@Override
	public String getExamples() {
		return "SYNTAX CUST;\nSYNTAX 84;\nSYNTAX findAll;";
	}
}
