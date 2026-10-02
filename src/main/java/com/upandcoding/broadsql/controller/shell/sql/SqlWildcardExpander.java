package com.upandcoding.broadsql.controller.shell.sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implements {@code EXPAND;} (see docs/SPRINT_0912A_EXPAND_FORMAT_SQL_ERRORS.md, section 4):
 * replaces a supported {@code SELECT *} / {@code alias.*} projection with the real column names of
 * the relation it refers to, resolved from database metadata via {@link SqlRelationColumnResolver}.
 *
 * <p>This is a conservative, hand-rolled scan over {@link SqlTokenizer}'s output - not a SQL
 * parser. It recognizes exactly enough shape to find the projection list and the {@code FROM}/
 * {@code JOIN} relation list safely; anything it cannot identify unambiguously is reported as
 * unsupported and the original SQL is returned untouched (never guessed). Supported shapes:
 *
 * <pre>
 * SELECT * FROM CUSTOMER
 * SELECT * FROM SCHEMA.CUSTOMER
 * SELECT c.* FROM CUSTOMER c
 * SELECT c.* FROM CUSTOMER AS c
 * SELECT c.ID, c.* FROM CUSTOMER c
 * SELECT c.*, o.ORDER_ID FROM CUSTOMER c JOIN ORDERS o ON o.CUSTOMER_ID = c.CUSTOMER_ID
 * </pre>
 *
 * <p>Explicitly unsupported: a naked {@code *} over more than one relation (its result-column order
 * and duplicate-name behavior cannot be reproduced reliably - qualify with {@code alias.*} instead);
 * a derived table/subquery or CTE as the {@code FROM} source; an alias that cannot be resolved or is
 * used for more than one relation; a statement that is not a single {@code SELECT}; a projection
 * with no expandable {@code *}/{@code alias.*} at all. {@code COUNT(*)}, arithmetic {@code a*b}, and
 * any {@code *} inside a string literal or comment are never touched - they are simply never
 * standalone projection items, so this scan never even considers them.
 */
public final class SqlWildcardExpander {

	private static final Set<String> JOIN_STARTERS = Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL");

	private static final Set<String> RELATION_LIST_TERMINATORS = Set.of(
			"WHERE", "GROUP", "ORDER", "HAVING", "UNION", "INTERSECT", "EXCEPT", "MINUS", "LIMIT", "FETCH", "FOR", "WINDOW");

	private final SqlRelationColumnResolver columnResolver;

	public SqlWildcardExpander(SqlRelationColumnResolver columnResolver) {
		this.columnResolver = columnResolver;
	}

