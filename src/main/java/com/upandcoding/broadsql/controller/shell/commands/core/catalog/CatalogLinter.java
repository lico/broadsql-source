package com.upandcoding.broadsql.controller.shell.commands.core.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;
import com.upandcoding.broadsql.controller.shell.scripts.StatementSplitter;
import com.upandcoding.broadsql.controller.shell.scripts.VariableNames;
import com.upandcoding.broadsql.controller.shell.scripts.VariableReferenceScanner;

/**
 * Bulk consistency checks across the {@link ScriptsLibrary}, used by {@code LIB LINT}. Reports findings
 * as plain text lines; never modifies anything. SPRINT 1909S: applies to every Script and no longer checks
 * {@code @alias}, which has no meaning in the Scripts Library.
 *
 * <p>SPRINT 0110A (spec section 24): the non-contiguous {@code %N} rule is removed (positional parameters no
 * longer exist); new rules: a possible legacy positional parameter {@code %1}..{@code %9} (migration, indicative:
 * a {@code LIKE '%1%'} pattern is reported too), a {@code ${name}} reference written inside a single-quoted string
 * literal (indicative: it is not substituted there; never reported for an {@code ECHO} message, where it is
 * interpolated), and {@code @params} validity (invalid or reserved name, duplicate). Static checks only: they never
 * depend on the session's variables.
 */
public class CatalogLinter {

	private static final Pattern LEGACY_POSITIONAL = Pattern.compile("%([1-9])(?!\\d)");

	public List<String> lint(ScriptsLibrary library, Set<String> knownInstances, Set<String> knownEnvironments) throws BroadSQLException {
		List<String> findings = new ArrayList<>();
		for (String relativeKey : new TreeSet<>(library.getList())) {
			findings.addAll(lintEntry(relativeKey, library.getRawContent(relativeKey), knownInstances, knownEnvironments));
		}
		return findings;
	}

	/**
	 * The same per-entry checks {@link #lint} performs, for exactly one Script's text - usable on an unsaved buffer
	 * without a round trip through disk.
	 */
	public List<String> lintEntry(String relativeKey, String scriptText, Set<String> knownInstances, Set<String> knownEnvironments) {
		EntryMetadata metadata = EntryMetadata.parse(scriptText);
		List<String> findings = new ArrayList<>(instanceEnvironmentFindings(relativeKey, metadata, knownInstances, knownEnvironments));
		findings.addAll(paramsFindings(relativeKey, metadata));
		List<String> statements = StatementSplitter.split(EntryMetadata.stripHeader(scriptText));
		for (int i = 0; i < statements.size(); i++) {
			findings.addAll(statementFindings(relativeKey, i + 1, statements.get(i)));
		}
		return findings;
	}

	private List<String> instanceEnvironmentFindings(String relativeKey, EntryMetadata metadata, Set<String> knownInstances, Set<String> knownEnvironments) {
		List<String> findings = new ArrayList<>();
		for (String instance : metadata.getInstances()) {
			if (knownInstances != null && !containsIgnoreCase(knownInstances, instance)) {
				findings.add(relativeKey + ": @instance '" + instance + "' does not match any known Database Group id");
			}
		}
		for (String environment : metadata.getEnvironments()) {
			if (knownEnvironments != null && !containsIgnoreCase(knownEnvironments, environment)) {
				findings.add(relativeKey + ": @environment '" + environment + "' does not match any known environment id");
			}
		}
		return findings;
	}

	/** {@code @params}: invalid or reserved names, and names declared more than once (case-insensitive). */
	static List<String> paramsFindings(String relativeKey, EntryMetadata metadata) {
		List<String> findings = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		Set<String> reportedDuplicates = new HashSet<>();
		for (String name : metadata.getDeclaredParams()) {
			String problem = VariableNames.problem(name, "name");
			if (problem != null) {
				findings.add(relativeKey + ": @params declares an invalid parameter: " + problem + " (every call of this Script fails until it is fixed)");
				continue;
			}
			String key = name.toUpperCase(Locale.ROOT);
			if (!seen.add(key) && reportedDuplicates.add(key)) {
				findings.add(relativeKey + ": @params declares " + name + " more than once");
			}
		}
		return findings;
	}

