package com.upandcoding.broadsql.controller.shell.commands.core.scripts;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.CatalogLinter;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;

/**
 * Checks the scripts catalog for consistency issues: {@code SCRIPT LINT [<name>|ALL]}. Same shape as
 * {@code LIB LINT} (see its Javadoc), applied to the {@code Scripts} catalog, except the non-contiguous
 * {@code %N} check, which is a {@code LIB RUN}-specific concept scripts don't use.
 */
public class CommandScriptLint extends Command {

	public CommandScriptLint() {
		super("SCRIPT LINT", "SC LN", "SCLN");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		String arg = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		FileCatalog catalog = new FileCatalog(consoleSettings.getScriptsPath(), BroadSQLErrorMessages.ERR_SCRIPTS_01);
		Set<String> knownInstances = getDatabaseConnectionsVault() != null ? getDatabaseConnectionsVault().getGroups() : null;
		Set<String> knownEnvironments = getDatabaseConnectionsVault() != null ? getDatabaseConnectionsVault().getEnvironments() : null;
		List<String> findings = new CatalogLinter().lint(catalog, knownInstances, knownEnvironments, false);

		if (StringUtils.isNotBlank(arg) && !"ALL".equalsIgnoreCase(arg)) {
			String resolved = catalog.resolve(arg);
			if (resolved == null) {
				console.println("The scripts catalog does not contain the requested file");
				return;
			}
			findings = filterFor(findings, resolved);
		}

		if (findings.isEmpty()) {
			console.println("SCRIPT LINT: no issues found");
		} else {
			console.println("SCRIPT LINT found " + findings.size() + " issue(s):");
			for (String finding : findings) {
				console.println("- " + finding);
			}
		}
		console.println("");
	}

	private static List<String> filterFor(List<String> findings, String resolved) {
		List<String> filtered = new ArrayList<>();
		for (String finding : findings) {
			if (finding.startsWith(resolved + ":") || finding.contains(resolved)) {
				filtered.add(finding);
			}
		}
		return filtered;
	}

	@Override
	public String getDescription() {
		return ("Checks the scripts catalog for consistency issues: unknown @instance/@environment ids, duplicate @alias values");
	}

	@Override
	public String getArguments() {
		return "<name> (optional) a file name, @alias, or search term; ALL (default) checks every entry";
	}

	@Override
	public String getExamples() {
		return "SCRIPT LINT;\n\tSCRIPT LINT DAILY.SQL;";
	}
}
