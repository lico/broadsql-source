package com.upandcoding.broadsql.dao.api.invocation;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** SPRINT XT02A (URL-Native API Execution) - endpoint template parsing/matching/display. */
class TestApiPathTemplate {

	@Test
	void parsesPlainRelativeTemplate() {
		ApiPathTemplate template = ApiPathTemplate.parse("/api/customer/${id}");
		Assertions.assertEquals(3, template.segmentCount());
		Assertions.assertEquals("id", template.parameterName(2));
		Assertions.assertNull(template.parameterName(1));
		Assertions.assertEquals(List.of("id"), template.parameterNamesInOrder());
	}

	@Test
	void stripsTheBrunoStyleBaseUrlPrefixBeforeSegmenting() {
		ApiPathTemplate template = ApiPathTemplate.parse("${baseUrl}/users/${id}");
		Assertions.assertEquals(2, template.segmentCount());
		Assertions.assertEquals("users", template.segment(0));
		Assertions.assertEquals("id", template.parameterName(1));
	}

	@Test
	void acceptsBrunosOwnMustacheSyntaxJustLikeTheDollarBraceForm() {
		// A real Bruno-authored/imported collection (not just BroadSQL's own manual-entry convention)
		// commonly writes {{baseUrl}}/{{name}} - both must resolve identically for the URL-native engine.
		ApiPathTemplate template = ApiPathTemplate.parse("{{baseUrl}}/api/rest/emails/v1/ping");
		Assertions.assertEquals(5, template.segmentCount());
		Assertions.assertEquals("api", template.segment(0));
		Assertions.assertTrue(template.matches(List.of("api", "rest", "emails", "v1", "ping")));

		ApiPathTemplate withParam = ApiPathTemplate.parse("{{baseUrl}}/users/{{id}}");
		Assertions.assertEquals("id", withParam.parameterName(1));
		Assertions.assertEquals("/users/:id", withParam.toCanonicalDisplay());
	}

	@Test
	void matchesStructurallyRegardlessOfWhatOccupiesAParameterPosition() {
		ApiPathTemplate template = ApiPathTemplate.parse("/api/customer/${id}");
		Assertions.assertTrue(template.matches(List.of("api", "customer", "123")));
		Assertions.assertTrue(template.matches(List.of("api", "customer", ":id")));
		Assertions.assertFalse(template.matches(List.of("api", "customer")));
		Assertions.assertFalse(template.matches(List.of("api", "order", "123")));
	}

	@Test
	void canonicalDisplayUsesColonSyntax() {
		ApiPathTemplate template = ApiPathTemplate.parse("/api/customer/${id}");
		Assertions.assertEquals("/api/customer/:id", template.toCanonicalDisplay());
	}

	@Test
	void partialSegmentTemplateIsTreatedAsLiteralForMatching() {
		ApiPathTemplate template = ApiPathTemplate.parse("/api/cust-${id}");
		Assertions.assertNull(template.parameterName(1), "a partial-segment placeholder is a documented simplification - treated as literal");
		Assertions.assertFalse(template.matches(List.of("api", "cust-123")));
	}

	// SPRINT XT02B acceptance correction (release blocker): a real Bruno .bru file's native path-parameter
	// syntax is a bare :name segment - distinct from Bruno's own {{name}} mustache variable interpolation,
	// and never converted to ${name} by BrunoPlaceholderNormalizer (which only handles the latter) - so an
	// imported endpoint's stored path can legitimately contain a literal :name segment that must be
	// recognized as a parameter, not silently treated as opaque literal text.

	@Test
	void recognizesABareColonStyleParameterSegmentJustLikeDollarBraceAndMustache() {
		ApiPathTemplate template = ApiPathTemplate.parse("/api/sites/:siteId");
		Assertions.assertEquals(3, template.segmentCount());
		Assertions.assertEquals("siteId", template.parameterName(2));
		Assertions.assertTrue(template.matches(List.of("api", "sites", "999")));
		Assertions.assertEquals("/api/sites/:siteId", template.toCanonicalDisplay());
	}

	@Test
	void normalizeColonSegmentsRewritesOnlyBareColonStyleSegmentsToDollarBraceForm() {
		Assertions.assertEquals("${baseUrl}/api/sites/${siteId}",
				ApiPathTemplate.normalizeColonSegments("${baseUrl}/api/sites/:siteId"));
		Assertions.assertEquals("{{baseUrl}}/api/sites/${siteId}/orders/${orderId}",
				ApiPathTemplate.normalizeColonSegments("{{baseUrl}}/api/sites/:siteId/orders/:orderId"));
		// Already-${name}/{{name}} segments, and literal segments, pass through completely untouched.
		Assertions.assertEquals("${baseUrl}/api/customer/${id}", ApiPathTemplate.normalizeColonSegments("${baseUrl}/api/customer/${id}"));
		Assertions.assertEquals("/api/literal-path", ApiPathTemplate.normalizeColonSegments("/api/literal-path"));
		Assertions.assertNull(ApiPathTemplate.normalizeColonSegments(null));
	}
}
