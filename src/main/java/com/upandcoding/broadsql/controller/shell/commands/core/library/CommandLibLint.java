package com.upandcoding.broadsql.controller.shell.commands.core.library;

import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.CatalogLinter;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;

/**
 * Checks the Scripts Library for consistency issues: {@code LIB LINT [<script>|ALL]}. Reports, never fixes.
 *
 * <p>With no argument (or the literal {@code ALL}), checks every Script. With {@code <script>} (a path
 * relative to the library, as for {@code LIB SHOW}), only findings involving that Script are reported.
 * Checks performed: an {@code @instance} or {@code @environment} id that doesn't match any Database
 * Group/environment known to the current CDF; an invalid, reserved or duplicate name in {@code -- @params:};
 * a possible legacy positional parameter {@code %1} to {@code %9} (positional parameters were removed: use
 * {@code name=value} arguments and {@code ${name}}; a {@code LIKE '%1%'} pattern is reported too, check it); a
 * {@code ${name}} written inside a quoted string, where it is not substituted (not reported for {@code ECHO},
 * which prints variable values inside its quotes).
 */
public class CommandLibLint extends Command {

	public CommandLibLint() {
		super("LIB LINT", "LI LN", "LILN");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		String arg = CommandUtils.isValidArgs(args) ? args[0].trim() : null;

		ScriptsLibrary library = LibraryScripts.open(consoleSettings);
		Set<String> knownInstances = getDatabaseConnectionsVault() != null ? getDatabaseConnectionsVault().getGroups() : null;
		Set<String> knownEnvironments = getDatabaseConnectionsVault() != null ? getDatabaseConnectionsVault().getEnvironments() : null;
		List<String> findings = new CatalogLinter().lint(library, knownInstances, knownEnvironments);

		if (StringUtils.isNotBlank(arg) && !"ALL".equalsIgnoreCase(arg)) {
			String key = LibraryScripts.existingKey(consoleSettings, library, arg);
			findings = filterFor(findings, key);
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

	private static List<String> filterFor(List<String> findings, String key) {
		List<String> filtered = new ArrayList<>();
		for (String finding : findings) {
			if (finding.startsWith(key + ":") || finding.contains(key)) {
				filtered.add(finding);
			}
		}
		return filtered;
	}

	@Override
	public String getDescription() {
		return "Checks the Scripts Library for consistency issues: unknown @instance/@environment ids, invalid @params, legacy %1..%9 parameters, ${name} inside quotes";
	}

	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.SCRIPT);
	}

	@Override
	public String getArguments() {
		return "<script> (optional) a path relative to the Scripts Library; ALL (default) checks every Script";
	}

	@Override
	public String getExamples() {
		return "LIB LINT;\n\tLIB LINT maintenance/cleanup.bsql;";
	}
}
