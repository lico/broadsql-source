package com.upandcoding.broadsql.controller.shell.reader;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * SPRINT XT02A (URL-Native API Execution) corrective pass, section 13 - {@link CompletionService}
 * exercised with zero JLine involvement and no real terminal, proving the "unit-testable
 * independently of a real terminal" requirement concretely: every scenario listed in the sprint's
 * corrective-pass instructions is covered here as a plain string-in/candidates-out test.
 */
class TestCompletionService {

	private static final String API_ID = "DESK";

	private ApiDefinitionsVault vault;
	private ApiCatalogService catalog;
	private CompletionService completion;
	private Integer userByUserNameId;

	@BeforeEach
	void setUp() throws BroadSQLException {
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));

		ApiEndpoint getCustomer = new ApiEndpoint(version.getId(), null, "Get customer", "GET", "${baseUrl}/api/customer/${id}", 0);
		getCustomer.setAlias("CUST");
		vault.saveEndpoint(getCustomer);
		ApiAttribute expand = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(getCustomer.getId()), ApiAttributeKind.QUERY_PARAMETER, "expand", null, false);
		expand.setAllowedValues("mail|orders|profile");
		vault.saveAttribute(expand);
		ApiAttribute showall = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(getCustomer.getId()), ApiAttributeKind.QUERY_PARAMETER, "showall", null, false);
		vault.saveAttribute(showall);

		ApiEndpoint deleteCustomer = new ApiEndpoint(version.getId(), null, "Delete customer", "DELETE", "${baseUrl}/api/customer/${id}", 1);
		deleteCustomer.setAlias("DELCUST");
		vault.saveEndpoint(deleteCustomer);

		// No alias at all - the common real-world shape for a Bruno-imported endpoint (SPRINT XT02B
		// acceptance correction): only a name and a numeric id, both of which RUN <TAB> must also complete.
		ApiEndpoint userByUserName = new ApiEndpoint(version.getId(), null, "userByUserName", "GET", "${baseUrl}/api/users/${userName}", 2);
		vault.saveEndpoint(userByUserName);
		this.userByUserNameId = userByUserName.getId();

		catalog = new ApiCatalogService(vault);
		completion = new CompletionService(() -> Set.of("CUSTOMER_ID", "CUSTOMER_TENANT", "CUSTOMER_REGION", "PATH"));
	}

	private List<String> values(List<CompletionCandidate> candidates) {
		return candidates.stream().map(CompletionCandidate::getValue).toList();
	}

	@Test
	void connectApiCompletesConfiguredApiIds() throws BroadSQLException {
		vault.createApiWithDefaultVersion(new ApiDefinition("CRM"));
		List<CompletionCandidate> result = completion.complete(List.of("CONNECT", "API", ""), 2, null, catalog, null);
		Assertions.assertTrue(values(result).containsAll(List.of(API_ID, "CRM")), values(result).toString());
	}

	@Test
	void runBareShowsMethodsAndAliasesExpandedToCanonicalUrl() {
		List<CompletionCandidate> result = completion.complete(List.of("RUN", ""), 1, API_ID, catalog, null);
		List<String> vals = values(result);
		Assertions.assertTrue(vals.contains("GET"), vals.toString());
		Assertions.assertTrue(vals.contains("/api/customer/:id"), "alias CUST must expand to its canonical URL, not stay as CUST: " + vals);
	}

	@Test
	void runAliasPrefixExpandsToCanonicalUrlNotTheAliasItself() {
		List<CompletionCandidate> result = completion.complete(List.of("RUN", "CUST"), 1, API_ID, catalog, null);
		Assertions.assertEquals(List.of("/api/customer/:id"), values(result));
	}

	@Test
	void runNamePrefixExpandsToCanonicalUrlEvenWithNoAliasConfigured() {
		// SPRINT XT02B acceptance correction: RUN userByUserName<TAB> must expand the same way an alias
		// would, since most imported endpoints never have an alias set at all.
		List<CompletionCandidate> result = completion.complete(List.of("RUN", "userByUserName"), 1, API_ID, catalog, null);
		Assertions.assertEquals(List.of("/api/users/:userName"), values(result));
	}

	@Test
	void runIdPrefixExpandsToCanonicalUrlTheSameWayAliasAndNameDo() {
		List<CompletionCandidate> result = completion.complete(List.of("RUN", String.valueOf(userByUserNameId)), 1, API_ID, catalog, null);
		Assertions.assertEquals(List.of("/api/users/:userName"), values(result));
	}

	@Test
	void runPartialUrlCompletesMatchingEndpointPaths() {
		// Two endpoints (GET and DELETE) share this exact canonical path shape, so both are legitimately
		// offered (with different descriptions distinguishing them by method) - the same replacement
		// value appearing twice is expected, not a bug.
		List<CompletionCandidate> result = completion.complete(List.of("RUN", "/api/cu"), 1, API_ID, catalog, null);
		Assertions.assertEquals(2, result.size(), result.toString());
		Assertions.assertTrue(values(result).stream().allMatch("/api/customer/:id"::equals), values(result).toString());
	}

	@Test
	void runQueryKeyCompletionListsConfiguredParameterNames() {
		List<CompletionCandidate> result = completion.complete(List.of("RUN", "/api/customer/:id?"), 1, API_ID, catalog, null);
		List<String> vals = values(result);
		Assertions.assertTrue(vals.contains("/api/customer/:id?expand="), vals.toString());
		Assertions.assertTrue(vals.contains("/api/customer/:id?showall="), vals.toString());
	}

	@Test
	void runQueryKeyCompletionExcludesAnAlreadyUsedParameter() {
		List<CompletionCandidate> result = completion.complete(List.of("RUN", "/api/customer/:id?expand=mail&"), 1, API_ID, catalog, null);
		List<String> vals = values(result);
		Assertions.assertTrue(vals.stream().noneMatch(v -> v.contains("&expand=")), "expand was already used and is not repeatable: " + vals);
		Assertions.assertTrue(vals.contains("/api/customer/:id?expand=mail&showall="), vals.toString());
	}

	@Test
	void runQueryValueCompletionListsAllowedValues() {
		List<CompletionCandidate> result = completion.complete(List.of("RUN", "/api/customer/:id?expand="), 1, API_ID, catalog, null);
		Assertions.assertEquals(List.of(
				"/api/customer/:id?expand=mail",
				"/api/customer/:id?expand=orders",
				"/api/customer/:id?expand=profile"), values(result));
	}

	@Test
	void runQueryValueCompletionRespectsAnAlreadyTypedPrefix() {
		List<CompletionCandidate> result = completion.complete(List.of("RUN", "/api/customer/:id?expand=or"), 1, API_ID, catalog, null);
		Assertions.assertEquals(List.of("/api/customer/:id?expand=orders"), values(result));
	}

	@Test
	void runWithAnExplicitMethodStillCompletesTheUrlOnly() {
		List<CompletionCandidate> result = completion.complete(List.of("RUN", "DELETE", "/api/cu"), 2, API_ID, catalog, null);
		Assertions.assertFalse(result.isEmpty());
		Assertions.assertTrue(values(result).stream().allMatch("/api/customer/:id"::equals), values(result).toString());
	}

	@Test
	void syntaxCompletesAliasesNotExpanded() {
		List<CompletionCandidate> result = completion.complete(List.of("SYNTAX", "CU"), 1, API_ID, catalog, null);
		Assertions.assertEquals(List.of("CUST"), values(result), "SYNTAX/HELP keep the alias as the final argument - never expand it");
	}

	@Test
	void helpCompletesCommandKeywordsAndEndpointAliases() {
		List<CompletionCandidate> result = completion.complete(List.of("HELP", "C"), 1, API_ID, catalog, List.of("CONNECT", "CONFIG", "SHOW"));
		List<String> vals = values(result);
		Assertions.assertTrue(vals.contains("CONNECT"), vals.toString());
		Assertions.assertTrue(vals.contains("CONFIG"), vals.toString());
		Assertions.assertTrue(vals.contains("CUST"), vals.toString());
		Assertions.assertFalse(vals.contains("SHOW"), "SHOW does not start with C, must not be suggested: " + vals);
	}

	@Test
	void environmentVariableCompletionSuggestsNamesOnlyNeverValues() {
		List<CompletionCandidate> result = completion.complete(List.of("RUN", "/api/customer/${ENV:CUS"), 1, API_ID, catalog, null);
		List<String> vals = values(result);
		Assertions.assertTrue(vals.contains("/api/customer/${ENV:CUSTOMER_ID}"), vals.toString());
		Assertions.assertTrue(vals.contains("/api/customer/${ENV:CUSTOMER_TENANT}"), vals.toString());
		Assertions.assertTrue(vals.contains("/api/customer/${ENV:CUSTOMER_REGION}"), vals.toString());
		for (CompletionCandidate candidate : result) {
			Assertions.assertNull(candidate.getDescription(), "must never show a value alongside the name: " + candidate);
		}
	}

	@Test
	void environmentVariableCompletionWorksInsideVarToo() {
		List<CompletionCandidate> result = completion.complete(List.of("VAR", "ID=${ENV:PA"), 1, null, catalog, null);
		Assertions.assertEquals(List.of("ID=${ENV:PATH}"), values(result));
	}

	@Test
	void noActiveApiSessionYieldsNoRunCandidates() {
		List<CompletionCandidate> result = completion.complete(List.of("RUN", ""), 1, null, catalog, null);
		Assertions.assertTrue(result.isEmpty());
	}

	@Test
	void unrecognizedCommandYieldsNoCandidates() {
		List<CompletionCandidate> result = completion.complete(List.of("SELECT", "*"), 1, API_ID, catalog, null);
		Assertions.assertTrue(result.isEmpty());
	}
}
