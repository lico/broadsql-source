package com.upandcoding.broadsql.dao.api.model;

/**
 * The supported {@code API_AUTH.AUTH_TYPE} values - see docs/SPRINT XT02 - Universal API Client.md,
 * section 13.1. {@link #NONE} through {@link #BEARER} are implemented in sub-sprint 3 (static
 * authentication); {@link #OAUTH2_CLIENT_CREDENTIALS} in the same sub-sprint's later half. Every value's
 * configuration (header name, username, token URL, etc.) is stored as {@code API_ATTRIBUTE} rows of kind
 * {@code PROPERTY} owned by the {@code API_AUTH} row - not as dedicated columns - so adding a future
 * authentication type never requires a schema change.
 *
 * <p>{@link #NONE} and {@link #UNSUPPORTED} are deliberately distinct, never conflated (sprint doc
 * section 17.3's corrective note): {@code NONE} means "this request intentionally has no
 * authentication"; {@code UNSUPPORTED} means "the source defined an authentication mechanism this
 * BroadSQL release does not implement" (e.g. an imported Digest, NTLM, WSSE, AWS v4, OAuth1, or
 * non-client-credentials OAuth2 configuration). An owner mapped to {@code UNSUPPORTED} always carries an
 * {@code unsupportedSourceAuthType} {@code PROPERTY} attribute recording the original type, so a future
 * execution attempt can report exactly which mechanism is missing rather than silently sending an
 * unauthenticated request.
 */
public enum ApiAuthType {
	NONE, API_KEY_HEADER, API_KEY_QUERY, BASIC, BEARER, OAUTH2_CLIENT_CREDENTIALS, UNSUPPORTED
}
