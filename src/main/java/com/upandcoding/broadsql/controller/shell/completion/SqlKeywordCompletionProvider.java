package com.upandcoding.broadsql.controller.shell.completion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidateType;
import com.upandcoding.broadsql.controller.shell.sql.SqlKeywords;
import com.upandcoding.broadsql.controller.shell.sql.SqlToken;
import com.upandcoding.broadsql.controller.shell.sql.SqlTokenType;

/**
 * Context-aware SQL keyword completion - SPRINT 0917-02, sections 6/7. Deliberately not a SQL parser
 * (section 6: "a pragmatic lexical/contextual implementation is acceptable") - it recognizes the
 * previous one or two significant tokens of the current statement and offers the small set of
 * keywords that plausibly follow, rather than either a formal grammar or (the thing section 7
 * explicitly rules out) "every SQL keyword beginning with the typed prefix".
 *
 * <p>Deliberately silent (returns no candidates) once the cursor is in a position a keyword clearly
 * cannot occupy (e.g. immediately after {@code FROM}/{@code JOIN}/{@code INTO}, where a relation name
 * is expected instead) - see {@link JdbcMetadataCompletionProvider}, which owns those positions.
 */
public final class SqlKeywordCompletionProvider implements CompletionProvider {

	private static final Set<String> STATEMENT_START = Set.of(
			"SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "TRUNCATE", "WITH", "MERGE", "GRANT",
			"REVOKE", "COMMIT", "ROLLBACK", "SAVEPOINT");

	@Override
	public List<CompletionCandidate> complete(CompletionContext context) {
		List<SqlToken> before = context.getStatementTokensBeforeCursor();
		String prev = lastWord(before);
		String first = context.getFirstWordOfStatement();

		Set<String> expected;
		if (before.isEmpty()) {
			expected = STATEMENT_START;
		} else if ("GROUP".equals(prev) || "ORDER".equals(prev)) {
			expected = Set.of("BY");
		} else if ("LEFT".equals(prev) || "RIGHT".equals(prev) || "FULL".equals(prev)) {
			expected = Set.of("JOIN", "OUTER");
		} else if ("OUTER".equals(prev) || "INNER".equals(prev) || "CROSS".equals(prev) || "NATURAL".equals(prev)) {
			expected = Set.of("JOIN");
		} else if ("CREATE".equals(prev)) {
			expected = Set.of("TABLE", "VIEW", "INDEX", "UNIQUE");
		} else if ("ALTER".equals(prev)) {
			expected = Set.of("TABLE", "VIEW");
		} else if ("DROP".equals(prev)) {
			expected = Set.of("TABLE", "VIEW", "INDEX");
		} else if ("BETWEEN".equals(prev)) {
			expected = Set.of(); // a value is expected next, not a keyword
		} else if ("IS".equals(prev)) {
			expected = Set.of("NULL", "NOT");
		} else if ("NOT".equals(prev)) {
			expected = Set.of("NULL", "IN", "EXISTS", "BETWEEN", "LIKE");
		} else if ("INSERT".equals(prev)) {
			expected = Set.of("INTO");
		} else if ("DELETE".equals(prev)) {
			expected = Set.of("FROM");
		} else {
			expected = clauseMenu(first, before, prev);
		}

		return filterByPrefix(expected, context.getWord());
	}

	/**
	 * The broad "what clause could plausibly come next" menu once we are past the statement's dynamic
	 * (table/column-name) parts - keyed by which clause keywords have already appeared, not by a full
	 * grammar. Returns an empty set once a relation/column name is expected instead (owned by
	 * {@link JdbcMetadataCompletionProvider}) - notably right after {@code FROM}, {@code JOIN},
	 * {@code INTO}, {@code UPDATE} or a schema-qualifying {@code .}.
	 */
	private Set<String> clauseMenu(String first, List<SqlToken> before, String prev) {
		if ("FROM".equals(prev) || "JOIN".equals(prev) || "INTO".equals(prev) || "UPDATE".equals(prev)) {
			return Set.of(); // a table name is expected here - JdbcMetadataCompletionProvider's territory
		}
		boolean hasFrom = containsKeyword(before, "FROM");
		boolean hasWhere = containsKeyword(before, "WHERE");
		boolean hasGroup = containsKeyword(before, "GROUP");
		boolean hasOrder = containsKeyword(before, "ORDER");
		boolean hasSet = containsKeyword(before, "SET");
		boolean hasValues = containsKeyword(before, "VALUES");

		if ("SELECT".equals(first)) {
			if ("ON".equals(prev) || "AND".equals(prev) || "OR".equals(prev)) {
				return Set.of(); // a condition is expected next, not a keyword
			}
			if (hasOrder) {
				return Set.of("ASC", "DESC", "LIMIT");
			}
			// SPRINT 0917-02, section 37's own examples ("SELECT region, COUNT(*) gr<TAB> -> GROUP") offer
			// a later clause even before an earlier one it would formally depend on (GROUP BY without FROM
			// yet typed) - deliberately a flat "what hasn't appeared yet" menu, not a strict clause order,
			// per section 6's "pragmatic ... not a full grammar".
			Set<String> menu = new java.util.LinkedHashSet<>();
			if (!hasFrom) {
				menu.add("FROM");
				menu.add("DISTINCT");
				menu.add("ALL");
			}
			if (!hasWhere) {
				menu.add("WHERE");
			}
			menu.add("JOIN");
			menu.add("INNER");
			menu.add("LEFT");
			menu.add("RIGHT");
			menu.add("FULL");
			if (!hasGroup) {
				menu.add("GROUP");
			} else {
				menu.add("HAVING");
			}
			menu.add("ORDER");
			menu.add("UNION");
			menu.add("LIMIT");
			return menu;
		}
		if ("UPDATE".equals(first)) {
			if (!hasSet) {
				return Set.of("SET");
			}
			return Set.of("WHERE", "AND", "OR");
		}
		if ("DELETE".equals(first)) {
			if (hasFrom) {
				return Set.of("WHERE", "AND", "OR");
			}
			return Set.of();
		}
		if ("INSERT".equals(first)) {
			if (hasValues) {
				return Set.of();
			}
			return Set.of("VALUES");
		}
		return Set.of();
	}

	private static boolean containsKeyword(List<SqlToken> tokens, String keyword) {
		for (SqlToken token : tokens) {
			if (token.isType(SqlTokenType.WORD) && token.is(keyword)) {
				return true;
			}
		}
		return false;
	}

	private static String lastWord(List<SqlToken> tokens) {
		for (int i = tokens.size() - 1; i >= 0; i--) {
			SqlToken token = tokens.get(i);
			if (token.isType(SqlTokenType.WORD)) {
				return token.getText().toUpperCase(Locale.ROOT);
			}
			if (token.isType(SqlTokenType.PUNCTUATION)) {
				return null; // e.g. right after a comma/paren - not a plain "after keyword X" position
			}
		}
		return null;
	}

	private static List<CompletionCandidate> filterByPrefix(Set<String> candidates, String word) {
		List<CompletionCandidate> result = new ArrayList<>();
		if (candidates.isEmpty()) {
			return result;
		}
		for (String keyword : SqlKeywords.ALL) {
			if (candidates.contains(keyword) && keyword.regionMatches(true, 0, word, 0, word.length())) {
				result.add(new CompletionCandidate(keyword, keyword, "SQL keyword", CompletionCandidateType.SQL_KEYWORD));
			}
		}
		return result;
	}
}
