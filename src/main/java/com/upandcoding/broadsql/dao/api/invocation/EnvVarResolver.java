package com.upandcoding.broadsql.dao.api.invocation;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Resolves {@code ${ENV:NAME}} references to operating-system environment variables - SPRINT XT02A
 * (URL-Native API Execution), section 3.3.
 *
 * <p><b>Deviation from the specification's exact wording (documented in docs/TECHNICAL_CHANGE.md)</b>:
 * the spec's original {@code ${NAME}} spelling was found, during repository analysis, to collide with
 * the pre-existing, unrelated {@code ${var}} API-variable templating already resolved by
 * {@link com.upandcoding.broadsql.dao.api.ApiVariableSubstitutor} inside a stored endpoint's own
 * path/query/header/body definitions. Per explicit user direction, this class recognizes only the
 * {@code ${ENV:NAME}} namespaced form; an ordinary {@code ${var}} (no {@code ENV:} prefix) is left
 * completely untouched, so an existing stored endpoint template such as {@code /api/customer/${customerId}}
 * keeps its current API-variable-templating meaning unchanged.
 *
 * <p>Never silently substitutes an empty string for an undefined variable (section 3.3) - fails
 * explicitly instead, naming the variable.
 */
public final class EnvVarResolver {

	private static final Pattern ENV_REF = Pattern.compile("\\$\\{ENV:([A-Za-z0-9_]+)\\}");

	private EnvVarResolver() {
	}

	public static String resolve(String input) throws BroadSQLException {
		return resolve(input, System::getenv);
	}

	/** Overload taking an explicit lookup function - the test seam, avoids depending on the real process environment. */
	public static String resolve(String input, Function<String, String> envLookup) throws BroadSQLException {
		if (input == null || !input.contains("${ENV:")) {
			return input;
		}
		Matcher matcher = ENV_REF.matcher(input);
		StringBuilder result = new StringBuilder();
		int last = 0;
		while (matcher.find()) {
			String name = matcher.group(1);
			String value = envLookup.apply(name);
			if (value == null) {
				throw new BroadSQLException("Environment variable '" + name + "' is not defined.");
			}
			result.append(input, last, matcher.start()).append(value);
			last = matcher.end();
		}
		result.append(input.substring(last));
		return result.toString();
	}
}
