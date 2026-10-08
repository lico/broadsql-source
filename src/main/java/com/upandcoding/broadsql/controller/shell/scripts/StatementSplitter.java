package com.upandcoding.broadsql.controller.shell.scripts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SPRINT XT02B, section 15: splits a block of input text into individual statements on unquoted,
 * uncommented {@code ;} boundaries - the ONE statement model shared by {@link ScriptExecutor} (a script
 * file), {@code CommandInterpreter}'s interactive read loop (a single typed line may itself contain more
 * than one {@code ;}-terminated statement, e.g. {@code SELECT 1; SELECT 2;}), and (SPRINT 1909S, via
 * {@link #splitSpans(String)}) the BroadSQL Editor's formatter and validator, so all of them see exactly
 * the statements that execution sees. Named generically (not {@code SqlStatementSplitter}) because the
 * input contains BroadSQL commands as well as plain SQL - this class has no notion of what a
 * "statement" means beyond text between semicolons.
 *
 * <p>Implemented as one coherent single-pass scanner/state machine, not "strip comments, then split on
 * quote-aware semicolons" as two independent transformations - a naive comment-stripping pass run
 * first, before quote-awareness gets a chance to protect anything, can itself misfire on a quoted
 * string containing a comment marker (e.g. {@code SELECT 'abc--def';}). This scanner tracks, in one
 * pass: single-quoted strings, double-quoted strings (doubled-quote escaping for either, the standard
 * SQL convention - {@code ''}/{@code ""} inside a string of that quote type is a literal quote
 * character, not the end of the string), {@code --} line comments (anywhere on a line, to the end of
 * that line), block comments delimited by slash-star and star-slash (single- or multi-line, not
 * nested), and {@code ;} statement separators - a comment marker or semicolon encountered while inside
 * either quote type is always literal text, never special.
 *
 * <p>Not supported, deliberately (existing limitations, not changed by SPRINT 1909S): backslash-escaped
 * semicolons in files (only the interactive read loop honours {@code \;}), backtick quoting, dollar
 * quoting, {@code BEGIN..END} blocks, {@code DELIMITER}, and {@code /} or {@code GO} terminators.
 * Procedural SQL therefore splits at every inner semicolon.
 *
 * <p>GitHub #196, the one exception: a statement that <b>starts</b> with {@code REPEAT BEGIN} (the
 * {@code REPEAT} command's block form) keeps its inner {@code ;}-terminated statements; it ends at the
 * {@code ;} that follows the inner statement starting with {@code END} ({@code END EVERY 10s;}). Only a
 * statement whose first words are {@code REPEAT BEGIN} is affected: {@code BEGIN}/{@code END} anywhere else
 * (procedural SQL, a column named {@code END}) splits exactly as before. A {@code REPEAT BEGIN} block still
 * open at the end of the input is reported as unterminated ({@link Result#isRepeatBlockOpen()}), which is
 * also what tells the interactive read loop to keep reading lines.
 *
 * <p>Line breaks outside quoted text become a single space (so a command or statement spread over
 * several lines reaches the interpreter as one line, exactly as when typed); inside quoted text they are
 * kept.
 */
public final class StatementSplitter {

	private enum State {
		NORMAL, IN_SINGLE_QUOTE, IN_DOUBLE_QUOTE, IN_LINE_COMMENT, IN_BLOCK_COMMENT
	}

	/** GitHub #196: the first words of a {@code REPEAT} block statement (whitespace and comments already reduced to spaces). */
	private static final Pattern REPEAT_BLOCK_START = Pattern.compile("(?is)^\\s*REPEAT\\s+BEGIN(?=\\s|$)");
	/** GitHub #196: an inner segment of a {@code REPEAT} block that closes it. */
	private static final Pattern REPEAT_BLOCK_END = Pattern.compile("(?is)^\\s*END(?=\\s|$)");
	private static final String REPEAT_BLOCK = "REPEAT BEGIN block (END missing)";

	private StatementSplitter() {
	}

	/**
	 * One statement located in the original text.
	 * {@code start}/{@code end} delimit the raw region (comments included) between the previous
	 * {@code ;} and this statement's terminating {@code ;} (exclusive); {@code text} is the cleaned,
	 * trimmed statement exactly as {@link #split(String)} returns it.
	 */
	public static final class Span {
		private final int start;
		private final int end;
		private final String text;

		Span(int start, int end, String text) {
			this.start = start;
			this.end = end;
			this.text = text;
		}

		public int getStart() {
			return start;
		}

		public int getEnd() {
			return end;
		}

		public String getText() {
			return text;
		}
	}

	/** The outcome of {@link #splitSpans(String)}. */
	public static final class Result {
		private final List<Span> spans;
		private final String unterminated;
		private final int unterminatedLine;
		private final int unterminatedColumn;

		Result(List<Span> spans, String unterminated, int line, int column) {
			this.spans = spans;
			this.unterminated = unterminated;
			this.unterminatedLine = line;
			this.unterminatedColumn = column;
		}

		public List<Span> getSpans() {
			return spans;
		}

		/** {@code null} when the input ended in normal text (or inside a line comment, which simply ends); otherwise what was left open: "single quote", "double quote" or "block comment". */
		public String getUnterminated() {
			return unterminated;
		}

		public boolean isTerminated() {
			return unterminated == null;
		}

		/** 1-based line of the opening quote/comment marker that was never closed (0 when terminated). */
		public int getUnterminatedLine() {
			return unterminatedLine;
		}

		public int getUnterminatedColumn() {
			return unterminatedColumn;
		}

		public String describeUnterminated() {
			return "Unterminated " + unterminated + " starting at line " + unterminatedLine + ", column " + unterminatedColumn;
		}

		/** GitHub #196: whether the input ended inside a {@code REPEAT BEGIN} block, before the statement starting with {@code END}. */
		public boolean isRepeatBlockOpen() {
			return REPEAT_BLOCK.equals(unterminated);
		}
	}

	/**
	 * GitHub #196: whether {@code text} ends inside a {@code REPEAT BEGIN ... END} block. The interactive read loop
	 * asks this before executing a line that ends with {@code ;}: the inner statements of a block typed over several
	 * lines end with {@code ;} too, and must not execute the block before its {@code END}.
	 */
	public static boolean isRepeatBlockOpen(String text) {
		return splitSpans(text).isRepeatBlockOpen();
	}

	/**
	 * The {@code REPEAT} block depth after a {@code ;} ending {@code segment}: the text since the block's previous
	 * inner {@code ;}, or the whole statement so far at depth 0. A segment starting with {@code END} closes one
	 * level; one starting with {@code REPEAT BEGIN} opens one (and its own first statement is examined the same
	 * way, so {@code REPEAT BEGIN END EVERY 1s} opens and closes at once).
	 */
	private static int repeatDepthAfter(int depth, String segment) {
		if (depth > 0 && REPEAT_BLOCK_END.matcher(segment).lookingAt()) {
			return depth - 1;
		}
		Matcher start = REPEAT_BLOCK_START.matcher(segment);
		if (start.lookingAt()) {
			return repeatDepthAfter(depth + 1, segment.substring(start.end()));
		}
		return depth;
	}

	/**
	 * The index of the first character of {@code text} in {@code [from, to)} that is neither whitespace nor part
	 * of a {@code --} or block comment ({@code to} when there is none).
	 */
	static int firstCodeIndex(String text, int from, int to) {
		int i = from;
		int end = Math.min(to, text.length());
		while (i < end) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
			} else if (c == '-' && i + 1 < end && text.charAt(i + 1) == '-') {
				while (i < end && text.charAt(i) != '\n') {
					i++;
				}
			} else if (c == '/' && i + 1 < end && text.charAt(i + 1) == '*') {
				int close = text.indexOf("*/", i + 2);
				i = close < 0 ? end : close + 2;
			} else {
				break;
			}
		}
		return i;
	}

	/**
	 * @param input the full text to split - may span multiple lines and contain any mix of statements,
	 *              comments, and quoted strings
	 * @return each statement's text, trimmed, comments removed, in order - empty results (e.g. from a
	 *         trailing {@code ;}, or an input that is only comments/whitespace) are omitted
	 */
	public static List<String> split(String input) {
		List<String> statements = new ArrayList<>();
		for (Span span : splitSpans(input).getSpans()) {
			statements.add(span.getText());
		}
		return statements;
	}

	/**
	 * Same scan as {@link #split(String)}, but also reports where each statement sits in the original
	 * text and whether a quote or block comment was left open at the end of the input (the trailing text
	 * is still returned as a last statement, as {@link #split(String)} always has).
	 */
	public static Result splitSpans(String input) {
		List<Span> spans = new ArrayList<>();
		if (input == null) {
			return new Result(spans, null, 0, 0);
		}

		StringBuilder current = new StringBuilder();
		State state = State.NORMAL;
		int n = input.length();
		int i = 0;
		int segmentStart = 0;
		int openIndex = -1;
		// GitHub #196: REPEAT BEGIN ... END nesting depth of the statement being read, and where its current inner
		// segment starts in `current`
		int repeatDepth = 0;
		int innerStart = 0;
		while (i < n) {
			char c = input.charAt(i);
			char next = (i + 1 < n) ? input.charAt(i + 1) : '\0';

			switch (state) {
			case IN_SINGLE_QUOTE:
				if (c == '\'' && next == '\'') {
					current.append("''");
					i += 2;
					continue;
				}
				current.append(c);
				if (c == '\'') {
					state = State.NORMAL;
				}
				i++;
				continue;

			case IN_DOUBLE_QUOTE:
				if (c == '"' && next == '"') {
					current.append("\"\"");
					i += 2;
					continue;
				}
				current.append(c);
				if (c == '"') {
					state = State.NORMAL;
				}
				i++;
				continue;

			case IN_LINE_COMMENT:
				if (c == '\n' || c == '\r') {
					state = State.NORMAL;
					current.append(' '); // preserve word separation where the comment was
				}
				i++;
				continue;

			case IN_BLOCK_COMMENT:
				if (c == '*' && next == '/') {
					state = State.NORMAL;
					i += 2;
					current.append(' '); // preserve word separation where the comment was
					continue;
				}
				i++;
				continue;

			case NORMAL:
			default:
				if (c == '\'') {
					state = State.IN_SINGLE_QUOTE;
					openIndex = i;
					current.append(c);
					i++;
				} else if (c == '"') {
					state = State.IN_DOUBLE_QUOTE;
					openIndex = i;
					current.append(c);
					i++;
				} else if (c == '-' && next == '-') {
					state = State.IN_LINE_COMMENT;
					i += 2;
				} else if (c == '/' && next == '*') {
					state = State.IN_BLOCK_COMMENT;
					openIndex = i;
					i += 2;
				} else if (c == ';') {
					repeatDepth = repeatDepthAfter(repeatDepth, current.substring(innerStart));
					if (repeatDepth > 0) {
						// an inner statement of a REPEAT block: part of the REPEAT statement, not a separator
						current.append(c);
						innerStart = current.length();
						i++;
						continue;
					}
					addIfNotBlank(spans, current.toString(), segmentStart, i);
					current = new StringBuilder();
					innerStart = 0;
					i++;
					segmentStart = i;
				} else if (c == '\n' || c == '\r') {
					current.append(' ');
					i++;
				} else {
					current.append(c);
					i++;
				}
			}
		}
		addIfNotBlank(spans, current.toString(), segmentStart, n);

		String unterminated = null;
		int line = 0;
		int column = 0;
		if (state == State.IN_SINGLE_QUOTE || state == State.IN_DOUBLE_QUOTE || state == State.IN_BLOCK_COMMENT) {
			unterminated = state == State.IN_SINGLE_QUOTE ? "single quote" : state == State.IN_DOUBLE_QUOTE ? "double quote" : "block comment";
		} else if (repeatDepthAfter(repeatDepth, current.substring(innerStart)) > 0) {
			// GitHub #196: the input ends inside a REPEAT block (the last inner statement is not the END one);
			// a trailing "END EVERY 10s" without its final ';' (the interactive loop strips it) closes the block
			unterminated = REPEAT_BLOCK;
			openIndex = firstCodeIndex(input, segmentStart, n);
		}
		if (unterminated != null) {
			line = 1;
			column = 1;
			for (int k = 0; k < openIndex; k++) {
				if (input.charAt(k) == '\n') {
					line++;
					column = 1;
				} else {
					column++;
				}
			}
		}
		return new Result(spans, unterminated, line, column);
	}

	private static void addIfNotBlank(List<Span> spans, String text, int start, int end) {
		String trimmed = text.trim();
		if (!trimmed.isEmpty()) {
			spans.add(new Span(start, end, trimmed));
		}
	}
}
