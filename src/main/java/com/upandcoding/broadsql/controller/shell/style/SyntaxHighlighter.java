package com.upandcoding.broadsql.controller.shell.style;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import org.jline.reader.LineReader;
import org.jline.reader.impl.DefaultHighlighter;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * SPRINT 2409K: lightweight input highlighting, part of the theme subsystem: it only chooses a
 * {@link StyleRole} for each piece of the line and asks {@link TerminalStyleHolder}'s current
 * {@link TerminalStyle} for its attributes, so the theme alone decides the colors and styling that is off
 * yields plain text.
 *
 * <p>For readability only. It is not a validator and knows no grammar: a line starting with a registered
 * BroadSQL command keyword (from the live command registry, the same one command dispatch uses, longest
 * keyword first) shows that keyword as {@link StyleRole#COMMAND} and the rest as
 * {@link StyleRole#COMMAND_ARGUMENT}; any other line is SQL, where common SQL keywords are
 * {@link StyleRole#SQL_KEYWORD}, {@code '...'} strings and numbers {@link StyleRole#SQL_LITERAL}, and
 * {@code --}/{@code /* *}{@code /} comments {@link StyleRole#SQL_COMMENT}. Anything else, including
 * vendor-specific syntax, is left as typed. The highlighted text always has exactly the characters of the
 * buffer: highlighting never changes what is executed.
 *
 * <p>While JLine is searching history (Ctrl-R) or a region is active, JLine's own highlighting is used
 * unchanged. Any failure falls back to the plain buffer, so highlighting can never block input.
 */
public final class SyntaxHighlighter extends DefaultHighlighter {

	/** Common SQL keywords across vendors; not a grammar. */
	static final Set<String> SQL_KEYWORDS = Set.of(
			"SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN", "EXISTS", "AS", "ON", "USING",
			"JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL", "GROUP", "ORDER", "BY", "HAVING", "DISTINCT",
			"UNION", "ALL", "INTERSECT", "EXCEPT", "MINUS", "LIMIT", "OFFSET", "FETCH", "FIRST", "NEXT", "ROWS", "ROW", "ONLY",
			"TOP", "ASC", "DESC", "CASE", "WHEN", "THEN", "ELSE", "END", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
			"MERGE", "MATCHED", "CREATE", "ALTER", "DROP", "TRUNCATE", "TABLE", "VIEW", "INDEX", "UNIQUE", "PRIMARY", "FOREIGN",
			"KEY", "REFERENCES", "CONSTRAINT", "DEFAULT", "CHECK", "WITH", "RECURSIVE", "GRANT", "REVOKE", "COMMIT", "ROLLBACK",
			"SAVEPOINT", "BEGIN", "TRUE", "FALSE", "CAST", "OVER", "PARTITION", "SCHEMA", "SEQUENCE", "TRIGGER", "PROCEDURE",
			"FUNCTION", "RETURNS", "DECLARE", "IF", "EXPLAIN");

	private final Supplier<Collection<String>> commandKeywords;

	/** @param commandKeywords every registered command keyword and alias, read at each call (never cached) */
	public SyntaxHighlighter(Supplier<Collection<String>> commandKeywords) {
		this.commandKeywords = commandKeywords;
	}

	@Override
	public AttributedString highlight(LineReader reader, String buffer) {
		TerminalStyle style = TerminalStyleHolder.get();
		try {
			if (!style.highlightsInput() || buffer == null || buffer.isEmpty() || isSearching(reader)) {
				return super.highlight(reader, buffer);
			}
			return highlight(style, buffer);
		} catch (RuntimeException e) {
			return new AttributedString(buffer == null ? "" : buffer);
		}
	}

	private static boolean isSearching(LineReader reader) {
		if (reader == null) {
			return false;
		}
		String searchTerm = reader.getSearchTerm();
		return (searchTerm != null && !searchTerm.isEmpty()) || reader.getRegionActive() != LineReader.RegionType.NONE;
	}

	/** Highlights {@code buffer} with {@code style}; the result's text is always exactly {@code buffer}. */
	public AttributedString highlight(TerminalStyle style, String buffer) {
		AttributedStringBuilder sb = new AttributedStringBuilder(buffer.length());
		int commandEnd = commandKeywordLength(buffer);
		if (commandEnd > 0) {
			int leading = leadingBlanks(buffer);
			sb.append(buffer.substring(0, leading));
			sb.styled(style.attributes(StyleRole.COMMAND), buffer.substring(leading, commandEnd));
			appendArguments(sb, style, buffer.substring(commandEnd));
		} else {
			appendSql(sb, style, buffer);
		}
		return sb.toAttributedString();
	}

	/** The end index of the longest registered command keyword the buffer starts with (whole words only), or 0. */
	int commandKeywordLength(String buffer) {
		Collection<String> keywords = commandKeywords == null ? null : commandKeywords.get();
		if (keywords == null) {
			return 0;
		}
		int leading = leadingBlanks(buffer);
		String line = buffer.substring(leading);
		int best = 0;
		for (String keyword : keywords) {
			if (keyword == null || keyword.isBlank()) {
				continue;
			}
			String k = keyword.trim();
			boolean glued = "@".equals(k); // @script: the one keyword glued to its argument
			if (line.length() < k.length() || !line.regionMatches(true, 0, k, 0, k.length())) {
				continue;
			}
			boolean wordBoundary = glued || line.length() == k.length() || !isWordChar(line.charAt(k.length()));
			if (wordBoundary && k.length() > best) {
				best = k.length();
			}
		}
		return best == 0 ? 0 : leading + best;
	}

	private static void appendArguments(AttributedStringBuilder sb, TerminalStyle style, String text) {
		AttributedStyle argument = style.attributes(StyleRole.COMMAND_ARGUMENT);
		AttributedStyle literal = style.attributes(StyleRole.SQL_LITERAL);
		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c == '\'') {
				int end = quotedEnd(text, i);
				sb.styled(literal, text.substring(i, end));
				i = end;
			} else {
				int next = text.indexOf('\'', i);
				int end = next < 0 ? text.length() : next;
				sb.styled(argument, text.substring(i, end));
				i = end;
			}
		}
	}

	private static void appendSql(AttributedStringBuilder sb, TerminalStyle style, String text) {
		int i = 0;
		int n = text.length();
		while (i < n) {
			char c = text.charAt(i);
			char next = i + 1 < n ? text.charAt(i + 1) : '\0';
			if (c == '\'') {
				int end = quotedEnd(text, i);
				sb.styled(style.attributes(StyleRole.SQL_LITERAL), text.substring(i, end));
				i = end;
			} else if (c == '"') {
				int end = text.indexOf('"', i + 1);
				end = end < 0 ? n : end + 1;
				sb.append(text.substring(i, end));
				i = end;
			} else if (c == '-' && next == '-') {
				int end = lineEnd(text, i);
				sb.styled(style.attributes(StyleRole.SQL_COMMENT), text.substring(i, end));
				i = end;
			} else if (c == '/' && next == '*') {
				int end = text.indexOf("*/", i + 2);
				end = end < 0 ? n : end + 2;
				sb.styled(style.attributes(StyleRole.SQL_COMMENT), text.substring(i, end));
				i = end;
			} else if (Character.isLetter(c) || c == '_') {
				int end = i;
				while (end < n && isWordChar(text.charAt(end))) {
					end++;
				}
				String word = text.substring(i, end);
				boolean qualified = i > 0 && text.charAt(i - 1) == '.';
				if (!qualified && SQL_KEYWORDS.contains(word.toUpperCase(Locale.ROOT))) {
					sb.styled(style.attributes(StyleRole.SQL_KEYWORD), word);
				} else {
					sb.append(word);
				}
				i = end;
			} else if (Character.isDigit(c) && (i == 0 || !isWordChar(text.charAt(i - 1)))) {
				int end = i;
				while (end < n && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
					end++;
				}
				sb.styled(style.attributes(StyleRole.SQL_LITERAL), text.substring(i, end));
				i = end;
			} else {
				sb.append(c);
				i++;
			}
		}
	}

	/** One past the closing quote of the {@code '...'} literal opening at {@code start} ({@code ''} escapes), or the end of the text. */
	private static int quotedEnd(String text, int start) {
		int i = start + 1;
		while (i < text.length()) {
			if (text.charAt(i) == '\'') {
				if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
					i += 2;
					continue;
				}
				return i + 1;
			}
			i++;
		}
		return text.length();
	}

	private static int lineEnd(String text, int from) {
		int i = from;
		while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
			i++;
		}
		return i;
	}

	private static int leadingBlanks(String text) {
		int i = 0;
		while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
			i++;
		}
		return i;
	}

	private static boolean isWordChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
	}

	/** The keywords of a command registry, without the raw-SQL fallback entry (which is not something a user types). */
	public static Supplier<Collection<String>> keywordsOf(java.util.Map<String, ?> registry, String excludedKey) {
		return () -> {
			if (registry == null) {
				return List.of();
			}
			java.util.List<String> keys = new java.util.ArrayList<>();
			for (String key : registry.keySet()) {
				if (key != null && !key.equals(excludedKey)) {
					keys.add(key);
				}
			}
			return keys;
		};
	}
}
