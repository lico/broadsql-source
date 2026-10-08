package com.upandcoding.broadsql.controller.shell.repeat;

import java.util.Locale;
import java.util.Set;

import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibRun;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandDefault;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExternalFile;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandRepeat;

/**
 * GitHub #196: the query-only restriction of {@code REPEAT} (version 1 is a monitoring facility). It restricts which
 * statements may run; it is not a guarantee that the database is never changed (see the last sentence below). One rule, applied to every
 * statement a repeated target runs, whatever the target ({@code /}, a block, an {@code @script}, a {@code LIB RUN}
 * script, and the scripts those call): before the first iteration for what can be known in advance, and again by the
 * interpreter just before each statement executes ({@code CommandInterpreter}'s repeat guard).
 *
 * <p>A statement is allowed when it is:
 * <ul>
 * <li>a SQL query: a statement sent to the database (no BroadSQL command keyword) that BroadSQL's own classification
 * runs as a query ({@link CommandUtils#isNotUpdateStatement}, the same whitespace-aware test the SQL path uses),
 * except {@code CALL} and {@code SCRIPT}, which can change data or write files; and that does not contain, outside
 * quotes and comments, one of the words {@code INSERT}, {@code UPDATE}, {@code DELETE}, {@code MERGE}, {@code UPSERT}
 * or {@code INTO} (a {@code WITH ... DELETE}, a {@code SELECT ... INTO} or {@code FOR UPDATE} is refused);</li>
 * <li>a call of another script, {@code @script} or {@code LIB RUN script}: its own statements are checked when they run.</li>
 * </ul>
 * Everything else is refused: DML, DDL, {@code CALL}, and every other BroadSQL command ({@code CONNECT}, {@code DUMP},
 * {@code SET}, {@code LET}, {@code ON ERROR}...), a nested {@code REPEAT} with its own message. What a function called
 * by a query does is beyond this check.
 */
public final class RepeatSafety {

	private static final Set<String> SIDE_EFFECT_QUERY_PREFIXES = Set.of("call", "script");
	private static final Set<String> WRITE_WORDS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "INTO");

	public static final String RULE = "REPEAT repeats queries only (SELECT, WITH, SHOW, EXPLAIN), and calls of scripts (@ or LIB RUN) that contain only queries.";

	private RepeatSafety() {
	}

	/**
	 * @param command   the command the interpreter dispatches {@code statement} to; {@code null} or {@link CommandDefault}
	 *                  for SQL sent to the database
	 * @param statement the statement as it will execute
	 * @return {@code null} when the statement may be repeated, otherwise why not (a complete error message)
	 */
	public static String problem(Command command, String statement) {
		if (command instanceof CommandRepeat) {
			return "Nested REPEAT is not supported: a repeated target cannot run REPEAT itself ('" + shorten(statement) + "').";
		}
		if (command instanceof CommandExternalFile || command instanceof CommandLibRun) {
			return null;
		}
		if (command != null && !(command instanceof CommandDefault)) {
			String keyword = command.getKeywords() != null && command.getKeywords().length > 0 ? command.getKeywords()[0] : shorten(statement);
			return "'" + shorten(statement) + "' cannot be repeated: " + keyword + " is a BroadSQL command. " + RULE;
		}
		String writeWord = queryProblem(statement);
		return writeWord == null ? null : "'" + shorten(statement) + "' cannot be repeated: " + writeWord + " " + RULE;
	}

	/** {@code null} for a repeatable SQL query, otherwise the reason (a sentence). */
	static String queryProblem(String sql) {
		if (sql == null || sql.isBlank()) {
			return "it is empty.";
		}
		String trimmed = sql.trim();
		if (!CommandUtils.isNotUpdateStatement(trimmed)) {
			return "it is not a query.";
		}
		String lower = trimmed.toLowerCase(Locale.ROOT);
		for (String prefix : SIDE_EFFECT_QUERY_PREFIXES) {
			if (lower.startsWith(prefix) && Character.isWhitespace(lower.charAt(prefix.length()))) {
				return prefix.toUpperCase(Locale.ROOT) + " can change data.";
			}
		}
		String word = firstWriteWord(trimmed);
		return word == null ? null : "it contains " + word + ".";
	}

	/** The first of {@link #WRITE_WORDS} written as a word outside quotes and comments, or {@code null}. */
	static String firstWriteWord(String sql) {
		int n = sql.length();
		int i = 0;
		while (i < n) {
			char c = sql.charAt(i);
			char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
			if (c == '\'' || c == '"' || c == '`') {
				int close = i + 1;
				while (close < n) {
					if (sql.charAt(close) == c) {
						if (close + 1 < n && sql.charAt(close + 1) == c) {
							close += 2;
							continue;
						}
						break;
					}
					close++;
				}
				i = close + 1;
			} else if (c == '-' && next == '-') {
				int eol = sql.indexOf('\n', i);
				i = eol < 0 ? n : eol + 1;
			} else if (c == '/' && next == '*') {
				int close = sql.indexOf("*/", i + 2);
				i = close < 0 ? n : close + 2;
			} else if (Character.isLetter(c) || c == '_') {
				int start = i;
				while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
					i++;
				}
				String word = sql.substring(start, i).toUpperCase(Locale.ROOT);
				boolean qualified = start > 0 && sql.charAt(start - 1) == '.';
				if (!qualified && WRITE_WORDS.contains(word)) {
					return word;
				}
			} else {
				i++;
			}
		}
		return null;
	}

	static String shorten(String statement) {
		String single = CommandUtils.toSingleLine(statement == null ? "" : statement);
		return single.length() <= 80 ? single : single.substring(0, 77) + "...";
	}
}
