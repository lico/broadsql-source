package com.upandcoding.broadsql.controller.shell.swing.api.form;

/**
 * The choices {@code CONFIG API}'s Authentication editor offers - docs/SPRINT XT02-sub sprint 5 - API
 * Configuration GUI + Bruno YAML Round-trip.md, sections 13/22/27. A strict superset of
 * {@link com.upandcoding.broadsql.dao.api.model.ApiAuthType}: {@link #INHERIT} has no {@code ApiAuthType}
 * equivalent at all - it means "no {@code API_AUTH} row at this owner," which the persistence layer
 * represents as an absent row, not a value (see {@code ApiEffectiveAuthResolver}'s javadoc for why an
 * absent row and an explicit {@link com.upandcoding.broadsql.dao.api.model.ApiAuthType#NONE} row are
 * different and must never be conflated).
 *
 * <p>{@link #INHERIT} is only a meaningful choice where a parent scope actually exists - API-level
 * authentication has no parent to inherit from (section 13), so the GUI must not offer it there; Group
 * and Endpoint scopes always have one (a parent folder, or the API itself).
 */
public enum ApiAuthChoice {
	INHERIT, NONE, BASIC, BEARER, API_KEY_HEADER, API_KEY_QUERY, OAUTH2_CLIENT_CREDENTIALS, UNSUPPORTED
}