	public WildcardExpansionResult expand(String sql) {
		if (sql == null || sql.isBlank()) {
			return WildcardExpansionResult.unsupported("No query to expand.");
		}

		List<SqlToken> tokens;
		try {
			tokens = SqlTokenizer.tokenize(sql);
		} catch (SqlLexException e) {
			return WildcardExpansionResult.unsupported("Cannot safely parse the current SQL (" + e.getMessage() + "). Current query unchanged.");
		}

		if (hasTopLevelSemicolon(tokens)) {
			return WildcardExpansionResult.unsupported("EXPAND does not support multiple statements. Current query unchanged.");
		}

		int selectIdx = firstSignificant(tokens, 0);
		if (selectIdx < 0 || !tokens.get(selectIdx).is("SELECT")) {
			return WildcardExpansionResult.unsupported("EXPAND applies only to a SELECT statement. Current query unchanged.");
		}

		int projStart = firstSignificant(tokens, selectIdx + 1);
		if (projStart >= 0 && (tokens.get(projStart).is("DISTINCT") || tokens.get(projStart).is("ALL"))) {
			projStart = firstSignificant(tokens, projStart + 1);
		}
		if (projStart < 0) {
			return WildcardExpansionResult.unsupported("EXPAND applies only to a SELECT statement with a resolvable FROM clause. Current query unchanged.");
		}

		int fromIdx = findTopLevelFrom(tokens, projStart);
		if (fromIdx < 0) {
			return WildcardExpansionResult.unsupported("EXPAND applies only to a SELECT statement with a resolvable FROM clause. Current query unchanged.");
		}

		List<List<SqlToken>> items = splitProjectionItems(tokens, projStart, fromIdx);

		RelationListResult relationListResult = parseRelationList(tokens, fromIdx + 1);
		if (!relationListResult.ok) {
			return WildcardExpansionResult.unsupported(relationListResult.reason + " Current query unchanged.");
		}

		List<StarItem> starItems = new ArrayList<>();
		for (List<SqlToken> item : items) {
			List<SqlToken> core = significantOnly(item);
			if (core.size() == 1 && core.get(0).isType(SqlTokenType.PUNCTUATION) && core.get(0).is("*")) {
				starItems.add(new StarItem(null, core.get(0).getStart(), core.get(0).getEnd()));
			} else if (core.size() == 3 && isIdentifierToken(core.get(0)) && core.get(1).is(".") && core.get(2).is("*")) {
				starItems.add(new StarItem(identifierText(core.get(0)), core.get(0).getStart(), core.get(2).getEnd()));
			}
		}

		if (starItems.isEmpty()) {
			return WildcardExpansionResult.unsupported(
					"EXPAND applies to a SELECT containing a resolvable * or alias.* projection. Current query unchanged.");
		}

		for (StarItem star : starItems) {
			if (star.alias == null && relationListResult.relations.size() > 1) {
				return WildcardExpansionResult.unsupported(
						"A naked * over more than one table/JOIN is not supported - qualify it as alias.*.");
			}
		}

		Map<String, List<String>> resolvedColumnsByRelation = new LinkedHashMap<>();
		List<Replacement> replacements = new ArrayList<>();
		for (StarItem star : starItems) {
			String relationRef;
			if (star.alias == null) {
				relationRef = relationListResult.relations.get(0).reference;
			} else {
				Relation relation = relationListResult.byAlias.get(star.alias.toUpperCase());
				if (relation == null) {
					return WildcardExpansionResult.unsupported("Cannot resolve relation for alias '" + star.alias + "'. Current query unchanged.");
				}
				relationRef = relation.reference;
			}

			List<String> columns = resolvedColumnsByRelation.get(relationRef);
			if (columns == null) {
				try {
					columns = columnResolver.resolveColumns(relationRef);
				} catch (Exception e) {
					return WildcardExpansionResult.unsupported(
							"Cannot resolve columns for '" + relationRef + "': " + e.getMessage() + ". Current query unchanged.");
				}
				if (columns == null || columns.isEmpty()) {
					return WildcardExpansionResult.unsupported("No columns found for '" + relationRef + "'. Current query unchanged.");
				}
				resolvedColumnsByRelation.put(relationRef, columns);
			}

			String replacementText = star.alias == null
					? String.join(",\n    ", columns)
					: columns.stream().map(col -> star.alias + "." + col).reduce((a, b) -> a + ",\n    " + b).orElse("");
			replacements.add(new Replacement(star.start, star.end, replacementText));
		}

		return WildcardExpansionResult.success(applyReplacements(sql, replacements));
	}

	// ---- projection scanning -------------------------------------------------------------

	private static List<List<SqlToken>> splitProjectionItems(List<SqlToken> tokens, int fromIndex, int toIndexExclusive) {
		List<List<SqlToken>> items = new ArrayList<>();
		List<SqlToken> current = new ArrayList<>();
		int depth = 0;
		for (int i = fromIndex; i < toIndexExclusive; i++) {
			SqlToken t = tokens.get(i);
			if (t.isType(SqlTokenType.PUNCTUATION) && t.is("(")) {
				depth++;
			} else if (t.isType(SqlTokenType.PUNCTUATION) && t.is(")")) {
				depth--;
			}
			if (depth == 0 && t.isType(SqlTokenType.PUNCTUATION) && t.is(",")) {
				items.add(current);
				current = new ArrayList<>();
				continue;
			}
			current.add(t);
		}
		items.add(current);
		return items;
	}

