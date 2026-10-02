package com.upandcoding.broadsql.dao.api.tabular;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * SPRINT XT02-7B, sections 14/15/16/17/18/19/20/21: the table model behind the single canonical
 * {@code SHOW ENDPOINTS [API <apiId>]} command. Columns reduced to ID/VERB/FOLDER/NAME/ALIAS (API
 * Quality and UX Consolidation sprint, section 3) - PATH/EXECUTABLE were dropped from this catalog
 * table (PATH stays an internal sort tiebreaker only; EXECUTABLE's underlying logic remains covered
 * by {@code TestApiExecutionPolicy}, now that it is no longer surfaced as a column here).
 */
class TestApiEndpointListingBuilder {

	private static final String API_ID = "DESK";

	private ApiDefinitionsVault newVaultWithFixture() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpointGroup mail = new ApiEndpointGroup(version.getId(), null, "Email", 0);
		vault.saveEndpointGroup(mail);

		ApiEndpoint ping = new ApiEndpoint(version.getId(), mail.getId(), "Check email service", "GET", "/api/rest/emails/v1/ping", 0);
		ping.setAlias("PINGMAIL");
		vault.saveEndpoint(ping);

		ApiEndpoint sender = new ApiEndpoint(version.getId(), mail.getId(), "Get mail server", "GET", "/api/rest/emails/v1/senders/{id}", 1);
		vault.saveEndpoint(sender);

		ApiEndpoint errors = new ApiEndpoint(version.getId(), null, "listMailsError", "POST", "/api/rest/emails/v1/sent/errors", 2);
		vault.saveEndpoint(errors);

