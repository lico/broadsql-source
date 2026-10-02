package com.upandcoding.broadsql.dao.api.model;

import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * Validates the syntax of a BroadSQL endpoint alias - docs/Amendment - Endpoint Aliases and Future
 * Scriptability.md, section 5. An alias is optional BroadSQL-owned metadata (see {@link ApiEndpoint#getAlias()})
 * that will eventually become the stable name a future {@code CALL} statement uses
 * ({@code CALL DO_ORDER(...)}), so it must already be a legal identifier for that future grammar - the
 * regex is verbatim from the amendment: {@code ^[A-Za-z_][A-Za-z0-9_]*$}.
 *
 * <p>Only applied when the alias is non-blank - a blank/absent alias is always valid (the field is
 * optional, per the amendment's section 1/4: never auto-generated, never required).
 */
public final class ApiAliasValidator {

	private static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

	private ApiAliasValidator() {
	}

	/** @return {@code true} if {@code alias} is blank (nothing to validate) or matches the required identifier shape. */
	public static boolean isValid(String alias) {
		return StringUtils.isBlank(alias) || ALIAS_PATTERN.matcher(alias).matches();
	}
}
