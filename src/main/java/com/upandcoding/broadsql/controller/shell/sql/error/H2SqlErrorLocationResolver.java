package com.upandcoding.broadsql.controller.shell.sql.error;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Resolves the {@code [*]} error-position marker H2 embeds in its own syntax-error messages - the
 * marker this sprint was asked to investigate (docs/SPRINT_0912A_EXPAND_FORMAT_SQL_ERRORS.md,
 * section 6.6). Verified empirically against H2 2.3.232 (the version bundled by this project,
 * pom.xml) by reading its source (H2's own Maven-published sources jar) and reproducing every case
 * below with a real H2 in-memory connection - not assumed from documentation, since none of this is
 * part of H2's public/documented contract:
 *
 * <ul>
 * <li>{@code [*]} is H2-specific ({@code org.h2.message.DbException.getSyntaxError} ->
 * {@code StringUtils.addAsterisk}) - it is <b>not</b> a JDBC standard, and other drivers never emit
 * it. It only ever appears for genuine syntax errors ({@code JdbcSQLSyntaxErrorException}); a
 * semantic error like "table not found" carries no marker at all.</li>
 * <li>The marker appears <b>only</b> inside the human-readable message
 * ({@code SQLException#getMessage()}) - never in {@code JdbcException#getSQL()}, which H2 always
 * returns clean (no marker). It sits inside a double-quoted echo of the SQL text that is itself
 * escaped for display by H2's internal {@code DbException.quote()}: every character whose
 * {@link Character#getType} is a control/format/separator/surrogate/unassigned category (except a
 * plain space) is rendered as {@code \XXXX} (4 hex digits - or {@code \+XXXXXX}, 6 hex digits, for
 * a codepoint above the BMP), and a literal {@code "} or {@code \} in the original text is doubled.
 * Everything else - letters, digits, and the marker's own {@code [}, {@code *}, {@code ]} - passes
 * through unescaped. This resolver reverses exactly that encoding to recover the true 0-based
 * character offset the marker represents into H2's own (raw, unescaped) copy of the SQL text - which
 * is character-for-character identical to the exact SQL BroadSQL submitted to JDBC, since neither
 * BroadSQL nor H2 modifies it in between.</li>
 * </ul>
 *
 * <p>This is not documented, guaranteed API - a future H2 version could change the message format.
 * Consistent with section 6.5 ("fail closed"), any span that does not decode cleanly, or a message
 * with no quoted span containing a literal {@code [*]}, simply yields {@link Optional#empty()}
 * rather than a guessed position; nothing here ever throws.
 */
public final class H2SqlErrorLocationResolver implements SqlErrorLocationResolver {

	private static final String MARKER = "[*]";

	@Override
	public Optional<SqlErrorLocation> resolve(String submittedSql, SQLException exception) {
		if (submittedSql == null || exception == null) {
			return Optional.empty();
		}
		if (!exception.getClass().getName().startsWith("org.h2.")) {
			return Optional.empty();
		}
		String message = exception.getMessage();
		if (message == null || message.isEmpty()) {
			return Optional.empty();
		}

		int searchFrom = 0;
		while (true) {
			int quoteStart = message.indexOf('"', searchFrom);
			if (quoteStart < 0) {
				return Optional.empty();
			}
			DecodedSpan span = decodeQuotedSpan(message, quoteStart + 1);
			if (span == null) {
				// Malformed/unexpected escape sequence in this span - fail closed rather than guess.
				return Optional.empty();
			}
			int markerIndex = span.decoded.indexOf(MARKER);
			if (markerIndex >= 0) {
				int offset = Math.min(markerIndex, submittedSql.length());
				return Optional.of(SqlErrorLocation.fromOffset(submittedSql, offset));
			}
			searchFrom = span.endExclusive;
		}
	}

	/**
	 * Package-private (not private) so the extraction algorithm can be unit-tested directly against
	 * fabricated H2-style escaped strings, without needing a real syntax error for every case.
	 */
	static final class DecodedSpan {
		final String decoded;
		final int endExclusive;

		DecodedSpan(String decoded, int endExclusive) {
			this.decoded = decoded;
			this.endExclusive = endExclusive;
		}
	}

	/**
	 * Decodes a double-quoted span of an H2 message, reversing {@code DbException.quote()}, starting
	 * right after the opening {@code "} at {@code contentStart}. Returns {@code null} (fail closed) on
	 * any escape sequence that does not match the exact grammar {@code quote()} produces.
	 */
	static DecodedSpan decodeQuotedSpan(String message, int contentStart) {
		StringBuilder decoded = new StringBuilder();
		int n = message.length();
		int i = contentStart;
		while (i < n) {
			char c = message.charAt(i);
			if (c == '"') {
				if (i + 1 < n && message.charAt(i + 1) == '"') {
					decoded.append('"');
					i += 2;
					continue;
				}
				return new DecodedSpan(decoded.toString(), i + 1);
			}
			if (c == '\\') {
				if (i + 1 < n && message.charAt(i + 1) == '\\') {
					decoded.append('\\');
					i += 2;
					continue;
				}
				if (i + 1 < n && message.charAt(i + 1) == '+') {
					if (i + 8 <= n && isHex(message, i + 2, 6)) {
						decoded.appendCodePoint(Integer.parseInt(message.substring(i + 2, i + 8), 16));
						i += 8;
						continue;
					}
					return null;
				}
				if (i + 5 <= n && isHex(message, i + 1, 4)) {
					decoded.append((char) Integer.parseInt(message.substring(i + 1, i + 5), 16));
					i += 5;
					continue;
				}
				return null;
			}
			decoded.append(c);
			i++;
		}
		return null; // never closed
	}

	private static boolean isHex(String s, int from, int count) {
		for (int i = from; i < from + count; i++) {
			if (Character.digit(s.charAt(i), 16) < 0) {
				return false;
			}
		}
		return true;
	}
}
