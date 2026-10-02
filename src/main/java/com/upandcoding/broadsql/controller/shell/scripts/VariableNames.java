package com.upandcoding.broadsql.controller.shell.scripts;

import java.util.Locale;
import java.util.Set;

/**
 * SPRINT 0110A: the one rule for SQL scripting variable names, shared by {@code LET}, script arguments,
 * {@code -- @params} and {@code ${name}} references (spec section 7.1): {@code [A-Za-z_][A-Za-z0-9_]*}, 1 to
 * {@value #MAX_LENGTH} characters, case-insensitive, never one of the reserved names {@code ENV},
 * {@code NULL}, {@code TRUE}, {@code FALSE}.
 */
public final class VariableNames {

	public static final int MAX_LENGTH = 64;

	/** Reserved names (upper case), compared case-insensitively. */
	public static final Set<String> RESERVED = Set.of("ENV", "NULL", "TRUE", "FALSE");

	private VariableNames() {
	}

	public static boolean isNameStart(char c) {
		return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
	}

	public static boolean isNameChar(char c) {
		return isNameStart(c) || (c >= '0' && c <= '9');
	}

	/** Whether {@code name} has the shape of a name (ignoring the reserved names). */
	public static boolean hasNameShape(String name) {
		if (name == null || name.isEmpty() || name.length() > MAX_LENGTH || !isNameStart(name.charAt(0))) {
			return false;
		}
		for (int i = 1; i < name.length(); i++) {
			if (!isNameChar(name.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	public static boolean isReserved(String name) {
		return name != null && RESERVED.contains(name.toUpperCase(Locale.ROOT));
	}

	public static boolean isValid(String name) {
		return hasNameShape(name) && !isReserved(name);
	}

	/** The case-insensitive key of {@code name}. */
	public static String key(String name) {
		return name.toUpperCase(Locale.ROOT);
	}

	/** {@code null} when {@code name} is valid, otherwise why it is not ({@code what} is e.g. "variable name"). */
	public static String problem(String name, String what) {
		if (name == null || name.isEmpty()) {
			return "Missing " + what;
		}
		if (isReserved(name)) {
			return "Invalid " + what + " '" + name + "': ENV, NULL, TRUE and FALSE are reserved";
		}
		if (!hasNameShape(name)) {
			return "Invalid " + what + " '" + name + "': a name starts with a letter or _, contains only letters, digits and _, and has at most "
					+ MAX_LENGTH + " characters";
		}
		return null;
	}
}
