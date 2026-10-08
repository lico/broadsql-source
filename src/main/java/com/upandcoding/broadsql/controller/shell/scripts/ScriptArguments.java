package com.upandcoding.broadsql.controller.shell.scripts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * SPRINT 0110A: named script arguments (spec section 17), the replacement of the removed positional
 * {@code %1..%9}. A call is {@code <script> name=value ...}; a value is a {@code LET} literal or one standalone
 * {@code ${other}} reference (never a query, never an expression). Arguments are separated by whitespace,
 * whitespace around {@code =} is optional, there is no count limit.
 *
 * <p>{@link #parse} checks the syntax of the whole argument list; {@link #evaluate} computes every value from
 * the session <b>before</b> anything is assigned (spec 17.3 step 1), so {@code a=${b} b=1} gives {@code a} the
 * value {@code b} had before the call and a failing argument assigns nothing.
 */
public final class ScriptArguments {

	/** The removed-positional-arguments explanation, part of every related error (spec 17.4). */
	public static final String POSITIONAL_REMOVED = "positional script arguments (%1..%9) were removed: pass arguments as name=value and read them with ${name}";

	/** One {@code name=value} argument as written. */
	public record Argument(String name, String valueText) {
	}

	private final List<Argument> arguments;

	private ScriptArguments(List<Argument> arguments) {
		this.arguments = Collections.unmodifiableList(arguments);
	}

	public static ScriptArguments none() {
		return new ScriptArguments(List.of());
	}

	public List<Argument> getArguments() {
		return arguments;
	}

	public boolean isEmpty() {
		return arguments.isEmpty();
	}

	/** The upper-cased names given, for the {@code @params} check. */
	public Set<String> nameKeys() {
		Set<String> keys = new HashSet<>();
		for (Argument argument : arguments) {
			keys.add(VariableNames.key(argument.name()));
		}
		return keys;
	}

	/**
	 * Splits what follows {@code @} or {@code LIB RUN} into the script reference (one word, or a double-quoted
	 * name containing spaces, quotes removed) and the argument text after it.
	 *
	 * @return {@code [reference, arguments]}; the reference is empty when nothing was given
	 */
	public static String[] splitReference(String text) throws BroadSQLException {
		String t = text == null ? "" : text.strip();
		if (t.isEmpty()) {
			return new String[] { "", "" };
		}
		if (t.charAt(0) == '"') {
			int closing = t.indexOf('"', 1);
			if (closing < 0) {
				throw new BroadSQLException("Unbalanced quotes in the script reference: " + t);
			}
			return new String[] { t.substring(1, closing), t.substring(closing + 1).strip() };
		}
		int end = 0;
		while (end < t.length() && !Character.isWhitespace(t.charAt(end))) {
			end++;
		}
		return new String[] { t.substring(0, end), t.substring(end).strip() };
	}

	/**
	 * Parses the argument list {@code text} (what follows the script reference).
	 *
	 * @throws BroadSQLException positional argument, invalid name, missing or invalid value, query value,
	 *                           duplicate name
	 */
	public static ScriptArguments parse(String text) throws BroadSQLException {
		return new ScriptArguments(scan(text, false, null).arguments);
	}

	/**
	 * For {@code DUMP}/{@code PULL} script sources (spec 17.1): the argument region of {@code text}, which ends
	 * at the first whitespace-separated {@code TO} or {@code AS} keyword that is not itself an argument name.
	 * Every other token that is not {@code name=value} is a syntax error of {@code commandName}.
	 *
	 * @return {@code [arguments text, remainder starting at TO/AS (or empty)]}
	 */
	public static String[] splitBeforeToOrAs(String text, String commandName) throws BroadSQLException {
		Scanned scanned = scan(text, true, commandName);
		return new String[] { text.substring(0, scanned.stopIndex).strip(), text.substring(scanned.stopIndex).strip() };
	}

	private record Scanned(List<Argument> arguments, int stopIndex) {
	}

	private static Scanned scan(String text, boolean stopAtToOrAs, String commandName) throws BroadSQLException {
		List<Argument> result = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		String s = text == null ? "" : text;
		int n = s.length();
		int pos = 0;
		while (true) {
			pos = skipWhitespace(s, pos);
			if (pos >= n) {
				return new Scanned(result, n);
			}
			int tokenStart = pos;
			char first = s.charAt(pos);
			int nameEnd = pos;
			if (first != '\'' && first != '"') {
				while (nameEnd < n && !Character.isWhitespace(s.charAt(nameEnd)) && s.charAt(nameEnd) != '=') {
					nameEnd++;
				}
			}
			String name = s.substring(pos, nameEnd);
			int afterName = skipWhitespace(s, nameEnd);
			boolean isArgument = !name.isEmpty() && afterName < n && s.charAt(afterName) == '=';
			if (!isArgument) {
				String token = bareToken(s, tokenStart);
				if (stopAtToOrAs && ("TO".equalsIgnoreCase(token) || "AS".equalsIgnoreCase(token))) {
					return new Scanned(result, tokenStart);
				}
				if (stopAtToOrAs) {
					throw new BroadSQLException(commandName + " syntax error: unexpected '" + token + "' after the script reference; script arguments are name=value, "
							+ "followed by the optional TO and AS clauses (" + POSITIONAL_REMOVED + ")");
				}
				if (name.isEmpty() && first == '=') {
					throw new BroadSQLException("Missing argument name before '=': arguments are name=value");
				}
				if (VariableNames.isValid(token)) {
					throw new BroadSQLException("Missing value for argument " + token + ": arguments are name=value (" + POSITIONAL_REMOVED + ")");
				}
				throw new BroadSQLException("Invalid script argument '" + token + "': " + POSITIONAL_REMOVED);
			}
			String problem = VariableNames.problem(name, "argument name");
			if (problem != null) {
				throw new BroadSQLException(problem);
			}
			int valueStart = skipWhitespace(s, afterName + 1);
			if (valueStart >= n) {
				throw new BroadSQLException("Missing value for argument " + name);
			}
			int valueEnd;
			if (s.charAt(valueStart) == '\'') {
				valueEnd = ScriptLiterals.stringLiteralEnd(s, valueStart);
				if (valueEnd < 0) {
					throw new BroadSQLException("Invalid value for argument " + name + ": unterminated quoted string");
				}
				// a literal must be followed by whitespace or the end: 'a'b is not one value
				while (valueEnd < n && !Character.isWhitespace(s.charAt(valueEnd))) {
					valueEnd++;
				}
			} else {
				valueEnd = valueStart;
				while (valueEnd < n && !Character.isWhitespace(s.charAt(valueEnd))) {
					valueEnd++;
				}
			}
			String valueText = s.substring(valueStart, valueEnd);
			if (ScriptLiterals.startsQuery(valueText)) {
				throw new BroadSQLException("Invalid value for argument " + name + ": queries are not allowed as argument values; assign the result with LET first, "
						+ "then pass it as " + name + "=${variable}");
			}
			if (!seen.add(VariableNames.key(name))) {
				throw new BroadSQLException("Duplicate argument " + name + " (argument names are case-insensitive)");
			}
			result.add(new Argument(name, valueText));
			pos = valueEnd;
		}
	}

	/** The whole bare token at {@code start}: a quoted string as one token, otherwise up to whitespace. */
	private static String bareToken(String s, int start) {
		int n = s.length();
		char first = s.charAt(start);
		int end = start + 1;
		if (first == '\'' || first == '"') {
			while (end < n && s.charAt(end) != first) {
				end++;
			}
			end = Math.min(n, end + 1);
		}
		while (end < n && !Character.isWhitespace(s.charAt(end))) {
			end++;
		}
		return s.substring(start, end);
	}

	private static int skipWhitespace(String s, int pos) {
		while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
			pos++;
		}
		return pos;
	}

	/**
	 * Evaluates every value against {@code variables} without assigning anything (spec 17.3 step 1).
	 *
	 * @return the values in argument order, keyed by the name as written
	 */
	public LinkedHashMap<String, ScriptValue> evaluate(ScriptVariables variables) throws BroadSQLException {
		LinkedHashMap<String, ScriptValue> values = new LinkedHashMap<>();
		for (Argument argument : arguments) {
			values.put(argument.name(), evaluateValue(argument.name(), argument.valueText(), variables));
		}
		return values;
	}

	/**
	 * The value of one argument (or of one Editor parameter field): a literal, or one standalone reference whose
	 * value object is transferred unchanged (never rendered and reparsed).
	 */
	public static ScriptValue evaluateValue(String name, String valueText, ScriptVariables variables) throws BroadSQLException {
		String text = valueText == null ? "" : valueText.strip();
		if (text.startsWith("${")) {
			VariableReferenceScanner.Occurrence reference = VariableReferenceScanner.referenceAt(text, 0);
			if (reference.isMalformed()) {
				throw SqlReferences.malformed(reference.text());
			}
			if (reference.end() != text.length()) {
				throw invalidValue(name, text);
			}
			ScriptValue value = variables.get(reference.name());
			if (value == null) {
				throw SqlReferences.undefined(reference.name());
			}
			return value;
		}
		ScriptValue literal = ScriptLiterals.tryParse(text);
		if (literal == null) {
			throw invalidValue(name, text);
		}
		return literal;
	}

	/** Syntax check of one value (Editor parameter field): {@code null} when valid, otherwise why not. Undefined references are not checked here. */
	public static String valueSyntaxProblem(String valueText) {
		String text = valueText == null ? "" : valueText.strip();
		if (text.isEmpty()) {
			return "a value is required";
		}
		if (text.startsWith("${")) {
			VariableReferenceScanner.Occurrence reference = VariableReferenceScanner.referenceAt(text, 0);
			if (reference.isMalformed()) {
				return "malformed variable reference " + reference.text();
			}
			return reference.end() == text.length() ? null : "one variable reference only";
		}
		if (ScriptLiterals.startsQuery(text)) {
			return "queries are not allowed as argument values";
		}
		return ScriptLiterals.tryParse(text) == null ? "a value is a number, a quoted string, TRUE, FALSE, NULL or one ${variable}" : null;
	}

	private static BroadSQLException invalidValue(String name, String text) {
		return new BroadSQLException("Invalid value for argument " + name + ": '" + text + "' (a value is a number, a quoted string, TRUE, FALSE, NULL, "
				+ "or one variable reference ${name}; expressions and queries are not allowed)");
	}

	/** {@code name=value ...} text for the given values as typed (the Editor's parameter dialog). */
	public static String format(LinkedHashMap<String, String> valueTexts) {
		StringBuilder text = new StringBuilder();
		for (var entry : valueTexts.entrySet()) {
			if (text.length() > 0) {
				text.append(' ');
			}
			text.append(entry.getKey()).append('=').append(entry.getValue().strip());
		}
		return text.toString();
	}
}
