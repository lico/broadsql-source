package com.upandcoding.broadsql.controller.shell.completion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandCategoryCatalog;
import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidateType;
import com.upandcoding.broadsql.controller.shell.sql.SqlToken;
import com.upandcoding.broadsql.controller.shell.sql.SqlTokenType;

/**
 * Top-level BroadSQL command and subcommand/keyword completion - SPRINT 0917-02, sections 4/5/23/29.
 *
 * <p>Reads directly from the live {@link CommandList} bean (every registered keyword literal, from
 * every alias down to {@code SHALCO}, already mapped to its owning {@link Command} - see
 * {@code CommandList#getConsoleCommands}) - section 22: "Do not duplicate BroadSQL grammar". No
 * second, separately maintained command vocabulary is introduced here. Held as a direct reference
 * rather than re-loaded per completion: {@link CommandList} is a singleton Spring bean populated once,
 * in place, by {@code CommandInterpreter.run()} before the interactive read loop starts - by the time a
 * user can press TAB, it is already populated, even though this provider itself may be constructed
 * earlier, at startup, before that population happens (see {@code BroadSQL.main}).
 *
 * <p>A multi-word canonical keyword (e.g. {@link Command#getKeywords()}{@code [0] == "SHOW ENDPOINTS"})
 * doubles as that command's own static subcommand grammar - completing token N of a canonical keyword
 * one token at a time (section 5) needs no per-command custom syntax description at all.
 *
 * <p>Section 23: at the first token, a canonical command name is offered whenever one shares the typed
 * prefix; a single-word alias (e.g. {@code SHALCO}) is only offered when <i>no</i> canonical command
 * does - so existing short forms keep working end-to-end (typed in full, they still dispatch exactly as
 * before - this class only decides what TAB offers) without cluttering the common case.
 */
public final class BroadSqlCommandCompletionProvider implements CompletionProvider {

	private final CommandList commandList;

	public BroadSqlCommandCompletionProvider(CommandList commandList) {
		this.commandList = commandList;
	}

	@Override
	public List<CompletionCandidate> complete(CompletionContext context) {
		if (commandList == null || commandList.isEmpty()) {
			return List.of();
		}
		List<String> typedTokens = wordTokens(context.getStatementTokensBeforeCursor());
		String word = context.getWord();

		if (typedTokens.isEmpty()) {
			return completeFirstToken(word);
		}
		return completeNextToken(typedTokens, word);
	}

	private List<CompletionCandidate> completeFirstToken(String word) {
		Set<String> canonicalFirstTokens = new LinkedHashSet<>();
		for (Command command : distinctCommands()) {
			String[] tokens = canonicalTokens(command);
			if (tokens.length > 0) {
				canonicalFirstTokens.add(tokens[0]);
			}
		}
		List<CompletionCandidate> fromCanonical = filterByPrefix(canonicalFirstTokens, word, CompletionCandidateType.BROADSQL_COMMAND, null);
		if (!fromCanonical.isEmpty()) {
			return fromCanonical;
		}

		Set<String> singleWordAliases = new LinkedHashSet<>();
		for (Map.Entry<String, Command> entry : commandList.entrySet()) {
			String literal = entry.getKey();
			if (entry.getValue() == null || !CommandCategoryCatalog.isDocumented(entry.getValue())) {
				continue; // SPRINT 2309T: a compatibility-only command (EXPORT, SET SEPARATOR...) is never suggested
			}
			if (literal != null && !literal.trim().isEmpty() && !literal.trim().contains(" ") && !canonicalFirstTokens.contains(literal.trim().toUpperCase(Locale.ROOT))) {
				singleWordAliases.add(literal.trim().toUpperCase(Locale.ROOT));
			}
		}
		return filterByPrefix(singleWordAliases, word, CompletionCandidateType.BROADSQL_COMMAND, "alias");
	}

	private List<CompletionCandidate> completeNextToken(List<String> typedTokens, String word) {
		Set<String> nextTokens = new LinkedHashSet<>();
		for (Command command : distinctCommands()) {
			String[] tokens = canonicalTokens(command);
			if (tokens.length <= typedTokens.size()) {
				continue;
			}
			if (matchesPrefix(tokens, typedTokens)) {
				nextTokens.add(tokens[typedTokens.size()]);
			}
		}
		return filterByPrefix(nextTokens, word, CompletionCandidateType.BROADSQL_KEYWORD, null);
	}

	private boolean matchesPrefix(String[] canonicalTokens, List<String> typedTokens) {
		for (int i = 0; i < typedTokens.size(); i++) {
			if (!canonicalTokens[i].equalsIgnoreCase(typedTokens.get(i))) {
				return false;
			}
		}
		return true;
	}

	private Set<Command> distinctCommands() {
		Set<Command> result = new LinkedHashSet<>();
		for (Command command : commandList.values()) {
			// SPRINT 2309T: documented commands only - executable compatibility commands (EXPORT, SET
			// SEPARATOR, LOAD BATCH) still run when typed, but TAB no longer promotes them.
			if (command != null && CommandCategoryCatalog.isDocumented(command)) {
				result.add(command);
			}
		}
		return result;
	}

	private static String[] canonicalTokens(Command command) {
		String canonical = command.getKeywords()[0];
		if (canonical == null || canonical.trim().isEmpty()) {
			return new String[0];
		}
		String[] tokens = canonical.trim().toUpperCase(Locale.ROOT).split("\\s+");
		return tokens;
	}

	private static List<String> wordTokens(List<SqlToken> tokens) {
		List<String> words = new ArrayList<>();
		for (SqlToken token : tokens) {
			if (token.isType(SqlTokenType.WORD)) {
				words.add(token.getText().toUpperCase(Locale.ROOT));
			}
		}
		return words;
	}

	private static List<CompletionCandidate> filterByPrefix(Set<String> candidates, String word, CompletionCandidateType type, String description) {
		List<CompletionCandidate> result = new ArrayList<>();
		for (String candidate : candidates) {
			if (candidate.regionMatches(true, 0, word, 0, word.length())) {
				result.add(new CompletionCandidate(candidate, candidate, description, type));
			}
		}
		return result;
	}
}
