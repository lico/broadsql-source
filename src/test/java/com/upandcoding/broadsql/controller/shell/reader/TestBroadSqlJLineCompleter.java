package com.upandcoding.broadsql.controller.shell.reader;

import java.util.ArrayList;
import java.util.List;

import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * SPRINT XT02A (URL-Native API Execution) corrective pass, section 13 - {@link BroadSqlJLineCompleter}
 * exercised through JLine's own real {@code DefaultParser} (pure text parsing, needs no terminal at
 * all) so the exact word-splitting/cursor behavior JLine will actually use in production is under
 * test, without needing a physical/interactive terminal. This is the seam between "no JLine
 * involved" ({@code TestCompletionService}) and "a real terminal is involved" (the one thing this
 * environment genuinely cannot verify, left as the user's manual smoke test).
 */
class TestBroadSqlJLineCompleter {

	private static final String API_ID = "DESK";

	private ApiDefinitionsVault vault;
	private ApiCatalogService catalog;

	@BeforeEach
	void setUp() throws BroadSQLException {
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Get customer", "GET", "${baseUrl}/api/customer/${id}", 0);
		endpoint.setAlias("CUST");
		vault.saveEndpoint(endpoint);
		catalog = new ApiCatalogService(vault);
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "Test"));
	}

	@AfterEach
	void tearDown() {
		ApiSessionContextHolder.clear();
	}

	private List<Candidate> complete(String line) {
		DefaultParser parser = new DefaultParser();
		ParsedLine parsed = parser.parse(line, line.length(), org.jline.reader.Parser.ParseContext.COMPLETE);
		BroadSqlJLineCompleter completer = new BroadSqlJLineCompleter(new CompletionService(), catalog, List::of);
		List<Candidate> candidates = new ArrayList<>();
		completer.complete(null, parsed, candidates);
		return candidates;
	}

	@Test
	void jLinesOwnParserSplitsOnWhitespaceOnlyMatchingBroadSqlsOwnTokenizing() {
		String line = "RUN /api/customer/:id?expand=";
		DefaultParser parser = new DefaultParser();
		ParsedLine parsed = parser.parse(line, line.length(), org.jline.reader.Parser.ParseContext.COMPLETE);
		// The whole URL (slashes, colon, question mark, equals) must stay ONE word - never split into
		// several by JLine's default delimiters - or query-parameter completion would compute the wrong
		// replacement span entirely.
		Assertions.assertEquals(List.of("RUN", "/api/customer/:id?expand="), parsed.words());
		Assertions.assertEquals(1, parsed.wordIndex());
	}

	@Test
	void runAliasExpandsToTheCanonicalUrlThroughTheRealJLineParser() {
		List<Candidate> result = complete("RUN CUST");
		Assertions.assertEquals(1, result.size());
		Assertions.assertEquals("/api/customer/:id", result.get(0).value());
	}

	@Test
	void queryValueCompletionWorksThroughTheRealJLineParser() throws BroadSQLException {
		int endpointId = vault.getEndpointsForVersion(vault.getDefaultVersion(API_ID).getId()).get(0).getId();
		com.upandcoding.broadsql.dao.api.model.ApiAttribute expand = new com.upandcoding.broadsql.dao.api.model.ApiAttribute(
				com.upandcoding.broadsql.dao.api.model.ApiOwnerType.ENDPOINT, String.valueOf(endpointId),
				com.upandcoding.broadsql.dao.api.model.ApiAttributeKind.QUERY_PARAMETER, "expand", null, false);
		expand.setAllowedValues("mail|orders");
		vault.saveAttribute(expand);

		List<Candidate> result = complete("RUN /api/customer/:id?expand=");

		Assertions.assertEquals(2, result.size());
		Assertions.assertTrue(result.stream().anyMatch(c -> c.value().equals("/api/customer/:id?expand=mail")), result.toString());
	}

	@Test
	void noCandidatesWithoutAnActiveApiSession() {
		ApiSessionContextHolder.clear();
		List<Candidate> result = complete("RUN CUST");
		Assertions.assertTrue(result.isEmpty());
	}
}
