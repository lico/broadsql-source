package com.upandcoding.broadsql.controller.shell.repeat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptArguments;
import com.upandcoding.broadsql.controller.shell.scripts.StatementSplitter;

/**
 * GitHub #196: one {@code REPEAT} command, parsed. The grammar has one fixed order, whatever the target:
 *
 * <pre>
 * REPEAT [&lt;target&gt;] EVERY &lt;interval&gt; [FOR &lt;duration&gt; | COUNT &lt;iterations&gt;] [TO &lt;file&gt; [AS CSV|JSON|TEXT]]
 * </pre>
 *
 * where {@code <target>} is nothing or {@code /} (the last query, the one {@code /} runs), {@code BEGIN <statements> END}
 * (a block of {@code ;}-terminated statements, kept together by {@link StatementSplitter}), {@code @<script> [arguments]}
 * or {@code LIB RUN <script> [arguments]}. A script reference is never interpreted here: it is executed as the
 * {@code @}/{@code LIB RUN} statement it is written as, so the normal resolution applies.
 *
 * <p>A script reference may itself be the word {@code every} ({@code REPEAT LIB RUN every EVERY 10s}): each
 * {@code EVERY} word is tried as the start of the clauses, from left to right, and the first one after which the
 * whole command parses is used.
 */
public final class RepeatStatement {

	/** What one iteration runs. */
	public enum TargetKind {
		/** {@code REPEAT EVERY ...} or {@code REPEAT / EVERY ...}: the last query, the one {@code /} runs. */
		LAST_QUERY,
		/** {@code REPEAT BEGIN ... END EVERY ...}. */
		BLOCK,
		/** {@code REPEAT @script EVERY ...}. */
		SCRIPT,
		/** {@code REPEAT LIB RUN script EVERY ...}. */
		LIBRARY_SCRIPT
	}

	/** The monitoring output formats. */
	public enum OutputFormat {
		CSV("csv"), JSON("json"), TEXT("txt");

		private final String extension;

		OutputFormat(String extension) {
			this.extension = extension;
		}

		public String extension() {
			return extension;
		}
	}

	public static final String USAGE = "REPEAT [<target>] EVERY <interval> [FOR <duration> | COUNT <iterations>] [TO <file> [AS CSV|JSON|TEXT]]";

	private static final String TARGETS = "The target is nothing or / (the last query), BEGIN <queries> END, @<script> or LIB RUN <script>.";
	private static final Pattern BEGIN = Pattern.compile("(?is)^BEGIN(?=\\s|$)");
	private static final Pattern END = Pattern.compile("(?is)^END(?=\\s|$)");
	private static final String LIB_RUN = "LIB RUN";

	private TargetKind targetKind;
	private String targetStatement;
	private List<String> blockStatements = List.of();
	private String scriptReference;
	private RepeatDuration every;
	private RepeatDuration forDuration;
	private Integer count;
	private String outputFile;
	private OutputFormat outputFormat;

	private RepeatStatement() {
	}

	/** @param text what follows the {@code REPEAT} keyword */
	public static RepeatStatement parse(String text) throws BroadSQLException {
		String rest = text == null ? "" : text.trim();
		if (rest.isEmpty()) {
			throw new BroadSQLException("REPEAT needs EVERY <interval>. Usage: " + USAGE);
		}
		Matcher begin = BEGIN.matcher(rest);
		if (begin.lookingAt()) {
			return parseBlock(rest.substring(begin.end()));
		}

		List<Token> tokens = tokenize(rest);
		List<Token> everyCandidates = new ArrayList<>();
		for (Token token : tokens) {
			if (!token.quoted && "EVERY".equalsIgnoreCase(token.text)) {
				everyCandidates.add(token);
			}
		}
		if (everyCandidates.isEmpty()) {
			String first = tokens.isEmpty() ? "" : tokens.get(0).text.toUpperCase(Locale.ROOT);
			if (first.equals("FOR") || first.equals("COUNT") || first.equals("TO")) {
				throw new BroadSQLException("EVERY <interval> is mandatory and comes first, right after the target. Usage: " + USAGE);
			}
			throw new BroadSQLException("EVERY <interval> is mandatory. Usage: " + USAGE);
		}
		BroadSQLException firstError = null;
		for (Token every : everyCandidates) {
			try {
				RepeatStatement statement = new RepeatStatement();
				statement.parseTarget(rest.substring(0, every.start).trim());
				statement.parseClauses(tokens.subList(tokens.indexOf(every), tokens.size()));
				return statement;
			} catch (BroadSQLException e) {
				if (firstError == null) {
					firstError = e;
				}
			}
		}
		throw firstError;
	}

