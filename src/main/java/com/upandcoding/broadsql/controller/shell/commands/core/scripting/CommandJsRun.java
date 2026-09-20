package com.upandcoding.broadsql.controller.shell.commands.core.scripting;

import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;

/**
 * Runs a saved {@code .js} script from the JS scripts catalog: {@code JS RUN <file> [args...]}.
 *
 * <p>{@code <file>} is resolved against the {@code JsScripts} catalog (relative path, {@code @alias},
 * or unambiguous bare file name) the same way {@link CommandJsList}/{@link CommandJsFind} enumerate
 * it. Unlike {@code SCRIPT RUN}/{@code LIB RUN}, the extension-optional convenience does not apply
 * here: {@code JsScripts} is kept in its own folder specifically so {@link FileCatalog}'s existing,
 * unmodified {@code .sql} auto-append behavior (shared with {@code LIB}/{@code SCRIPT}) is not
 * disturbed, so a bare name with no extension will not resolve to a {@code .js} file; pass the file
 * name with its {@code .js} extension, or a declared {@code @alias} (which matches regardless of
 * extension). Any trailing tokens after {@code <file>} are bound into the script as {@code args}
 * ({@code args[0]}, {@code args[1]}, ...). Also prints a one-line warning, without refusing to run,
 * for each of the entry's {@code @instance}/{@code @environment} metadata that doesn't match the
 * current connection's instance/environment, the same convention as {@code SCRIPT RUN}.
 */
public class CommandJsRun extends Command {

	public CommandJsRun() {
		super("JS RUN", "JS RU", "JSRU");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		if (!CommandUtils.isValidArgs(args)) {
			console.println("You must provide a file name");
			return;
		}
		String requested = args[0].trim();
		String[] scriptArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];

		FileCatalog catalog = new FileCatalog(consoleSettings.getJsScriptsPath(), BroadSQLErrorMessages.ERR_JSSCRIPTS_01);
		String resolved = catalog.resolve(requested);
		if (resolved == null) {
			console.println("The JS scripts catalog does not contain the requested file");
			return;
		}

		warnIfInstanceMismatch(catalog, resolved);
		warnIfEnvironmentMismatch(catalog, resolved);

		String body = catalog.getQueryBody(resolved);
		JsScriptRunner.run(body, console, sqlDatabase, getDatabaseConnectionsVault(), scriptArgs);
	}

	private void warnIfInstanceMismatch(FileCatalog catalog, String resolved) {
		EntryMetadata metadata = catalog.getEntryMetadata(resolved);
		String currentInstance = CommandUtils.currentInstance(platform, getDatabaseConnectionsVault());
		if (!metadata.appliesToInstance(currentInstance)) {
			console.println("This script is tagged for instance " + metadata.instanceDisplayValue()
					+ ", the current connection's instance is " + (StringUtils.isBlank(currentInstance) ? "unknown" : currentInstance));
		}
	}

	private void warnIfEnvironmentMismatch(FileCatalog catalog, String resolved) {
		EntryMetadata metadata = catalog.getEntryMetadata(resolved);
		String currentEnvironment = CommandUtils.currentEnvironment(platform, getDatabaseConnectionsVault());
		if (!metadata.appliesToEnvironment(currentEnvironment)) {
			console.println("This script is tagged for environment " + metadata.environmentDisplayValue()
					+ ", the current connection's environment is " + (StringUtils.isBlank(currentEnvironment) ? "unknown" : currentEnvironment));
		}
	}

	@Override
	public String getDescription() {
		return ("Runs a saved .js script from the JS scripts catalog, optionally passing positional arguments");
	}

	@Override
	public String getArguments() {
		return "<file> (mandatory) a file name (with its .js extension) or @alias; [args...] (optional) positional arguments bound as args[0], args[1], ...";
	}

	@Override
	public String getExamples() {
		return "JS RUN migrate_active_customers.js;\n\tJS RUN migrate_active_customers.js PROD_ORDERS ARCHIVE_DB;";
	}
}