	private static List<String> statementFindings(String relativeKey, int number, String statement) {
		List<String> findings = new ArrayList<>();
		Set<String> legacy = new LinkedHashSet<>();
		Matcher matcher = LEGACY_POSITIONAL.matcher(statement);
		while (matcher.find()) {
			legacy.add(matcher.group());
		}
		for (String placeholder : legacy) {
			findings.add(relativeKey + ": statement " + number + ": possible legacy positional parameter " + placeholder
					+ "; positional parameters were removed, use name=value arguments and ${name}");
		}
		if (isStringLiteralRuleScope(statement)) {
			for (String reference : referencesInsideStringLiterals(statement)) {
				findings.add(relativeKey + ": statement " + number + ": " + reference + " is not substituted inside a string literal; write " + reference
						+ " outside quotes to bind it");
			}
		}
		return findings;
	}

	/**
	 * The commands whose quoted text the reference-in-string rule covers (spec 24): {@code LET} (literal or query),
	 * script calls (argument literals) and {@code DUMP}/{@code PULL} (source query). Every other BroadSQL command,
	 * {@code ECHO} first, is out of scope; a statement that is not a BroadSQL command is SQL, in scope.
	 */
	private static final Set<Class<?>> STRING_RULE_COMMANDS = Set.of(com.upandcoding.broadsql.controller.shell.commands.core.script.CommandLet.class,
			com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExternalFile.class,
			com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibRun.class,
			com.upandcoding.broadsql.controller.shell.commands.core.export.CommandDumpTable.class,
			com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull.class);

	private static volatile java.util.Map<String, Class<?>> keywordOwners;

	/** Every keyword and alias of the registered BroadSQL commands, with the class that owns it. */
	private static java.util.Map<String, Class<?>> keywordOwners() {
		java.util.Map<String, Class<?>> owners = keywordOwners;
		if (owners == null) {
			owners = new java.util.HashMap<>();
			for (Class<? extends com.upandcoding.broadsql.controller.shell.commands.Command> type : com.upandcoding.broadsql.controller.shell.commands.CommandCategoryCatalog
					.registeredClasses().keySet()) {
				try {
					for (String keyword : type.getDeclaredConstructor().newInstance().getKeywords()) {
						owners.put(keyword, type);
					}
				} catch (ReflectiveOperationException | RuntimeException e) {
					// a command that cannot be instantiated here simply contributes no keyword
				}
			}
			keywordOwners = owners;
		}
		return owners;
	}

	static boolean isStringLiteralRuleScope(String statement) {
		java.util.Map<String, Class<?>> owners = keywordOwners();
		String keyword = CommandUtils.matchingKeyword(statement, owners.keySet().toArray(new String[0]));
		return keyword == null || STRING_RULE_COMMANDS.contains(owners.get(keyword));
	}

	/** The distinct valid {@code ${name}} references written inside single-quoted string literals of {@code statement}. */
	static Set<String> referencesInsideStringLiterals(String statement) {
		Set<String> found = new LinkedHashSet<>();
		int n = statement.length();
		int i = 0;
		while (i < n) {
			char c = statement.charAt(i);
			if (c == '"') {
				int close = statement.indexOf('"', i + 1);
				i = close < 0 ? n : close + 1;
			} else if (c == '\'') {
				int end = i + 1;
				StringBuilder content = new StringBuilder();
				while (end < n) {
					if (statement.charAt(end) == '\'') {
						if (end + 1 < n && statement.charAt(end + 1) == '\'') {
							content.append("''");
							end += 2;
							continue;
						}
						break;
					}
					content.append(statement.charAt(end));
					end++;
				}
				String literal = content.toString();
				int k = literal.indexOf("${");
				while (k >= 0) {
					VariableReferenceScanner.Occurrence occurrence = VariableReferenceScanner.referenceAt(literal, k);
					if (!occurrence.isMalformed()) {
						found.add(occurrence.text());
					}
					k = literal.indexOf("${", k + 2);
				}
				i = end + 1;
			} else {
				i++;
			}
		}
		return found;
	}

	private static boolean containsIgnoreCase(Set<String> values, String value) {
		for (String candidate : values) {
			if (candidate.equalsIgnoreCase(value)) {
				return true;
			}
		}
		return false;
	}
}