	private static RepeatStatement parseBlock(String afterBegin) throws BroadSQLException {
		StatementSplitter.Result split = StatementSplitter.splitSpans(afterBegin);
		if (!split.isTerminated()) {
			throw new BroadSQLException("The REPEAT block cannot be read: " + split.describeUnterminated() + " (inside BEGIN ... END).");
		}
		List<StatementSplitter.Span> spans = split.getSpans();
		if (spans.isEmpty() || !END.matcher(spans.get(spans.size() - 1).getText()).lookingAt()) {
			throw new BroadSQLException("The REPEAT block has no END. Write REPEAT BEGIN <query>; <query>; END EVERY <interval>; "
					+ "each statement inside the block ends with ;");
		}
		List<String> statements = new ArrayList<>();
		for (int i = 0; i < spans.size() - 1; i++) {
			String statement = spans.get(i).getText();
			if (END.matcher(statement).lookingAt()) {
				throw new BroadSQLException("Unexpected END inside the REPEAT block: a block has exactly one END, followed by EVERY <interval>.");
			}
			statements.add(statement);
		}
		if (statements.isEmpty()) {
			throw new BroadSQLException("The REPEAT block is empty: put at least one query between BEGIN and END.");
		}
		String last = spans.get(spans.size() - 1).getText();
		Matcher end = END.matcher(last);
		end.lookingAt();
		String clauses = last.substring(end.end()).trim();
		List<Token> tokens = tokenize(clauses);
		if (tokens.isEmpty() || tokens.get(0).quoted || !"EVERY".equalsIgnoreCase(tokens.get(0).text)) {
			throw new BroadSQLException("END must be followed by EVERY <interval>. Usage: " + USAGE);
		}
		RepeatStatement statement = new RepeatStatement();
		statement.targetKind = TargetKind.BLOCK;
		statement.blockStatements = Collections.unmodifiableList(statements);
		statement.parseClauses(tokens);
		return statement;
	}

	private void parseTarget(String target) throws BroadSQLException {
		if (target.isEmpty() || target.equals("/")) {
			targetKind = TargetKind.LAST_QUERY;
			return;
		}
		if (target.startsWith("@")) {
			String reference = ScriptArguments.splitReference(target.substring(1))[0];
			if (reference == null || reference.isBlank()) {
				throw new BroadSQLException("REPEAT @ needs a script name or path, for example REPEAT @monitor.sql EVERY 10s.");
			}
			targetKind = TargetKind.SCRIPT;
			targetStatement = target;
			scriptReference = reference;
			return;
		}
		int libRunEnd = CommandUtils.keywordMatchEnd(target, LIB_RUN);
		if (libRunEnd >= 0) {
			String reference = ScriptArguments.splitReference(target.substring(libRunEnd).trim())[0];
			if (reference == null || reference.isBlank()) {
				throw new BroadSQLException("REPEAT LIB RUN needs a Scripts Library script, for example REPEAT LIB RUN monitor.sql EVERY 10s.");
			}
			targetKind = TargetKind.LIBRARY_SCRIPT;
			targetStatement = target;
			scriptReference = reference;
			return;
		}
		if (target.startsWith("/")) {
			throw new BroadSQLException("REPEAT / takes nothing else before EVERY: it repeats the last query on the current connection. Usage: " + USAGE);
		}
		throw new BroadSQLException("REPEAT cannot repeat '" + CommandUtils.toSingleLine(target) + "'. " + TARGETS + " Usage: " + USAGE);
	}

	private void parseClauses(List<Token> tokens) throws BroadSQLException {
		int i = 0;
		// EVERY <interval>: always first
		i++;
		if (i >= tokens.size()) {
			throw new BroadSQLException("EVERY needs an interval. " + RepeatDuration.ACCEPTED);
		}
		every = RepeatDuration.parse(tokens.get(i++).text, "EVERY");

		while (i < tokens.size()) {
			Token token = tokens.get(i);
			String word = token.quoted ? "" : token.text.toUpperCase(Locale.ROOT);
			if (word.equals("FOR") || word.equals("COUNT")) {
				if (outputFile != null) {
					throw new BroadSQLException(word + " must come before TO. Usage: " + USAGE);
				}
				if ((word.equals("FOR") && forDuration != null) || (word.equals("COUNT") && count != null)) {
					throw new BroadSQLException(word + " can be given only once. Usage: " + USAGE);
				}
				if (forDuration != null || count != null) {
					throw new BroadSQLException("FOR and COUNT cannot be combined: give at most one of them. Usage: " + USAGE);
				}
				if (i + 1 >= tokens.size()) {
					throw new BroadSQLException(word.equals("FOR") ? "FOR needs a duration. " + RepeatDuration.ACCEPTED
							: "COUNT needs a number of iterations, a positive whole number.");
				}
				String value = tokens.get(i + 1).text;
				if (word.equals("FOR")) {
					forDuration = RepeatDuration.parse(value, "FOR");
				} else {
					count = parseCount(value);
				}
				i += 2;
			} else if (word.equals("TO")) {
				if (outputFile != null) {
					throw new BroadSQLException("TO can be given only once. Usage: " + USAGE);
				}
				if (i + 1 >= tokens.size()) {
					throw new BroadSQLException("TO needs a file name, for example TO monitor.csv.");
				}
				outputFile = tokens.get(i + 1).text;
				if (outputFile.isBlank()) {
					throw new BroadSQLException("TO needs a file name, for example TO monitor.csv.");
				}
				i += 2;
			} else if (word.equals("AS")) {
				if (outputFile == null) {
					throw new BroadSQLException("AS needs TO <file> first: TO <file> AS CSV|JSON|TEXT.");
				}
				if (outputFormat != null) {
					throw new BroadSQLException("AS can be given only once. Usage: " + USAGE);
				}
				if (i + 1 >= tokens.size()) {
					throw new BroadSQLException("AS needs a format: CSV, JSON or TEXT.");
				}
				outputFormat = parseFormat(tokens.get(i + 1).text);
				i += 2;
			} else if (word.equals("EVERY")) {
				throw new BroadSQLException("EVERY can be given only once. Usage: " + USAGE);
			} else {
				throw new BroadSQLException("Unexpected '" + token.text + "' in REPEAT. Usage: " + USAGE);
			}
		}
		if (outputFile != null && outputFormat == null) {
			outputFormat = formatFromExtension(outputFile);
		}
	}

