package com.upandcoding.broadsql.dao.api.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory-only access-token cache for OAuth2 Client Credentials - docs/SPRINT XT02 - Universal API
 * Client.md, section 19.8. Never persisted to the CDF (only the OAuth *configuration* - token URL, client
 * ID/secret, scope - is persisted, as {@code API_ATTRIBUTE} rows); a runtime access token lives only as
 * long as this object does.
 *
 * <p><b>Cache key</b> is built by the caller ({@link ApiAuthenticationRuntime#buildOAuth2CacheKey}) as a
 * SHA-256 fingerprint of the entire effective OAuth context - the auth definition's owning row, the
 * selected environment's ID, and every resolved credential-bearing value (token URL, client ID, client
 * secret, scope, client-authentication method) - corrected in the sub-sprint 3 review (section 20) after
 * an earlier version keyed on token URL + client ID alone, which could not tell two different
 * environments (or a changed secret/scope) apart when those two values happened to coincide. This class
 * itself stays agnostic to what a key represents - it only needs it to be an opaque, sufficiently unique
 * string.
 *
 * <p>A token with no {@code expires_in} in its response is deliberately <b>never cached</b> (an absent
 * expiry always misses, forcing a fresh fetch on every use) rather than assumed valid indefinitely -
 * documented in {@link #getValidToken}.
 */
public class ApiOAuth2TokenCache {

	private static final Duration SAFETY_MARGIN = Duration.ofSeconds(5);

	private final ConcurrentMap<String, CachedToken> tokens = new ConcurrentHashMap<>();

	/**
	 * @return the cached access token for {@code cacheKey} if one exists and will not expire within the
	 *         next {@link #SAFETY_MARGIN}, {@code null} otherwise (no entry, no expiry info recorded, or
	 *         expiry too close/past) - a {@code null} return is the caller's signal to fetch a fresh token.
	 */
	public String getValidToken(String cacheKey) {
		CachedToken cached = tokens.get(cacheKey);
		if (cached == null || cached.expiresAt == null) {
			return null;
		}
		if (Instant.now().isAfter(cached.expiresAt.minus(SAFETY_MARGIN))) {
			return null;
		}
		return cached.accessToken;
	}

	/** @param expiresAt {@code null} when the token response carried no {@code expires_in} - such a token is never actually reused, see {@link #getValidToken}. */
	public void put(String cacheKey, String accessToken, Instant expiresAt) {
		tokens.put(cacheKey, new CachedToken(accessToken, expiresAt));
	}

	private static final class CachedToken {
		private final String accessToken;
		private final Instant expiresAt;

		CachedToken(String accessToken, Instant expiresAt) {
			this.accessToken = accessToken;
			this.expiresAt = expiresAt;
		}

		/** Redacted - see {@link com.upandcoding.broadsql.dao.api.model.ApiAttribute}'s Javadoc for the same reasoning. */
		@Override
		public String toString() {
			return "CachedToken{accessToken=******, expiresAt=" + expiresAt + "}";
		}
	}
}
