package com.upandcoding.broadsql.controller.shell.commands.core.library;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.CatalogLinter;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog;

/**
 * Checks the SQL library for consistency issues: {@code LIB LINT [<name>|ALL]}. Reports, never fixes.
 *
 * <p>With no argument (or the literal {@code ALL}), checks every entry. With {@code <name>}
 * (resolved the same way as {@code LIB SHOW}), only findings involving that entry are reported. Checks
 * performed: non-contiguous {@code %1..%N} parameters, an {@code @instance} or {@code @environment} id
 * that doesn't match any instance/environment known to the current CDF, and two entries declaring the
 * same {@code @alias}. See {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}, section 7.
 */
public class CommandLibLint extends Command {

	public CommandLibLint() {
		super("LIB LINT", "LI LN", "LILN");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		String arg = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		if (StringUtils.isBlank(consoleSettings.getLibraryPath())) {
			console.println("No SQL library folder specified in the INI file");
			return;
		}

		FileCatalog catalog = new FileCatalog(consoleSettings.getLibraryPath());
		Set<String> knownInstances = getDatabaseConnectionsVault() != null ? getDatabaseConnectionsVault().getGroups() : null;
		Set<String> knownEnvironments = getDatabaseConnectionsVault() != null ? getDatabaseConnectionsVault().getEnvironments() : null;
		List<String> findings = new CatalogLinter().lint(catalog, knownInstances, knownEnvironments, true);

		if (StringUtils.isNotBlank(arg) && !"ALL".equalsIgnoreCase(arg)) {
			String resolved = catalog.resolve(arg);
			if (resolved == null) {
				console.println("The SQL library does not contain the requested query file");
				return;
			}
			findings = filterFor(findings, resolved);
		}

		if (findings.isEmpty()) {
			console.println("LIB LINT: no issues found");
		} else {
			console.println("LIB LINT found " + findings.size() + " issue(s):");
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
		return ("Checks the SQL library for consistency issues: non-contiguous %N parameters, unknown @instance/@environment ids, duplicate @alias values");
	}

	@Override
	public String getArguments() {
		return "<name> (optional) a file name, @alias, or search term; ALL (default) checks every entry";
	}

	@Override
	public String getExamples() {
		return "LIB LINT;\n\tLIB LINT COUNTRY.SQL;";
	}
}