	private static List<SqlToken> significantOnly(List<SqlToken> item) {
		List<SqlToken> result = new ArrayList<>();
		for (SqlToken t : item) {
			if (!t.isInsignificant()) {
				result.add(t);
			}
		}
		return result;
	}

	private static int findTopLevelFrom(List<SqlToken> tokens, int fromIndex) {
		int depth = 0;
		for (int i = fromIndex; i < tokens.size(); i++) {
			SqlToken t = tokens.get(i);
			if (t.isType(SqlTokenType.PUNCTUATION) && t.is("(")) {
				depth++;
			} else if (t.isType(SqlTokenType.PUNCTUATION) && t.is(")")) {
				depth--;
			} else if (depth == 0 && t.isType(SqlTokenType.WORD) && t.is("FROM")) {
				return i;
			}
		}
		return -1;
	}

	private static boolean hasTopLevelSemicolon(List<SqlToken> tokens) {
		int depth = 0;
		for (SqlToken t : tokens) {
			if (t.isType(SqlTokenType.PUNCTUATION) && t.is("(")) {
				depth++;
			} else if (t.isType(SqlTokenType.PUNCTUATION) && t.is(")")) {
				depth--;
			} else if (depth == 0 && t.isType(SqlTokenType.PUNCTUATION) && t.is(";")) {
				return true;
			}
		}
		return false;
	}

	private static int firstSignificant(List<SqlToken> tokens, int fromIndex) {
		for (int i = fromIndex; i < tokens.size(); i++) {
			if (!tokens.get(i).isInsignificant()) {
				return i;
			}
		}
		return -1;
	}

	// ---- FROM / JOIN relation-list scanning -----------------------------------------------

	private static boolean isIdentifierToken(SqlToken t) {
		return t.isType(SqlTokenType.WORD) || t.isType(SqlTokenType.QUOTED_IDENTIFIER);
	}

	private static String identifierText(SqlToken t) {
		if (t.isType(SqlTokenType.QUOTED_IDENTIFIER)) {
			char quote = t.getText().charAt(0);
			String inner = t.getText().substring(1, t.getText().length() - 1);
			return inner.replace("" + quote + quote, "" + quote);
		}
		return t.getText();
	}

	private static final class Relation {
		final String reference;
		final String alias;

		Relation(String reference, String alias) {
			this.reference = reference;
			this.alias = alias;
		}
	}

	private static final class RelationListResult {
		boolean ok;
		String reason;
		List<Relation> relations = new ArrayList<>();
		Map<String, Relation> byAlias = new LinkedHashMap<>();

		static RelationListResult fail(String reason) {
			RelationListResult r = new RelationListResult();
			r.ok = false;
			r.reason = reason;
			return r;
		}
	}

	private static final class RelationParseOutcome {
		Relation relation;
		int nextIndex;
		String error;

		static RelationParseOutcome fail(String error) {
			RelationParseOutcome o = new RelationParseOutcome();
			o.error = error;
			return o;
		}

		static RelationParseOutcome of(Relation relation, int nextIndex) {
			RelationParseOutcome o = new RelationParseOutcome();
			o.relation = relation;
			o.nextIndex = nextIndex;
			return o;
		}
	}

