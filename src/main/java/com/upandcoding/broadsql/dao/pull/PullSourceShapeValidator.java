package com.upandcoding.broadsql.dao.pull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Decides whether a {@code PULL} source query is simple enough for {@code MODE APPEND KEY(<column>)}
 * to safely inject a delta filter into it (see docs/EXPORT_TO_H2.md, "Modes" and "APPEND KEY(...)
 * opened up to joined queries"), and performs that injection.
 *
 * <p>A source is eligible when it is a {@code SELECT ... FROM <table>[ JOIN <table> ON ...]*[ WHERE
 * ...]}, nothing else - explicit {@code JOIN} syntax (any kind: {@code INNER}/{@code LEFT}/
 * {@code RIGHT}/{@code FULL OUTER}, any number of them) is allowed, but no comma-joined or derived
 * (subquery) {@code FROM}, no {@code GROUP BY}/{@code HAVING}/{@code ORDER BY}/{@code UNION}/
 * {@code INTERSECT}/{@code EXCEPT}/{@code MINUS}/{@code LIMIT}/{@code OFFSET}/{@code FETCH}. Comma
 * joins stay forbidden because they are easy to write by accident as an unintended cross join, and
 * {@code JOIN ... ON} already covers the same need explicitly; a derived table stays forbidden because
 * appending a filter clause at the end without a real SQL parser could land in the wrong place inside
 * it; aggregation stays forbidden on its own merits (an aggregate value can change for an existing
 * group as new source rows arrive, which is not "insert-only" - a {@code MERGE} concept, not
 * {@code APPEND}'s).
 *
 * <p>The {@code NULL}-key risk a {@code JOIN} can introduce (an unmatched outer-join row producing a
 * {@code NULL} key, which then never satisfies {@code key > watermark} and is permanently hidden from
 * every future delta pull) is <b>not</b> checked here - it is checked at runtime, against the executed
 * query's own {@link java.sql.ResultSetMetaData#isNullable}, by
 * {@link PullToH2Exporter#checkKeyNullability}. See that method's Javadoc for an important, empirically
 * confirmed limitation: that runtime check cannot be trusted for an outer join on every JDBC driver.
 *
 * <p>Only a lightweight, regex-based check - matching the rest of {@link PullCommandParser}, which
 * does not use a real SQL parser either. String literals and parenthesized regions are blanked out
 * (same length, so offsets keep lining up with the original text) before any keyword is searched for,
 * so a keyword appearing inside a literal or inside a legitimate subquery in the {@code WHERE} clause
 * (e.g. {@code WHERE ID IN (SELECT ID FROM X)}) does not trigger a false rejection.
 */
public final class PullSourceShapeValidator {

	private static final Pattern FORBIDDEN_KEYWORDS = Pattern.compile(
			"\\b(GROUP\\s+BY|HAVING|ORDER\\s+BY|UNION|INTERSECT|EXCEPT|MINUS|LIMIT|OFFSET|FETCH)\\b",
			Pattern.CASE_INSENSITIVE);

	/** Detects an outer join, for {@link #usesOuterJoin} - see that method's Javadoc for why it matters. */
	private static final Pattern OUTER_JOIN = Pattern.compile("\\b(LEFT|RIGHT|FULL)\\s+(OUTER\\s+)?JOIN\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern FROM_KEYWORD = Pattern.compile("\\bFROM\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern WHERE_KEYWORD = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]*$");

	private PullSourceShapeValidator() {
	}

	/**
	 * @throws BroadSQLException with an explicit, user-facing reason if {@code sourceQuery} is not
	 *                           eligible for {@code MODE APPEND KEY(...)} - see the class Javadoc for
	 *                           exactly what is and is not allowed
	 */
	static void validateEligible(String sourceQuery) throws BroadSQLException {
		String masked = maskParenthesizedRegions(maskStringLiterals(sourceQuery));

		Matcher forbidden = FORBIDDEN_KEYWORDS.matcher(masked);
		if (forbidden.find()) {
			throw new BroadSQLException("MODE APPEND KEY(...) does not support this source query shape in this version "
					+ "(no GROUP BY, HAVING, ORDER BY, UNION/INTERSECT/EXCEPT, or LIMIT/OFFSET/FETCH) - found '"
					+ forbidden.group().trim() + "'. Use a plain 'SELECT ... FROM <table>[ JOIN <table> ON ...] [WHERE ...]' "
					+ "source, or MODE OVERWRITE.");
		}

		Matcher fromMatcher = FROM_KEYWORD.matcher(masked);
		if (!fromMatcher.find()) {
			throw new BroadSQLException("Could not locate a FROM clause in the PULL source query");
		}
		int fromEnd = fromMatcher.end();

		Matcher whereMatcher = WHERE_KEYWORD.matcher(masked);
		int fromClauseEnd = (whereMatcher.find(fromEnd) && whereMatcher.start() > fromEnd) ? whereMatcher.start() : masked.length();

		String fromClause = masked.substring(fromEnd, fromClauseEnd).trim();
		if (fromClause.isEmpty()) {
			throw new BroadSQLException("Could not locate a FROM clause in the PULL source query");
		}
		if (fromClause.contains(",") || fromClause.contains("(")) {
			throw new BroadSQLException("MODE APPEND KEY(...) does not support this FROM clause in this version "
					+ "(no comma-joined tables, no derived table/subquery - use an explicit JOIN instead) - found: '"
					+ fromClause + "'. Use MODE OVERWRITE, or wait for MODE MERGE.");
		}
	}

	/**
	 * Whether {@code sourceQuery} uses an explicit outer join ({@code LEFT}/{@code RIGHT}/{@code FULL
	 * [OUTER]} {@code JOIN}) - used by {@code CommandPull} to print an unconditional caution note for
	 * {@code MODE APPEND KEY(...)}, independent of what {@link PullToH2Exporter#checkKeyNullability}
	 * concludes. Reason it needs to be unconditional: verified empirically (real H2 2.3.232 database,
	 * see docs/EXPORT_TO_H2.md, "APPEND KEY(...) opened up to joined queries") that
	 * {@code ResultSetMetaData.isNullable()} confidently reports a {@code LEFT JOIN}'s unmatched-side
	 * key column as "cannot be NULL" even when the actual result row has a {@code NULL} in it - so that
	 * check alone cannot be relied on to warn about this specific, real risk.
	 */
	public static boolean usesOuterJoin(String sourceQuery) {
		String masked = maskParenthesizedRegions(maskStringLiterals(sourceQuery));
		return OUTER_JOIN.matcher(masked).find();
	}

	/**
	 * Splices a {@code <key> > ?} delta filter into {@code sourceQuery}, which must already have passed
	 * {@link #validateEligible}: it is known to end either after a {@code WHERE} condition or right after the
	 * {@code FROM} clause (a single table, or a {@code JOIN ... ON ...} chain), with nothing else following.
	 *
	 * <p>An existing condition keeps its meaning whatever it contains: it is wrapped in parentheses,
	 * {@code WHERE (<condition>) AND (<key> > ?)}, so {@code A = 1 OR B = 2} can never become
	 * {@code A = 1 OR (B = 2 AND <key> > ?)}, which would pull again rows already appended. The closing
	 * parenthesis goes on its own line when the query contains a {@code --} comment, which would otherwise
	 * swallow it.
	 *
	 * <p>{@code <key>} is the expression the select list gives the result column named {@code keyColumn}
	 * ({@link #keyExpression}): {@code WHERE} cannot see select-list aliases, and an unqualified name can be
	 * ambiguous in a {@code JOIN}.
	 */
	public static String appendKeyFilter(String sourceQuery, String keyColumn) throws BroadSQLException {
		if (!IDENTIFIER.matcher(keyColumn).matches()) {
			throw new BroadSQLException("Invalid KEY column '" + keyColumn + "' - expected a plain identifier");
		}
		String trimmed = sourceQuery.trim();
		String masked = maskParenthesizedRegions(maskStringLiterals(trimmed));
		String key = keyExpression(trimmed, masked, keyColumn);
		boolean lineComment = masked.contains("--");
		Matcher from = FROM_KEYWORD.matcher(masked);
		Matcher where = WHERE_KEYWORD.matcher(masked);
		if (from.find() && where.find(from.end())) {
			String condition = trimmed.substring(where.end()).trim();
			return trimmed.substring(0, where.start()) + "WHERE (" + condition + (lineComment ? "\n" : "") + ") AND (" + key + " > ?)";
		}
		return trimmed + (lineComment ? "\n" : " ") + "WHERE " + key + " > ?";
	}

	/** A select-list alias: {@code <expression> AS <alias>}. */
	private static final Pattern AS_ALIAS = Pattern.compile("(?is)^(.*\\S)\\s+AS\\s+(\"?)([A-Za-z_][A-Za-z0-9_$]*)\\2$");
	/** A plain column reference, optionally qualified, optionally followed by an alias without {@code AS}. */
	private static final Pattern COLUMN_REFERENCE = Pattern
			.compile("(?s)^((?:[A-Za-z_][A-Za-z0-9_$]*\\.)*([A-Za-z_][A-Za-z0-9_$]*))(?:\\s+(\"?)([A-Za-z_][A-Za-z0-9_$]*)\\3)?$");
	private static final Pattern SELECT_KEYWORD = Pattern.compile("(?i)^\\s*SELECT\\s+((DISTINCT|ALL)\\s+)?");

	/**
	 * The SQL expression that produces the result column {@code keyColumn}, for use in {@code WHERE}: the
	 * expression of a select-list item {@code <expression> AS <keyColumn>} ({@code O.ID} for
	 * {@code O.ID AS ORDER_ID}), or the qualified column of an item {@code T.<keyColumn>} ({@code C.ID} for
	 * {@code KEY(ID)} over {@code C.ID}). {@code keyColumn} itself when no single item defines it (a
	 * {@code SELECT *}, or an item the check cannot read).
	 */
	static String keyExpression(String query, String masked, String keyColumn) {
		Matcher select = SELECT_KEYWORD.matcher(masked);
		Matcher from = FROM_KEYWORD.matcher(masked);
		if (!select.find() || !from.find(select.end())) {
			return keyColumn;
		}
		String found = null;
		int start = select.end();
		int end = from.start();
		int itemStart = start;
		for (int i = start; i <= end; i++) {
			if (i == end || masked.charAt(i) == ',') {
				String maskedItem = masked.substring(itemStart, i).trim();
				String item = query.substring(itemStart, i).trim();
				String expression = itemExpression(maskedItem, item, keyColumn);
				if (expression != null) {
					if (found != null) {
						// two items name the key column: the export itself reports the duplicate label
						return keyColumn;
					}
					found = expression;
				}
				itemStart = i + 1;
			}
		}
		return found == null ? keyColumn : found;
	}

	/** The expression of one select-list item when it produces the column {@code keyColumn}, otherwise {@code null}. */
	private static String itemExpression(String maskedItem, String item, String keyColumn) {
		Matcher alias = AS_ALIAS.matcher(maskedItem);
		if (alias.matches()) {
			return alias.group(3).equalsIgnoreCase(keyColumn) ? item.substring(0, alias.end(1)).trim() : null;
		}
		Matcher column = COLUMN_REFERENCE.matcher(maskedItem);
		if (column.matches()) {
			String label = column.group(4) != null ? column.group(4) : column.group(2);
			return label.equalsIgnoreCase(keyColumn) ? column.group(1) : null;
		}
		return null;
	}

	/**
	 * Replaces the content of every single-quoted string literal (doubled {@code ''} - the SQL-standard
	 * escaped quote - handled the same way as {@link PullCommandParser#findMatchingParen}) with spaces,
	 * keeping the string the same length so offsets found in the result still apply to the original.
	 */
	private static String maskStringLiterals(String sql) {
		char[] chars = sql.toCharArray();
		boolean inLiteral = false;
		for (int i = 0; i < chars.length; i++) {
			if (chars[i] == '\'') {
				inLiteral = !inLiteral;
			} else if (inLiteral) {
				chars[i] = ' ';
			}
		}
		return new String(chars);
	}

	/** Replaces the content strictly inside every parenthesized region (any depth) with spaces, same length. */
	private static String maskParenthesizedRegions(String sql) {
		char[] chars = sql.toCharArray();
		int depth = 0;
		for (int i = 0; i < chars.length; i++) {
			char c = chars[i];
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
				if (depth < 0) {
					depth = 0;
				}
			} else if (depth > 0) {
				chars[i] = ' ';
			}
		}
		return new String(chars);
	}
}
