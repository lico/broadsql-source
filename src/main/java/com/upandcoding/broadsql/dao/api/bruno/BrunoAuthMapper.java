package com.upandcoding.broadsql.dao.api.bruno;

import static com.upandcoding.broadsql.dao.api.bruno.BrunoPlaceholderNormalizer.denormalize;
import static com.upandcoding.broadsql.dao.api.bruno.BrunoPlaceholderNormalizer.normalize;
import static com.upandcoding.broadsql.dao.api.bruno.BrunoYamlUtil.asMap;
import static com.upandcoding.broadsql.dao.api.bruno.BrunoYamlUtil.asString;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthType;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * Maps an OpenCollection {@code Auth} node (see docs/SPRINT XT02 - Universal API Client.md, section
 * 16.1) onto BroadSQL's {@link ApiAuthType} plus a flat list of {@code PROPERTY}-kind
 * {@link ApiAttribute} values - the owner/ID fields on each returned attribute are left unset;
 * {@link BrunoCollectionImporter} fills them in once the owning {@code API_AUTH} row's generated ID is
 * known.
 *
 * <p>{@code basic}/{@code bearer}/{@code apikey}/{@code oauth2} (client_credentials flow only) map
 * directly onto the five {@link ApiAuthType} values sub-sprint 3 will make executable. Every other
 * OpenCollection auth type (AWS v4, WSSE, Digest, NTLM, OAuth1, OAuth2 flows other than client
 * credentials) has no equivalent yet - mapped to {@link ApiAuthType#UNSUPPORTED}, never
 * {@link ApiAuthType#NONE} (the two are semantically different: {@code NONE} is "intentionally no
 * authentication," {@code UNSUPPORTED} is "authentication is defined, but this release cannot use it" -
 * sprint doc section 17.3's corrective note), with the original type/flow recorded in an
 * {@code unsupportedSourceAuthType} property, so the fact that the source had *some* authentication
 * configured is never silently lost and a future execution attempt can name exactly what is missing.
 */
final class BrunoAuthMapper {

	private BrunoAuthMapper() {
	}

	static final class MappedAuth {
		final ApiAuthType authType;
		final List<ApiAttribute> properties;
		/** Non-null exactly when {@link #authType} is {@link ApiAuthType#UNSUPPORTED} - the original OpenCollection auth type/flow. */
		final String unsupportedSourceType;

		MappedAuth(ApiAuthType authType, List<ApiAttribute> properties, String unsupportedSourceType) {
			this.authType = authType;
			this.properties = properties;
			this.unsupportedSourceType = unsupportedSourceType;
		}
	}

	/**
	 * @param authNode the raw {@code auth} YAML node - a {@code Map} for a real auth configuration, the
	 *                 literal string {@code "inherit"}, or {@code null}/absent
	 * @return {@code null} when there is nothing to persist at this owner level (absent, or
	 *         {@code "inherit"} - both leave resolution to fall through to a less specific owner, a
	 *         sub-sprint 3/4 concern); otherwise the mapped authentication
	 */
	static MappedAuth map(Object authNode) {
		if (authNode == null || "inherit".equals(authNode)) {
			return null;
		}
		Map<String, Object> auth = asMap(authNode);
		if (auth == null) {
			return null;
		}
		String type = asString(auth.get("type"));
		if (type == null) {
			return null;
		}
		switch (type) {
			case "none":
				// The exporter's own reverse mapping (unmap, NONE case below) writes exactly {type: none}
				// for an explicit ApiAuthType.NONE row - this case is that mapping's inverse. Before this
				// case existed, "none" fell through to the unsupported(type) default below, so an explicit
				// NONE auth did not round-trip: re-importing a NONE-exported endpoint produced UNSUPPORTED
				// instead (SPRINT XT02 verification finding 7). This is intentionally distinct from the
				// absent/"inherit" case just above, which returns null (no API_AUTH row at all, resolution
				// falls through to a less specific owner) - an explicit NONE persists its own row so a
				// lower-scoped NONE can override an inherited non-NONE authentication, exactly as intended.
				return new MappedAuth(ApiAuthType.NONE, List.of(), null);
			case "basic":
				return new MappedAuth(ApiAuthType.BASIC, List.of(
						property("username", asString(auth.get("username")), false),
						property("password", asString(auth.get("password")), true)), null);
			case "bearer":
				return new MappedAuth(ApiAuthType.BEARER, List.of(
						property("token", asString(auth.get("token")), true)), null);
			case "apikey": {
				boolean inQuery = "query".equalsIgnoreCase(asString(auth.get("placement")));
				return new MappedAuth(inQuery ? ApiAuthType.API_KEY_QUERY : ApiAuthType.API_KEY_HEADER, List.of(
						property("name", asString(auth.get("key")), false),
						property("value", asString(auth.get("value")), true)), null);
			}
			case "oauth2":
				return mapOAuth2(auth);
			default:
				return unsupported(type);
		}
	}

	private static MappedAuth mapOAuth2(Map<String, Object> auth) {
		String flow = asString(auth.get("flow"));
		if (!"client_credentials".equals(flow)) {
			return unsupported("oauth2:" + flow);
		}
		Map<String, Object> credentials = asMap(auth.get("credentials"));
		List<ApiAttribute> properties = new ArrayList<>();
		properties.add(property("accessTokenUrl", asString(auth.get("accessTokenUrl")), false));
		if (auth.get("refreshTokenUrl") != null) {
			properties.add(property("refreshTokenUrl", asString(auth.get("refreshTokenUrl")), false));
		}
		if (credentials != null) {
			properties.add(property("clientId", asString(credentials.get("clientId")), false));
			properties.add(property("clientSecret", asString(credentials.get("clientSecret")), true));
			if (credentials.get("placement") != null) {
				properties.add(property("tokenPlacement", asString(credentials.get("placement")), false));
			}
		}
		if (auth.get("scope") != null) {
			properties.add(property("scope", asString(auth.get("scope")), false));
		}
		return new MappedAuth(ApiAuthType.OAUTH2_CLIENT_CREDENTIALS, properties, null);
	}

	private static MappedAuth unsupported(String sourceType) {
		return new MappedAuth(ApiAuthType.UNSUPPORTED, List.of(property("unsupportedSourceAuthType", sourceType, false)), sourceType);
	}

	private static ApiAttribute property(String name, String value, boolean secret) {
		return new ApiAttribute(ApiOwnerType.AUTH, null, ApiAttributeKind.PROPERTY, name, normalize(value), secret);
	}

	// ------------------------------------------------------------------------------------------
	// Export (SPRINT XT02 sub-sprint 5) - the reverse of map() above: an ApiAuthType plus its
	// PROPERTY attributes back onto an OpenCollection Auth node.
	// ------------------------------------------------------------------------------------------

	/**
	 * Reverse of {@link #map(Object)} - builds the OpenCollection {@code Auth} node for {@code authType}
	 * from its persisted {@code PROPERTY} attributes, for {@link BrunoCollectionExporter}.
	 *
	 * <p>A secret property (Basic's password, Bearer's token, an API key's value, an OAuth2 client
	 * secret) is included only when {@code includeSecrets} is {@code true} - otherwise its key is
	 * <b>omitted entirely</b> from the node, per docs/SPRINT XT02-sub sprint 5.md section 36 ("never
	 * export literal {@code ******} as though it were the real value"); every non-secret property is
	 * always included.
	 *
	 * <p>{@link ApiAuthType#UNSUPPORTED} can only ever be reconstructed on a best-effort basis: the
	 * importer only ever records the bare original type/flow string in
	 * {@code unsupportedSourceAuthType} (see {@link #unsupported(String)}), never the full original auth
	 * node, so a type with additional fields (e.g. AWS v4's region/service) cannot be losslessly
	 * reproduced - exactly the "where possible" qualifier in docs/Amendment - Endpoint Aliases and
	 * Future Scriptability.md's export-compatibility guidance. {@code contextLabel} (e.g.
	 * {@code "Administration / Legacy Login"}) and {@code result} let the caller surface this as an
	 * export warning (spec section 38) rather than silently guessing; either may be {@code null} when
	 * the caller does not need that reporting (e.g. a unit test exercising this method directly).
	 *
	 * @return the {@code auth} node to write, or a {@code {type: none}} node for {@link ApiAuthType#NONE}
	 *         - never {@code null}; the caller decides separately whether to omit the {@code auth} key
	 *         entirely when no {@code API_AUTH} row exists at all (that is "inherit", a different case
	 *         from an explicit {@code NONE} row, which this method still has to represent)
	 */
	static Map<String, Object> unmap(ApiAuthType authType, List<ApiAttribute> properties, boolean includeSecrets, String contextLabel, BrunoExportResult result) {
		Map<String, Object> auth = new java.util.LinkedHashMap<>();
		switch (authType) {
			case NONE:
				auth.put("type", "none");
				return auth;
			case BASIC:
				auth.put("type", "basic");
				auth.put("username", denormalize(valueOf(properties, "username")));
				putSecretAware(auth, "password", findAttr(properties, "password"), includeSecrets);
				return auth;
			case BEARER:
				auth.put("type", "bearer");
				putSecretAware(auth, "token", findAttr(properties, "token"), includeSecrets);
				return auth;
			case API_KEY_HEADER:
			case API_KEY_QUERY:
				auth.put("type", "apikey");
				auth.put("placement", authType == ApiAuthType.API_KEY_QUERY ? "query" : "header");
				auth.put("key", denormalize(valueOf(properties, "name")));
				putSecretAware(auth, "value", findAttr(properties, "value"), includeSecrets);
				return auth;
			case OAUTH2_CLIENT_CREDENTIALS:
				return unmapOAuth2(properties, includeSecrets);
			case UNSUPPORTED:
			default:
				return unmapUnsupported(properties, contextLabel, result);
		}
	}

	private static Map<String, Object> unmapOAuth2(List<ApiAttribute> properties, boolean includeSecrets) {
		Map<String, Object> auth = new java.util.LinkedHashMap<>();
		auth.put("type", "oauth2");
		auth.put("flow", "client_credentials");
		putIfPresent(auth, "accessTokenUrl", valueOf(properties, "accessTokenUrl"));
		putIfPresent(auth, "refreshTokenUrl", valueOf(properties, "refreshTokenUrl"));
		Map<String, Object> credentials = new java.util.LinkedHashMap<>();
		putIfPresent(credentials, "clientId", valueOf(properties, "clientId"));
		putSecretAware(credentials, "clientSecret", findAttr(properties, "clientSecret"), includeSecrets);
		putIfPresent(credentials, "placement", valueOf(properties, "tokenPlacement"));
		if (!credentials.isEmpty()) {
			auth.put("credentials", credentials);
		}
		putIfPresent(auth, "scope", valueOf(properties, "scope"));
		return auth;
	}

	/**
	 * Best-effort reconstruction of an {@link ApiAuthType#UNSUPPORTED} node - see this method group's
	 * class javadoc for why this can only ever be a placeholder, never a full reconstruction.
	 */
	private static Map<String, Object> unmapUnsupported(List<ApiAttribute> properties, String contextLabel, BrunoExportResult result) {
		String sourceType = valueOf(properties, "unsupportedSourceAuthType");
		if (result != null) {
			result.recordUnrepresentableAuth((contextLabel != null ? contextLabel : "endpoint") + ": imported authentication type '" + sourceType
					+ "' cannot be represented exactly in OpenCollection export - only a minimal placeholder is written.");
		}
		Map<String, Object> auth = new java.util.LinkedHashMap<>();
		if (sourceType != null && sourceType.contains(":")) {
			String[] parts = sourceType.split(":", 2);
			auth.put("type", parts[0]);
			if (parts.length > 1 && !parts[1].isBlank() && !"null".equals(parts[1])) {
				auth.put("flow", parts[1]);
			}
		} else {
			auth.put("type", sourceType == null ? "none" : sourceType);
		}
		return auth;
	}

	private static void putSecretAware(Map<String, Object> node, String key, ApiAttribute attr, boolean includeSecrets) {
		if (attr == null) {
			return;
		}
		if (attr.isSecret() && !includeSecrets) {
			// Omitted entirely - never a fake "" or "******" value (spec section 36).
			return;
		}
		node.put(key, denormalize(attr.getValue()));
	}

	private static void putIfPresent(Map<String, Object> node, String key, String value) {
		if (value != null) {
			node.put(key, denormalize(value));
		}
	}

	private static String valueOf(List<ApiAttribute> properties, String name) {
		ApiAttribute attr = findAttr(properties, name);
		return attr == null ? null : attr.getValue();
	}

	private static ApiAttribute findAttr(List<ApiAttribute> properties, String name) {
		for (ApiAttribute p : properties) {
			if (name.equals(p.getName())) {
				return p;
			}
		}
		return null;
	}
}
