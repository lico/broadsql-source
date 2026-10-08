package com.upandcoding.broadsql.dao.api.bruno;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAuthType;

class TestBrunoAuthMapper {

	@Test
	void mapsBasicAuth() {
		BrunoAuthMapper.MappedAuth mapped = BrunoAuthMapper.map(Map.of("type", "basic", "username", "{{user}}", "password", "{{pass}}"));

		Assertions.assertEquals(ApiAuthType.BASIC, mapped.authType);
		Assertions.assertEquals("${user}", propertyValue(mapped.properties, "username"));
		Assertions.assertEquals("${pass}", propertyValue(mapped.properties, "password"));
	}

	@Test
	void mapsBearerAuthAndKeepsThePlaceholderUnresolved() {
		BrunoAuthMapper.MappedAuth mapped = BrunoAuthMapper.map(Map.of("type", "bearer", "token", "{{token}}"));

		Assertions.assertEquals(ApiAuthType.BEARER, mapped.authType);
		Assertions.assertEquals("${token}", propertyValue(mapped.properties, "token"), "a credential referencing an environment variable must stay environment-resolved, never replaced with a concrete value at import time");
	}

	@Test
	void mapsApiKeyInHeader() {
		BrunoAuthMapper.MappedAuth mapped = BrunoAuthMapper.map(Map.of("type", "apikey", "key", "X-Api-Key", "value", "{{token}}", "placement", "header"));

		Assertions.assertEquals(ApiAuthType.API_KEY_HEADER, mapped.authType);
		Assertions.assertEquals("X-Api-Key", propertyValue(mapped.properties, "name"));
	}

	@Test
	void mapsApiKeyInQuery() {
		BrunoAuthMapper.MappedAuth mapped = BrunoAuthMapper.map(Map.of("type", "apikey", "key", "api_key", "value", "secret", "placement", "query"));

		Assertions.assertEquals(ApiAuthType.API_KEY_QUERY, mapped.authType);
	}

	@Test
	void mapsOAuth2ClientCredentials() {
		BrunoAuthMapper.MappedAuth mapped = BrunoAuthMapper.map(Map.of(
				"type", "oauth2",
				"flow", "client_credentials",
				"accessTokenUrl", "{{baseUrl}}/oauth/token",
				"credentials", Map.of("clientId", "{{clientId}}", "clientSecret", "{{clientSecret}}", "placement", "basic_auth_header"),
				"scope", "admin.read"));

		Assertions.assertEquals(ApiAuthType.OAUTH2_CLIENT_CREDENTIALS, mapped.authType);
		Assertions.assertEquals("${baseUrl}/oauth/token", propertyValue(mapped.properties, "accessTokenUrl"));
		Assertions.assertEquals("${clientId}", propertyValue(mapped.properties, "clientId"));
		Assertions.assertEquals("${clientSecret}", propertyValue(mapped.properties, "clientSecret"));
		Assertions.assertEquals("basic_auth_header", propertyValue(mapped.properties, "tokenPlacement"));
		Assertions.assertEquals("admin.read", propertyValue(mapped.properties, "scope"));
	}

	@Test
	void mapsUnsupportedOAuth2FlowToUnsupportedNeverNoneAndRecordsTheOriginalType() {
		BrunoAuthMapper.MappedAuth mapped = BrunoAuthMapper.map(Map.of("type", "oauth2", "flow", "authorization_code"));

		Assertions.assertEquals(ApiAuthType.UNSUPPORTED, mapped.authType, "a defined-but-unimplemented auth mechanism must never be reported as NONE (intentionally no auth)");
		Assertions.assertEquals("oauth2:authorization_code", mapped.unsupportedSourceType);
	}

	@Test
	void mapsUnsupportedAuthTypeToUnsupportedNeverNoneAndRecordsIt() {
		BrunoAuthMapper.MappedAuth mapped = BrunoAuthMapper.map(Map.of("type", "digest", "username", "u", "password", "p"));

		Assertions.assertEquals(ApiAuthType.UNSUPPORTED, mapped.authType, "a defined-but-unimplemented auth mechanism must never be reported as NONE (intentionally no auth)");
		Assertions.assertEquals("digest", mapped.unsupportedSourceType);
	}

	@Test
	void inheritAndAbsentAuthBothMapToNull() {
		Assertions.assertNull(BrunoAuthMapper.map("inherit"));
		Assertions.assertNull(BrunoAuthMapper.map(null));
	}

	/**
	 * SPRINT XT02 verification finding 7: an explicit {@code {type: none}} auth node (the exporter's own
	 * reverse mapping for {@link ApiAuthType#NONE}) used to fall through to the {@code default} branch and
	 * come back as {@link ApiAuthType#UNSUPPORTED} - the importer had no {@code "none"} case at all.
	 */
	@Test
	void mapsExplicitNoneAuthToNoneNotUnsupported() {
		BrunoAuthMapper.MappedAuth mapped = BrunoAuthMapper.map(Map.of("type", "none"));

		Assertions.assertEquals(ApiAuthType.NONE, mapped.authType);
		Assertions.assertNull(mapped.unsupportedSourceType);
		Assertions.assertTrue(mapped.properties.isEmpty());
	}

	/** Absent/"inherit" (null - no API_AUTH row at all) and explicit NONE (a real row) must remain distinguishable - explicit NONE must never collapse into the "inherit" case. */
	@Test
	void explicitNoneIsDistinctFromAbsentOrInheritAuth() {
		BrunoAuthMapper.MappedAuth explicitNone = BrunoAuthMapper.map(Map.of("type", "none"));
		Assertions.assertNotNull(explicitNone, "an explicit NONE node must persist its own row, unlike absent/inherit");
		Assertions.assertEquals(ApiAuthType.NONE, explicitNone.authType);

		Assertions.assertNull(BrunoAuthMapper.map("inherit"), "inherit must still map to null (no row)");
		Assertions.assertNull(BrunoAuthMapper.map(null), "absent auth must still map to null (no row)");
	}

	@Test
	void explicitNoneRoundTripsThroughExportAndReimportWithoutBecomingUnsupported() {
		Map<String, Object> exported = BrunoAuthMapper.unmap(ApiAuthType.NONE, List.of(), true, "Some API / Some endpoint", null);
		Assertions.assertEquals("none", exported.get("type"));

		BrunoAuthMapper.MappedAuth reimported = BrunoAuthMapper.map(exported);
		Assertions.assertEquals(ApiAuthType.NONE, reimported.authType,
				"an explicit NONE auth exported as {type: none} must re-import as NONE, not UNSUPPORTED");
	}

	private String propertyValue(List<ApiAttribute> properties, String name) {
		return properties.stream().filter(p -> name.equals(p.getName())).findFirst().map(ApiAttribute::getValue).orElse(null);
	}
}
