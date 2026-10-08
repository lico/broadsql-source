package com.upandcoding.broadsql.controller.shell.scripts;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * SPRINT 0110A: the literal grammar shared by {@code LET} and script arguments (spec sections 8.1 and 22):
 * an integer ({@code [-]digits}), a decimal ({@code [-]digits.digits}), a single-quoted string with {@code ''}
 * for one quote, {@code TRUE}, {@code FALSE} or {@code NULL}. Nothing else: no sign {@code +}, no exponent, no
 * {@code .5} or {@code 1.}, no expression. A string literal is never interpolated.
 */
public final class ScriptLiterals {

	private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");
	private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+\\.[0-9]+");

	/** The message of every invalid value (spec section 8.4). */
	public static final String VALUES_HINT = "values are a number, a quoted string, TRUE, FALSE, NULL, one variable reference, or a query starting with SELECT, WITH or VALUES";

	private ScriptLiterals() {
	}

	/**
	 * The value of {@code text}, which must be exactly one literal (surrounding whitespace ignored).
	 *
	 * @throws BroadSQLException "Invalid value" otherwise
	 */
	public static ScriptValue parse(String text) throws BroadSQLException {
		ScriptValue value = tryParse(text);
		if (value == null) {
			throw invalid(text);
		}
		return value;
	}

	/** As {@link #parse}, {@code null} instead of an exception. */
	public static ScriptValue tryParse(String text) {
		if (text == null) {
			return null;
		}
		String t = text.strip();
		if (t.isEmpty()) {
			return null;
		}
		if (t.charAt(0) == '\'') {
			int end = stringLiteralEnd(t, 0);
			if (end != t.length()) {
				return null;
			}
			return ScriptValue.ofString(unquote(t));
		}
		String upper = t.toUpperCase(Locale.ROOT);
		switch (upper) {
			case "TRUE":
				return ScriptValue.ofBoolean(true);
			case "FALSE":
				return ScriptValue.ofBoolean(false);
			case "NULL":
				return ScriptValue.untypedNull();
			default:
				break;
		}
		if (INTEGER.matcher(t).matches()) {
			try {
				return ScriptValue.ofLong(Long.parseLong(t));
			} catch (NumberFormatException outOfRange) {
				return ScriptValue.ofDecimal(new BigDecimal(t));
			}
		}
		if (DECIMAL.matcher(t).matches()) {
			return ScriptValue.ofDecimal(new BigDecimal(t));
		}
		return null;
	}

	/**
	 * The index just after the single-quoted string starting at {@code start} ({@code text.charAt(start)} is
	 * {@code '}), honoring {@code ''}; {@code -1} when it is not terminated.
	 */
	public static int stringLiteralEnd(String text, int start) {
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
		return -1;
	}

	/** The content of a complete single-quoted literal, {@code ''} turned into {@code '}. */
	public static String unquote(String quoted) {
		return quoted.substring(1, quoted.length() - 1).replace("''", "'");
	}

	/** Whether {@code text} starts a query: its first maximal run of letters is SELECT, WITH or VALUES (spec section 8.3). */
	public static boolean startsQuery(String text) {
		if (text == null) {
			return false;
		}
		String t = text.stripLeading();
		int end = 0;
		while (end < t.length() && Character.isLetter(t.charAt(end))) {
			end++;
		}
		String word = t.substring(0, end).toUpperCase(Locale.ROOT);
		return word.equals("SELECT") || word.equals("WITH") || word.equals("VALUES");
	}

	public static BroadSQLException invalid(String text) {
		return new BroadSQLException("Invalid value '" + (text == null ? "" : text.strip()) + "': " + VALUES_HINT);
	}
}