	/** Parses the {@code FROM}/{@code JOIN}/comma relation list starting right after {@code FROM}. */
	private RelationListResult parseRelationList(List<SqlToken> tokens, int startIndex) {
		RelationListResult result = new RelationListResult();
		result.ok = true;

		int i = firstSignificant(tokens, startIndex);
		if (i < 0) {
			return RelationListResult.fail("Unexpected end of statement after FROM.");
		}
		RelationParseOutcome outcome = parseOneRelation(tokens, i);
		if (outcome.error != null) {
			return RelationListResult.fail(outcome.error);
		}
		if (!registerRelation(result, outcome.relation)) {
			return RelationListResult.fail("Ambiguous alias '" + outcome.relation.alias + "' used for more than one relation.");
		}
		int next = outcome.nextIndex;

		while (true) {
			if (next < 0 || next >= tokens.size()) {
				return result;
			}
			SqlToken t = tokens.get(next);
			if (t.isType(SqlTokenType.PUNCTUATION) && t.is(",")) {
				int relStart = firstSignificant(tokens, next + 1);
				if (relStart < 0) {
					return RelationListResult.fail("Unexpected end of statement after ','.");
				}
				RelationParseOutcome joined = parseOneRelation(tokens, relStart);
				if (joined.error != null) {
					return RelationListResult.fail(joined.error);
				}
				if (!registerRelation(result, joined.relation)) {
					return RelationListResult.fail("Ambiguous alias '" + joined.relation.alias + "' used for more than one relation.");
				}
				next = joined.nextIndex;
				continue;
			}
			if (t.isType(SqlTokenType.WORD) && JOIN_STARTERS.contains(t.getText().toUpperCase())) {
				int relStart = skipJoinTypeKeywords(tokens, next);
				if (relStart < 0) {
					return RelationListResult.fail("Cannot resolve a JOIN clause in the FROM clause.");
				}
				RelationParseOutcome joined = parseOneRelation(tokens, relStart);
				if (joined.error != null) {
					return RelationListResult.fail(joined.error);
				}
				if (!registerRelation(result, joined.relation)) {
					return RelationListResult.fail("Ambiguous alias '" + joined.relation.alias + "' used for more than one relation.");
				}
				next = skipOptionalOnClause(tokens, joined.nextIndex);
				continue;
			}
			// WHERE / GROUP / ORDER / HAVING / UNION / semicolon / end of statement: relation list done.
			return result;
		}
	}

	private static boolean registerRelation(RelationListResult result, Relation relation) {
		result.relations.add(relation);
		String key = relation.alias.toUpperCase();
		if (result.byAlias.containsKey(key)) {
			return false;
		}
		result.byAlias.put(key, relation);
		return true;
	}

	private static String lastSegment(String reference) {
		int dot = reference.lastIndexOf('.');
		return dot >= 0 ? reference.substring(dot + 1) : reference;
	}

	/**
	 * Parses one relation - an identifier chain ({@code table}, {@code schema.table}, ...) and an
	 * optional {@code [AS] alias} - starting at {@code startIndex} (which must already be a
	 * significant token). {@link Relation#alias} is always non-null: the relation's own bare table
	 * name (last segment) when none is given, so {@code SELECT CUSTOMER.* FROM CUSTOMER} resolves
	 * the same way an explicitly aliased relation would.
	 */
	private static RelationParseOutcome parseOneRelation(List<SqlToken> tokens, int startIndex) {
		SqlToken first = tokens.get(startIndex);
		if (first.isType(SqlTokenType.PUNCTUATION) && first.is("(")) {
			return RelationParseOutcome.fail("A derived table/subquery FROM source is not supported.");
		}
		if (first.isType(SqlTokenType.WORD) && first.is("WITH")) {
			return RelationParseOutcome.fail("A CTE FROM source is not supported.");
		}
		if (!isIdentifierToken(first)) {
			return RelationParseOutcome.fail("Cannot resolve the FROM clause.");
		}

		StringBuilder reference = new StringBuilder(identifierText(first));
		int next = firstSignificant(tokens, startIndex + 1);
		while (next >= 0 && tokens.get(next).is(".")) {
			int identIdx = firstSignificant(tokens, next + 1);
			if (identIdx < 0 || !isIdentifierToken(tokens.get(identIdx))) {
				break;
			}
			reference.append('.').append(identifierText(tokens.get(identIdx)));
			next = firstSignificant(tokens, identIdx + 1);
		}

		String alias = null;
		if (next >= 0 && tokens.get(next).is("AS")) {
			int aliasIdx = firstSignificant(tokens, next + 1);
			if (aliasIdx < 0 || !isIdentifierToken(tokens.get(aliasIdx))) {
				return RelationParseOutcome.fail("Expected an alias after AS in the FROM clause.");
			}
			alias = identifierText(tokens.get(aliasIdx));
			next = firstSignificant(tokens, aliasIdx + 1);
		} else if (next >= 0 && isIdentifierToken(tokens.get(next))
				&& !RELATION_LIST_TERMINATORS.contains(tokens.get(next).getText().toUpperCase())
				&& !JOIN_STARTERS.contains(tokens.get(next).getText().toUpperCase())
				&& !tokens.get(next).is("ON")) {
			alias = identifierText(tokens.get(next));
			next = firstSignificant(tokens, next + 1);
		}

		Relation relation = new Relation(reference.toString(), alias != null ? alias : lastSegment(reference.toString()));
		return RelationParseOutcome.of(relation, next < 0 ? tokens.size() : next);
	}

