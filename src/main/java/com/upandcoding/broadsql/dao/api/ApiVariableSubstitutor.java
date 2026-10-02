package com.upandcoding.broadsql.dao.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.bruno.BrunoPlaceholderNormalizer;

/**
 * Substitutes {@code ${name}} placeholders (BroadSQL's internal variable syntax - see docs/SPRINT XT02 -
 * Universal API Client.md, section 16.3) in a template string against an already-{@link
 * ApiVariableResolver#resolve resolved} variable map. Deliberately separate from resolution itself:
 * {@link ApiVariableResolver} determines *which values are in scope*; this class applies them to one
 * concrete string (an auth property value - sub-sprint 3; a URL/header template - sub-sprint 4).
 *
 * <p>Fails loudly - never silently - when a referenced variable is not in scope, per the requirement that
 * both authentication and endpoint execution must fail before any network request is issued rather than
 * send a literal unresolved {@code ${token}}/{@code ${cloudId}} or an empty value.
 *
 * <p>Since SPRINT XT02-7B, Bruno's own mustache-style {@code {{name}}} syntax is also accepted, as an
 * alternative to {@code ${name}} - the documented/preferred form for new work, matching Bruno's own
 * convention, while {@code ${name}} remains fully supported for compatibility with content already
 * imported or written before this sprint. Both are normalized to {@code ${name}} via
 * {@link BrunoPlaceholderNormalizer#normalize} (the same conversion the Bruno importer has always
 * applied at import time) before substitution runs, so a template may freely mix both syntaxes, and
 * variable name matching remains exactly as case-sensitive as before for either syntax.
 */
public final class ApiVariableSubstitutor {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)\\}");

	private ApiVariableSubstitutor() {
	}

	/**
	 * @param template            the string to substitute into - {@code null} returns {@code null}. May
	 *                            use {@code {{name}}} and/or {@code ${name}} interchangeably.
	 * @param variables           the resolved variable scope
	 * @param missingVariableContext what to call an unresolved variable in the thrown message (e.g.
	 *                            {@code "authentication variable"} -&gt; {@code "Unable to resolve
	 *                            authentication variable: token"})
	 */
	public static String substitute(String template, Map<String, String> variables, String missingVariableContext) throws BroadSQLException {
		if (template == null) {
			return null;
		}
		Matcher matcher = PLACEHOLDER.matcher(BrunoPlaceholderNormalizer.normalize(template));
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String name = matcher.group(1);
			String value = variables.get(name);
			if (value == null) {
				throw new BroadSQLException("Unable to resolve " + missingVariableContext + ": " + name);
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/**
	 * Percent-encodes {@code value} using URL path-segment rules - space becomes {@code %20}, not the
	 * form-encoding {@code +} {@link URLEncoder} produces by default (correct for a query parameter value,
	 * wrong inside a URL path segment). Used to pre-encode a resolved *path parameter's* value before it
	 * is placed into the variable map a URL template is substituted against (sub-sprint 4, section 21.5) -
	 * deliberately not applied to every substituted variable in a URL template, since most of them
	 * (`${baseUrl}`, a multi-segment `${resource}`) are structural and must be inserted verbatim, not
	 * treated as one opaque value to escape.
	 */
	public static String encodePathSegment(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