	private static int parseCount(String value) throws BroadSQLException {
		if (!value.matches("\\d+")) {
			throw new BroadSQLException("Invalid COUNT '" + value + "': it must be a positive whole number (1 or more).");
		}
		long parsed;
		try {
			parsed = value.length() > 10 ? Long.MAX_VALUE : Long.parseLong(value);
		} catch (NumberFormatException e) {
			parsed = Long.MAX_VALUE;
		}
		if (parsed < 1) {
			throw new BroadSQLException("Invalid COUNT '" + value + "': it must be a positive whole number (1 or more).");
		}
		if (parsed > Integer.MAX_VALUE) {
			throw new BroadSQLException("Invalid COUNT '" + value + "': the number is too large.");
		}
		return (int) parsed;
	}

	private static OutputFormat parseFormat(String value) throws BroadSQLException {
		for (OutputFormat format : OutputFormat.values()) {
			if (format.name().equalsIgnoreCase(value)) {
				return format;
			}
		}
		throw new BroadSQLException("Unknown REPEAT output format '" + value + "': use CSV, JSON or TEXT.");
	}

	private static OutputFormat formatFromExtension(String file) throws BroadSQLException {
		String lower = file.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".csv")) {
			return OutputFormat.CSV;
		}
		if (lower.endsWith(".json")) {
			return OutputFormat.JSON;
		}
		if (lower.endsWith(".txt")) {
			return OutputFormat.TEXT;
		}
		throw new BroadSQLException("REPEAT cannot tell the format of '" + file + "' from its extension: add AS CSV, AS JSON or AS TEXT.");
	}

	// ---- tokens ----

	private static final class Token {
		final String text;
		final int start;
		final boolean quoted;

		Token(String text, int start, boolean quoted) {
			this.text = text;
			this.start = start;
			this.quoted = quoted;
		}
	}

	/** Whitespace-separated words; a word starting with {@code "} or {@code '} runs to the matching quote, which is removed. */
	private static List<Token> tokenize(String text) {
		List<Token> tokens = new ArrayList<>();
		int i = 0;
		int n = text.length();
		while (i < n) {
			if (Character.isWhitespace(text.charAt(i))) {
				i++;
				continue;
			}
			int start = i;
			char c = text.charAt(i);
			if (c == '"' || c == '\'') {
				int close = text.indexOf(c, i + 1);
				int end = close < 0 ? n : close;
				tokens.add(new Token(text.substring(i + 1, end), start, true));
				i = close < 0 ? n : close + 1;
				continue;
			}
			while (i < n && !Character.isWhitespace(text.charAt(i))) {
				char q = text.charAt(i);
				if (q == '"' || q == '\'') {
					int close = text.indexOf(q, i + 1);
					i = close < 0 ? n : close + 1;
				} else {
					i++;
				}
			}
			tokens.add(new Token(text.substring(start, i), start, false));
		}
		return tokens;
	}

	// ---- accessors ----

	public TargetKind getTargetKind() {
		return targetKind;
	}

	/** {@code @...} or {@code LIB RUN ...} exactly as written (arguments included); {@code null} for the other targets. */
	public String getTargetStatement() {
		return targetStatement;
	}

	/** The script reference of an {@code @} or {@code LIB RUN} target; {@code null} for the other targets. */
	public String getScriptReference() {
		return scriptReference;
	}

	/** The statements of a {@code BEGIN ... END} block, in order; empty for the other targets. */
	public List<String> getBlockStatements() {
		return blockStatements;
	}

	public RepeatDuration getEvery() {
		return every;
	}

	/** {@code null} when there is no {@code FOR}. */
	public RepeatDuration getForDuration() {
		return forDuration;
	}

	/** {@code null} when there is no {@code COUNT}. */
	public Integer getCount() {
		return count;
	}

	/** The {@code TO} file as written; {@code null} when there is none. */
	public String getOutputFile() {
		return outputFile;
	}

	/** The {@code AS} format, or the one the file extension gives; {@code null} without {@code TO}. */
	public OutputFormat getOutputFormat() {
		return outputFormat;
	}

	/** Whether the command ends by itself ({@code FOR} or {@code COUNT}). */
	public boolean isBounded() {
		return forDuration != null || count != null;
	}
}