	/**
	 * Consumes the join-type keyword sequence starting at {@code joinStartIndex} (e.g. {@code JOIN},
	 * {@code LEFT JOIN}, {@code LEFT OUTER JOIN}, {@code CROSS JOIN}) and returns the index of the
	 * joined relation's first token, or -1 if the sequence never reaches {@code JOIN}.
	 */
	private static int skipJoinTypeKeywords(List<SqlToken> tokens, int joinStartIndex) {
		int i = joinStartIndex;
		while (i < tokens.size()) {
			SqlToken t = tokens.get(i);
			boolean isJoinTypeWord = t.isType(SqlTokenType.WORD) && (JOIN_STARTERS.contains(t.getText().toUpperCase()) || t.is("OUTER"));
			if (!isJoinTypeWord) {
				return -1;
			}
			if (t.is("JOIN")) {
				return firstSignificant(tokens, i + 1);
			}
			int next = firstSignificant(tokens, i + 1);
			if (next < 0) {
				return -1;
			}
			i = next;
		}
		return -1;
	}

	/** If the next significant token is ON, skips the join condition up to (not including) the next top-level separator/terminator. */
	private static int skipOptionalOnClause(List<SqlToken> tokens, int fromIndexOrSize) {
		if (fromIndexOrSize >= tokens.size()) {
			return fromIndexOrSize;
		}
		int i = firstSignificant(tokens, fromIndexOrSize);
		if (i < 0 || !tokens.get(i).is("ON")) {
			return fromIndexOrSize;
		}
		i = firstSignificant(tokens, i + 1);
		int depth = 0;
		while (i >= 0 && i < tokens.size()) {
			SqlToken t = tokens.get(i);
			if (t.isType(SqlTokenType.PUNCTUATION) && t.is("(")) {
				depth++;
			} else if (t.isType(SqlTokenType.PUNCTUATION) && t.is(")")) {
				depth--;
			} else if (depth == 0 && t.isType(SqlTokenType.PUNCTUATION) && (t.is(",") || t.is(";"))) {
				return i;
			} else if (depth == 0 && t.isType(SqlTokenType.WORD)
					&& (JOIN_STARTERS.contains(t.getText().toUpperCase()) || RELATION_LIST_TERMINATORS.contains(t.getText().toUpperCase()))) {
				return i;
			}
			i++;
		}
		return tokens.size();
	}

	// ---- rendering -------------------------------------------------------------------------

	private static final class StarItem {
		final String alias;
		final int start;
		final int end;

		StarItem(String alias, int start, int end) {
			this.alias = alias;
			this.start = start;
			this.end = end;
		}
	}

	private static final class Replacement {
		final int start;
		final int end;
		final String text;

		Replacement(int start, int end, String text) {
			this.start = start;
			this.end = end;
			this.text = text;
		}
	}

	private static String applyReplacements(String original, List<Replacement> replacements) {
		List<Replacement> sorted = new ArrayList<>(replacements);
		sorted.sort((a, b) -> Integer.compare(a.start, b.start));
		StringBuilder out = new StringBuilder();
		int cursor = 0;
		for (Replacement r : sorted) {
			out.append(original, cursor, r.start);
			out.append(r.text);
			cursor = r.end;
		}
		out.append(original, cursor, original.length());
		return out.toString();
	}
}
