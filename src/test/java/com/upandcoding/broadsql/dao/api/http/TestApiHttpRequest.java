package com.upandcoding.broadsql.dao.api.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * XT02 final corrective patch (Codex finding 1): HTTP header names are case-insensitive - the shared
 * request model must treat {@code Content-Type}/{@code content-type}/{@code CONTENT-TYPE} (and any other
 * header, not just Content-Type) as the same logical header, so a more specific scope's header replaces
 * an inherited one regardless of casing, rather than both surviving as separate map entries.
 */
class TestApiHttpRequest {

	@Test
	void aDifferentlyCasedHeaderReplacesTheEarlierOneRatherThanCoexisting() {
		ApiHttpRequest request = new ApiHttpRequest();
		request.setHeader("Content-Type", "application/json");
		request.setHeader("content-type", "application/merge-patch+json");

		Assertions.assertEquals(1, request.getHeaders().size(), "only one logical header may survive");
		Assertions.assertEquals("application/merge-patch+json", request.getHeaders().get("Content-Type"),
				"the more specific (later) value must win");
		Assertions.assertEquals("application/merge-patch+json", request.getHeaders().get("content-type"),
				"lookup must also be case-insensitive - callers must not need to know which casing was stored");
	}

	@Test
	void arbitraryHeaderCasingBehavesTheSameWayNotJustContentType() {
		ApiHttpRequest request = new ApiHttpRequest();
		request.setHeader("X-Tenant", "acme");
		request.setHeader("x-tenant", "widgetco");
		request.setHeader("Accept", "text/plain");
		request.setHeader("ACCEPT", "application/json");

		Assertions.assertEquals(2, request.getHeaders().size());
		Assertions.assertEquals("widgetco", request.getHeaders().get("X-TENANT"));
		Assertions.assertEquals("application/json", request.getHeaders().get("accept"));
	}

	@Test
	void anAuthorizationHeaderIsReplacedRegardlessOfCase() {
		ApiHttpRequest request = new ApiHttpRequest();
		request.setHeader("authorization", "should-never-be-sent");
		request.setHeader("Authorization", "Bearer real-token");

		Assertions.assertEquals(1, request.getHeaders().size());
		Assertions.assertEquals("Bearer real-token", request.getHeaders().get("AUTHORIZATION"));
	}
}
