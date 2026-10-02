package com.upandcoding.broadsql.controller.shell.sql.error;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.h2.jdbc.JdbcException;

import com.upandcoding.broadsql.controller.errors.SqlExecutionException;

/**
 * Builds the readable "SQL ERROR" diagnostic block from docs/SPRINT_0912A_EXPAND_FORMAT_SQL_ERRORS.md,
 * section 6: the database/driver's own message, SQLState and error code stay authoritative and
 * fully visible; this only adds structure around them and, when a {@link SqlErrorLocationResolver}
 * can establish one reliably, a caret into the <em>exact</em> SQL that was submitted (never a
 * reformatted copy - see section 6.4).
 */
public final class SqlErrorRenderer {

	private static final List<SqlErrorLocationResolver> RESOLVERS = List.of(new H2SqlErrorLocationResolver());
	private static final String RULE = "------------------------------------------------------------";

	private SqlErrorRenderer() {
	}

	public static String render(SqlExecutionException error) {
		SQLException sqlException = error.getSqlException();
		String sql = error.getSql();

		StringBuilder out = new StringBuilder();
		out.append("SQL ERROR\n").append(RULE).append('\n');
		out.append("Connection : ").append(displayOrNone(error.getConnectionId())).append('\n');
		out.append("SQLState   : ").append(displayOrNone(sqlException.getSQLState())).append('\n');
		out.append("Error code : ").append(sqlException.getErrorCode()).append('\n');
		out.append('\n');
		out.append(cleanMessage(sqlException)).append('\n');

		if (sql != null && !sql.isEmpty()) {
			Optional<SqlErrorLocation> location = resolveLocation(sql, sqlException);
			out.append('\n').append("Query:\n");
			appendQueryBlock(out, sql, location.orElse(null));
		}
		out.append(RULE);

		List<String> chained = distinctChainedMessages(sqlException);
		if (!chained.isEmpty()) {
			out.append('\n').append("Additional database error(s):\n");
			for (String line : chained) {
				out.append("  ").append(line).append('\n');
			}
			out.append(RULE);
		}

		return out.toString();
	}

	private static Optional<SqlErrorLocation> resolveLocation(String sql, SQLException exception) {
		for (SqlErrorLocationResolver resolver : RESOLVERS) {
			Optional<SqlErrorLocation> location = resolver.resolve(sql, exception);
			if (location.isPresent()) {
				return location;
			}
		}
		return Optional.empty();
	}

	/**
	 * The authoritative driver message, with H2's own embedded escaped SQL-statement echo removed -
	 * BroadSQL renders the SQL itself, cleanly and unescaped, in the {@code Query:} section below, so
	 * showing it a second time (with H2's own {@code \XXXX}-escaped control characters and {@code [*]}
	 * marker still in it) would be exactly the unreadable duplication this sprint exists to fix. Only
	 * the one quoted span that actually contains the {@code [*]} marker is removed - an unrelated
	 * quoted parameter in the same message (e.g. H2's {@code expected "..."} clause) is untouched.
	 * Every other driver's message is shown completely as-is.
	 */
	private static String cleanMessage(SQLException e) {
		String message = e.getMessage();
		if (message == null) {
			return "(no message)";
		}
		if (e instanceof JdbcException) {
			String original = ((JdbcException) e).getOriginalMessage();
			if (original != null && !original.isBlank()) {
				message = original;
			}
			message = stripEmbeddedSqlEcho(message);
		}
		return message;
	}

	/**
	 * H2 (at least in some locales) repeats the whole syntax-error sentence once translated and once
	 * in English, each with its own quoted, marker-bearing SQL echo (see class Javadoc) - so every
	 * such span is stripped, not just the first, looping until none remain.
	 */
	private static String stripEmbeddedSqlEcho(String message) {
		String result = message;
		while (true) {
			int searchFrom = 0;
			int replacedAt = -1;
			int replacedEnd = -1;
			while (true) {
				int quoteStart = result.indexOf('"', searchFrom);
				if (quoteStart < 0) {
					break;
				}
				H2SqlErrorLocationResolver.DecodedSpan span = H2SqlErrorLocationResolver.decodeQuotedSpan(result, quoteStart + 1);
				if (span == null) {
					return result; // fail closed - leave the message exactly as the driver produced it
				}
				if (span.decoded.contains("[*]")) {
					replacedAt = quoteStart;
					replacedEnd = span.endExclusive;
					break;
				}
				searchFrom = span.endExclusive;
			}
			if (replacedAt < 0) {
				return result;
			}
			result = result.substring(0, replacedAt) + "(see below)" + result.substring(replacedEnd);
		}
	}

	private static void appendQueryBlock(StringBuilder out, String sql, SqlErrorLocation location) {
		String[] lines = sql.split("\r\n|\n", -1);
		int gutterWidth = Math.max(1, String.valueOf(lines.length).length());
		boolean caretRendered = false;
		for (int idx = 0; idx < lines.length; idx++) {
			int lineNo = idx + 1;
			out.append(padLeft(String.valueOf(lineNo), gutterWidth)).append(" | ").append(lines[idx]).append('\n');
			if (location != null && location.getLine() == lineNo) {
				String content = lines[idx];
				// A tab makes any single-character-per-column caret potentially misleading - prefer a
				// trustworthy "line X, column Y" note over a caret that may not visually line up (section 7.5).
				if (!content.contains("\t") && location.getColumn() - 1 <= content.length()) {
					out.append(" ".repeat(gutterWidth)).append(" | ")
							.append(" ".repeat(location.getColumn() - 1)).append('^').append('\n');
					caretRendered = true;
				}
			}
		}
		if (location != null && !caretRendered) {
			out.append("(error at line ").append(location.getLine()).append(", column ").append(location.getColumn()).append(")\n");
		}
	}

	private static String padLeft(String s, int width) {
		StringBuilder b = new StringBuilder();
		for (int i = s.length(); i < width; i++) {
			b.append(' ');
		}
		return b.append(s).toString();
	}

	/**
	 * Distinct chained {@link SQLException}s beyond the primary one, each rendered as
	 * {@code [SQLState-code] message} - identical (message, SQLState, error code) repeats are
	 * suppressed (section 6.7). Full stack traces are unaffected - they still go through whatever
	 * logging path already captured them, nothing about that changes here.
	 */
	private static List<String> distinctChainedMessages(SQLException primary) {
		List<String> result = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		seen.add(chainKey(primary));
		SQLException next = primary.getNextException();
		while (next != null) {
			if (seen.add(chainKey(next))) {
				result.add("[" + displayOrNone(next.getSQLState()) + "-" + next.getErrorCode() + "] " + cleanMessage(next));
			}
			next = next.getNextException();
		}
		return result;
	}

	private static String chainKey(SQLException e) {
		return e.getSQLState() + "|" + e.getErrorCode() + "|" + e.getMessage();
	}

	private static String displayOrNone(String s) {
		return (s == null || s.isBlank()) ? "(none)" : s;
	}
}