		return vault;
	}

	@Test
	void includesAllRequiredColumns() throws BroadSQLException {
		ApiResultTable table = ApiEndpointListingBuilder.build(newVaultWithFixture(), API_ID, null, null);
		Assertions.assertEquals(List.of("ID", "VERB", "FOLDER", "NAME", "ALIAS"), table.getColumnNames());
		Assertions.assertEquals(3, table.getRows().size());
	}

	@Test
	void folderColumnReflectsTheFullFolderPath() throws BroadSQLException {
		ApiResultTable table = ApiEndpointListingBuilder.build(newVaultWithFixture(), API_ID, "GET", "ping");
		Assertions.assertEquals(1, table.getRows().size());
		Map<String, String> row = table.getRows().get(0);
		Assertions.assertEquals("Email", row.get("FOLDER"));
		Assertions.assertEquals("PINGMAIL", row.get("ALIAS"));
	}

	@Test
	void topLevelEndpointHasABlankFolder() throws BroadSQLException {
		ApiResultTable table = ApiEndpointListingBuilder.build(newVaultWithFixture(), API_ID, "POST", null);
		Assertions.assertEquals(1, table.getRows().size());
		Assertions.assertEquals("", table.getRows().get(0).get("FOLDER"));
	}

	@Test
	void verbFilterIsCaseInsensitiveAndExact() throws BroadSQLException {
		ApiDefinitionsVault vault = newVaultWithFixture();
		Assertions.assertEquals(2, ApiEndpointListingBuilder.build(vault, API_ID, "get", null).getRows().size());
		Assertions.assertEquals(1, ApiEndpointListingBuilder.build(vault, API_ID, "Post", null).getRows().size());
		Assertions.assertEquals(0, ApiEndpointListingBuilder.build(vault, API_ID, "DELETE", null).getRows().size());
	}

	@Test
	void matchFilterSearchesFolderPathNameAndAlias() throws BroadSQLException {
		ApiDefinitionsVault vault = newVaultWithFixture();
		Assertions.assertEquals(3, ApiEndpointListingBuilder.build(vault, API_ID, null, "mail").getRows().size(),
				"matches folder name 'Email' (ping), endpoint name 'Get mail server' (sender), and endpoint name 'listMailsError' (errors)");
		Assertions.assertEquals(1, ApiEndpointListingBuilder.build(vault, API_ID, null, "PINGMAIL").getRows().size(), "matches the alias");
		Assertions.assertEquals(1, ApiEndpointListingBuilder.build(vault, API_ID, null, "senders").getRows().size(), "matches the path");
	}

	@Test
	void combinedFilterIsLogicalAnd() throws BroadSQLException {
		ApiDefinitionsVault vault = newVaultWithFixture();
		ApiResultTable table = ApiEndpointListingBuilder.build(vault, API_ID, "GET", "ping");
		Assertions.assertEquals(1, table.getRows().size());
		Assertions.assertEquals(0, ApiEndpointListingBuilder.build(vault, API_ID, "POST", "ping").getRows().size());
	}

	@Test
	void zeroMatchesProducesAnEmptyTableRatherThanAnError() throws BroadSQLException {
		ApiResultTable table = ApiEndpointListingBuilder.build(newVaultWithFixture(), API_ID, null, "does-not-exist-anywhere");
		Assertions.assertTrue(table.getRows().isEmpty());
		Assertions.assertEquals(List.of("ID", "VERB", "FOLDER", "NAME", "ALIAS"), table.getColumnNames());
	}

	@Test
	void sortOrderIsDeterministicByFolderThenPathThenVerbThenId() throws BroadSQLException {
		ApiResultTable table = ApiEndpointListingBuilder.build(newVaultWithFixture(), API_ID, null, null);
		List<String> folders = table.getRows().stream().map(r -> r.get("FOLDER")).toList();
		// Top-level (blank folder) sorts before "Email" alphabetically.
		Assertions.assertEquals("", folders.get(0));
		Assertions.assertEquals("Email", folders.get(1));
		Assertions.assertEquals("Email", folders.get(2));
	}

	@Test
	void unrecognizedFilterClauseIsRejected() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ApiEndpointListingBuilder.parseFilters(new String[] { "BOGUS", "value" }, 0));
		Assertions.assertTrue(ex.getMessage().contains("Unrecognized clause"));
	}

	@Test
	void filtersMayAppearInEitherOrder() throws BroadSQLException {
		ApiEndpointListingBuilder.Filters a = ApiEndpointListingBuilder.parseFilters(new String[] { "VERB", "GET", "MATCH", "ping" }, 0);
		Assertions.assertEquals("GET", a.verb);
		Assertions.assertEquals("ping", a.match);

		ApiEndpointListingBuilder.Filters b = ApiEndpointListingBuilder.parseFilters(new String[] { "MATCH", "ping", "VERB", "GET" }, 0);
		Assertions.assertEquals("GET", b.verb);
		Assertions.assertEquals("ping", b.match);
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02B, section 9: SHOW ENDPOINTS GET; (a bare HTTP method) is now the canonical form;
	// the legacy VERB GET; form remains accepted for backward compatibility.
	// ------------------------------------------------------------------------------------------

	@Test
	void bareHttpMethodTokenIsTheCanonicalVerbFilter() throws BroadSQLException {
		ApiEndpointListingBuilder.Filters filters = ApiEndpointListingBuilder.parseFilters(new String[] { "GET" }, 0);
		Assertions.assertEquals("GET", filters.verb);
		Assertions.assertNull(filters.match);
	}

	@Test
	void bareHttpMethodTokenCombinesWithMatch() throws BroadSQLException {
		ApiEndpointListingBuilder.Filters filters = ApiEndpointListingBuilder.parseFilters(new String[] { "GET", "MATCH", "ping" }, 0);
		Assertions.assertEquals("GET", filters.verb);
		Assertions.assertEquals("ping", filters.match);
	}

	@Test
	void legacyVerbFormStillWorksAlongsideTheCanonicalBareForm() throws BroadSQLException {
		ApiEndpointListingBuilder.Filters legacy = ApiEndpointListingBuilder.parseFilters(new String[] { "VERB", "GET" }, 0);
		Assertions.assertEquals("GET", legacy.verb);
	}

	@Test
	void bareMethodFilterActuallyFiltersTheBuiltTable() throws BroadSQLException {
		ApiDefinitionsVault vault = newVaultWithFixture();
		ApiEndpointListingBuilder.Filters filters = ApiEndpointListingBuilder.parseFilters(new String[] { "POST" }, 0);
		ApiResultTable table = ApiEndpointListingBuilder.build(vault, API_ID, filters.verb, filters.match);
		Assertions.assertEquals(1, table.getRows().size());
		Assertions.assertEquals("POST", table.getRows().get(0).get("VERB"));
	}

	@Test
	void aNonMethodUnrecognizedTokenIsStillRejected() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ApiEndpointListingBuilder.parseFilters(new String[] { "BOGUS" }, 0));
		Assertions.assertTrue(ex.getMessage().contains("Unrecognized clause"));
	}
}
