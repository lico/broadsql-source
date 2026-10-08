package com.upandcoding.broadsql.dao.api.invocation;

import java.io.IOException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * Endpoint resolution by route specificity: among the structurally matching endpoints of the request's HTTP
 * method, a static segment outranks a placeholder; only equally specific (or incomparable) candidates are
 * reported as ambiguous.
 */
class TestApiEndpointResolver {

	private static final String API_ID = "DESK";

	private ApiDefinitionsVault vault;
	private ApiVersion version;
	private ApiEndpointResolver resolver;

	@BeforeEach
	void setUp() throws IOException, BroadSQLException {
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		resolver = new ApiEndpointResolver(vault);
	}

	private ApiEndpoint endpoint(String name, String method, String path) throws BroadSQLException {
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, name, method, path, 0);
		vault.saveEndpoint(endpoint);
		return endpoint;
	}

	private ApiEndpoint resolve(String method, String path) throws BroadSQLException {
		return resolver.resolve(API_ID, method, ApiPathTemplate.segmentize(path));
	}

	@Test
	void staticSegmentBeatsParameterSegment() throws BroadSQLException {
		endpoint("bySender", "GET", "${baseUrl}/senders/${sender}");
		endpoint("listAll", "GET", "${baseUrl}/senders/list");

		Assertions.assertEquals("listAll", resolve("GET", "/senders/list").getName());
	}

	@Test
	void resultDoesNotDependOnInsertionOrder() throws BroadSQLException {
		endpoint("listAll", "GET", "${baseUrl}/senders/list");
		endpoint("bySender", "GET", "${baseUrl}/senders/${sender}");

		Assertions.assertEquals("listAll", resolve("GET", "/senders/list").getName());
	}

	@Test
	void parameterRouteStillMatchesOtherValues() throws BroadSQLException {
		endpoint("listAll", "GET", "${baseUrl}/senders/list");
		endpoint("bySender", "GET", "${baseUrl}/senders/${sender}");

		Assertions.assertEquals("bySender", resolve("GET", "/senders/john").getName());
	}

	@Test
	void colonAndMustacheSpellingsRankLikeDollarBrace() throws BroadSQLException {
		endpoint("bySender", "GET", "{{baseUrl}}/senders/:sender");
		endpoint("listAll", "GET", "{{baseUrl}}/senders/list");

		Assertions.assertEquals("listAll", resolve("GET", "/senders/list").getName());
		Assertions.assertEquals("bySender", resolve("GET", "/senders/x").getName());
	}

	@Test
	void equivalentParameterRoutesRemainAmbiguous() throws BroadSQLException {
		endpoint("bySender", "GET", "${baseUrl}/senders/${sender}");
		endpoint("byId", "GET", "${baseUrl}/senders/${id}");

		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> resolve("GET", "/senders/foo"));
		Assertions.assertTrue(e.getMessage().contains("Ambiguous endpoint configuration"), e.getMessage());
		Assertions.assertTrue(e.getMessage().contains("/senders/:sender") && e.getMessage().contains("/senders/:id"), e.getMessage());
	}

	@Test
	void identicalStaticRoutesRemainAmbiguous() throws BroadSQLException {
		endpoint("one", "GET", "${baseUrl}/senders/list");
		endpoint("two", "GET", "${baseUrl}/senders/list");

		Assertions.assertThrows(BroadSQLException.class, () -> resolve("GET", "/senders/list"));
	}

	@Test
	void httpMethodSeparatesEndpoints() throws BroadSQLException {
		endpoint("getList", "GET", "${baseUrl}/senders/list");
		endpoint("postList", "POST", "${baseUrl}/senders/list");

		Assertions.assertEquals("getList", resolve("GET", "/senders/list").getName());
		Assertions.assertEquals("postList", resolve("POST", "/senders/list").getName());
	}

	@Test
	void methodMismatchIsNotRescuedByAMoreSpecificRouteOfAnotherMethod() throws BroadSQLException {
		endpoint("postList", "POST", "${baseUrl}/senders/list");
		endpoint("bySender", "GET", "${baseUrl}/senders/${sender}");

		Assertions.assertEquals("bySender", resolve("GET", "/senders/list").getName());
	}

	@Test
	void multiSegmentMostLiteralsWins() throws BroadSQLException {
		endpoint("allParams", "GET", "${baseUrl}/a/${x}/${y}");
		endpoint("oneLiteral", "GET", "${baseUrl}/a/b/${y}");
		endpoint("allLiterals", "GET", "${baseUrl}/a/b/c");

		Assertions.assertEquals("allLiterals", resolve("GET", "/a/b/c").getName());
		Assertions.assertEquals("oneLiteral", resolve("GET", "/a/b/z").getName());
		Assertions.assertEquals("allParams", resolve("GET", "/a/q/z").getName());
	}

	@Test
	void incomparableMultiSegmentRoutesAreAmbiguous() throws BroadSQLException {
		endpoint("abx", "GET", "${baseUrl}/a/b/${x}");
		endpoint("ayc", "GET", "${baseUrl}/a/${y}/c");

		// /a/b/c matches both; each pins a literal the other leaves open, so neither is more specific.
		Assertions.assertThrows(BroadSQLException.class, () -> resolve("GET", "/a/b/c"));
		// Requests matching only one of them still resolve.
		Assertions.assertEquals("abx", resolve("GET", "/a/b/z").getName());
		Assertions.assertEquals("ayc", resolve("GET", "/a/q/c").getName());
	}

	@Test
	void foreignWordListGetsNoSpecialTreatment() throws BroadSQLException {
		endpoint("bySender", "GET", "${baseUrl}/senders/${sender}");

		Assertions.assertEquals("bySender", resolve("GET", "/senders/list").getName());
	}

	@Test
	void legacyEmbeddedQueryTextInStoredPathDoesNotAffectRouteIdentity() throws BroadSQLException {
		endpoint("listAll", "GET", "${baseUrl}/api/rest/emails/v1/senders/list?key=");
		endpoint("bySender", "GET", "${baseUrl}/api/rest/emails/v1/senders/${sender}");

		Assertions.assertEquals("listAll", resolve("GET", "/api/rest/emails/v1/senders/list").getName());
		Assertions.assertEquals("bySender", resolve("GET", "/api/rest/emails/v1/senders/john").getName());
	}

	@Test
	void noMatchStillReportsNoEndpoint() throws BroadSQLException {
		endpoint("listAll", "GET", "${baseUrl}/senders/list");

		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> resolve("GET", "/other"));
		Assertions.assertTrue(e.getMessage().contains("No endpoint matches"), e.getMessage());
	}

	@Test
	void specificityOrderIsAStrictPartialOrder() {
		ApiPathTemplate literal = ApiPathTemplate.parse("/s/list");
		ApiPathTemplate param = ApiPathTemplate.parse("/s/${a}");
		ApiPathTemplate otherParam = ApiPathTemplate.parse("/s/${b}");
		Assertions.assertTrue(literal.isMoreSpecificThan(param));
		Assertions.assertFalse(param.isMoreSpecificThan(literal));
		Assertions.assertFalse(param.isMoreSpecificThan(otherParam));
		Assertions.assertFalse(literal.isMoreSpecificThan(literal));
	}
}
