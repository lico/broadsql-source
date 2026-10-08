package com.upandcoding.broadsql.controller.shell.scripts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SPRINT 0110A: finds the {@code ${name}} variable references and the raw {@code ?} characters of a SQL text,
 * only in normal statement text (spec sections 6.4, 6.7, 9.3 and 9.6): never inside a single-quoted string
 * ({@code ''} escapes), a double-quoted identifier ({@code ""} escapes), a {@code --} line comment, a
 * {@code /* *}{@code /} block comment, or a dollar-quoted string ({@code $$...$$}, {@code $tag$...$tag$},
 * PostgreSQL and H2 syntax; the opening {@code $} is not preceded by a letter, digit, {@code _} or {@code $}).
 * Same single-pass lexical model as {@code StatementSplitter} and {@code ListSourceMacroScanner}.
 *
 * <p>In normal text, {@code ${} always begins a reference: when it is not followed by a valid name and
 * {@code }}, the reference is malformed.
 */
public final class VariableReferenceScanner {

	/** A {@code ${...}} occurrence: a valid reference ({@link #name()} non-null) or a malformed one. */
	public record Occurrence(int start, int end, String name, String text) {

		public boolean isMalformed() {
			return name == null;
		}
	}

	/** Everything found in one text. */
	public static final class Scan {
		private final List<Occurrence> occurrences;
		private final List<Integer> rawQuestionMarks;

		Scan(List<Occurrence> occurrences, List<Integer> rawQuestionMarks) {
			this.occurrences = Collections.unmodifiableList(occurrences);
			this.rawQuestionMarks = Collections.unmodifiableList(rawQuestionMarks);
		}

		/** Valid and malformed references, left to right. */
		public List<Occurrence> getOccurrences() {
			return occurrences;
		}

		/** Positions of the raw {@code ?} characters of normal text, left to right. */
		public List<Integer> getRawQuestionMarks() {
			return rawQuestionMarks;
		}

		public boolean hasReferences() {
			return !occurrences.isEmpty();
		}
	}

	private enum State {
		NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, LINE_COMMENT, BLOCK_COMMENT, DOLLAR_QUOTE
	}

	private VariableReferenceScanner() {
	}

	public static Scan scan(String text) {
		List<Occurrence> occurrences = new ArrayList<>();
		List<Integer> questionMarks = new ArrayList<>();
		if (text == null) {
			return new Scan(occurrences, questionMarks);
		}
		State state = State.NORMAL;
		String dollarTag = null;
		int n = text.length();
		int i = 0;
		while (i < n) {
			char c = text.charAt(i);
			char next = i + 1 < n ? text.charAt(i + 1) : '\0';
			switch (state) {
				case SINGLE_QUOTE:
					if (c == '\'') {
						if (next == '\'') {
							i += 2;
							continue;
						}
						state = State.NORMAL;
					}
					i++;
					continue;
				case DOUBLE_QUOTE:
					if (c == '"') {
						if (next == '"') {
							i += 2;
							continue;
						}
						state = State.NORMAL;
					}
					i++;
					continue;
				case LINE_COMMENT:
					if (c == '\n' || c == '\r') {
						state = State.NORMAL;
					}
					i++;
					continue;
				case BLOCK_COMMENT:
					if (c == '*' && next == '/') {
						state = State.NORMAL;
						i += 2;
						continue;
					}
					i++;
					continue;
				case DOLLAR_QUOTE:
					if (text.startsWith(dollarTag, i)) {
						i += dollarTag.length();
						state = State.NORMAL;
						dollarTag = null;
						continue;
					}
					i++;
					continue;
				case NORMAL:
				default:
					if (c == '\'') {
						state = State.SINGLE_QUOTE;
						i++;
					} else if (c == '"') {
						state = State.DOUBLE_QUOTE;
						i++;
					} else if (c == '-' && next == '-') {
						state = State.LINE_COMMENT;
						i += 2;
					} else if (c == '/' && next == '*') {
						state = State.BLOCK_COMMENT;
						i += 2;
					} else if (c == '$' && next == '{') {
						Occurrence occurrence = referenceAt(text, i);
						occurrences.add(occurrence);
						i = occurrence.end();
					} else if (c == '$' && (dollarTag = dollarQuoteOpenerAt(text, i)) != null) {
						state = State.DOLLAR_QUOTE;
						i += dollarTag.length();
					} else {
						if (c == '?') {
							questionMarks.add(i);
						}
						i++;
					}
			}
		}
		return new Scan(occurrences, questionMarks);
	}

	/**
	 * The reference that starts at {@code start} ({@code text} has {@code ${} there): valid when a name of at
	 * most {@value VariableNames#MAX_LENGTH} characters that is not reserved is followed by {@code }}, malformed
	 * otherwise. A malformed occurrence spans up to its closing {@code }} when one follows before a line break,
	 * otherwise up to the next whitespace.
	 */
	public static Occurrence referenceAt(String text, int start) {
		int i = start + 2;
		int n = text.length();
		int nameEnd = i;
		while (nameEnd < n && VariableNames.isNameChar(text.charAt(nameEnd))) {
			nameEnd++;
		}
		String name = text.substring(i, nameEnd);
		if (nameEnd < n && text.charAt(nameEnd) == '}' && VariableNames.isValid(name)) {
			return new Occurrence(start, nameEnd + 1, name, text.substring(start, nameEnd + 1));
		}
		int end = malformedEnd(text, start);
		return new Occurrence(start, end, null, text.substring(start, end));
	}

	private static int malformedEnd(String text, int start) {
		int n = text.length();
		for (int k = start + 2; k < n; k++) {
			char c = text.charAt(k);
			if (c == '}') {
				return k + 1;
			}
			if (c == '\n' || c == '\r') {
				break;
			}
		}
		int k = start + 2;
		while (k < n && !Character.isWhitespace(text.charAt(k))) {
			k++;
		}
		return k;
	}

	/**
	 * The dollar-quote opener ({@code $$} or {@code $tag$}) starting at {@code i}, or {@code null}: the opening
	 * {@code $} must not be preceded by a letter, digit, {@code _} or {@code $} (so {@code V$SESSION} and
	 * {@code $1} are ordinary text), and the tag is made of letters, digits and {@code _}.
	 */
	static String dollarQuoteOpenerAt(String text, int i) {
		if (i > 0) {
			char before = text.charAt(i - 1);
			if (Character.isLetterOrDigit(before) || before == '_' || before == '$') {
				return null;
			}
		}
		int k = i + 1;
		while (k < text.length() && (Character.isLetterOrDigit(text.charAt(k)) || text.charAt(k) == '_')) {
			k++;
		}
		if (k < text.length() && text.charAt(k) == '$') {
			return text.substring(i, k + 1);
		}
		return null;
	}
}
