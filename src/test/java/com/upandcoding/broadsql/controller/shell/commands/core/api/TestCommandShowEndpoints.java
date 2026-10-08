package com.upandcoding.broadsql.controller.shell.commands.core.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandTestSupport;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * {@code SHOW ENDPOINTS [API <apiId>] [VERB <verb>] [MATCH <keyword>]} - API Quality and UX
 * Consolidation sprint: the single canonical endpoint-listing command. Without {@code API <apiId>},
 * it is the active-{@code CONNECT API}-session shorthand (SPRINT XT02-7B); with it, it is
 * self-contained and requires no active session - this is the former, now-retired
 * {@code SHOW API ENDPOINTS <apiId>}'s behavior, migrated here rather than duplicated.
 */
class TestCommandShowEndpoints {

	private static final String API_ID = "DEMO";

	@AfterEach
	void tearDown() {
		ApiSessionContextHolder.clear();
	}

	private CommandShowEndpoints newCommand(CapturingShellConsole console, ApiDefinitionsVault vault) {
		CommandShowEndpoints cmd = CommandTestSupport.create(CommandShowEndpoints.class, console);
		cmd.setApiDefinitionsVault(vault);
		return cmd;
	}

	@Test
	void refusesWithoutAnActiveApiContext() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowEndpoints cmd = newCommand(console, TestApiDefinitionsVaults.newFileBackedVault());

		cmd.execute("SHOW ENDPOINTS");

		Assertions.assertTrue(console.getOutput().contains("No active API context"), console.getOutput());
	}

	@Test
	void listsTheActiveApisEndpointsWithoutRepeatingTheApiId() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Check email service", "GET", "/ping", 0);
		endpoint.setAlias("PINGMAIL");
		vault.saveEndpoint(endpoint);
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "PROD"));

		CommandShowEndpoints cmd = newCommand(console, vault);
		cmd.execute("SHOW ENDPOINTS");

		Assertions.assertTrue(console.getOutput().contains("PINGMAIL"), console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("1 endpoint"), console.getOutput());
	}

	@Test
	void appliesVerbAndMatchFilters() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveEndpoint(new ApiEndpoint(version.getId(), null, "Check email service", "GET", "/ping", 0));
		vault.saveEndpoint(new ApiEndpoint(version.getId(), null, "Create user", "POST", "/users", 1));
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "PROD"));

		CommandShowEndpoints cmd = newCommand(console, vault);
		cmd.execute("SHOW ENDPOINTS MATCH ping VERB GET");

		Assertions.assertTrue(console.getOutput().contains("1 endpoint"), console.getOutput());
	}

	/**
	 * Corrective patch, Codex finding 3: the active {@link ApiSessionContext} can outlive the API it
	 * points at being deactivated through {@code CONFIG API} in the meantime - deactivating an API never
	 * clears an already-established context. {@code SHOW ENDPOINTS} must revalidate on every call rather
	 * than trusting the context blindly.
	 */
	@Test
	void refusesWhenTheActiveApiHasBeenDeactivatedSinceConnecting() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveEndpoint(new ApiEndpoint(version.getId(), null, "Check email service", "GET", "/ping", 0));
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "PROD"));

		// Deactivated after the API session context was already established (e.g. via CONFIG API in
		// another window) - the context itself is untouched by this.
		vault.deactivateApi(API_ID);

		CommandShowEndpoints cmd = newCommand(console, vault);
		cmd.execute("SHOW ENDPOINTS");

		Assertions.assertTrue(console.getOutput().contains("is inactive"),
				"a deactivated active-context API must be refused as inactive, not silently listed: " + console.getOutput());
		Assertions.assertFalse(console.getOutput().contains("Check email service"),
				"no endpoint of a deactivated API may be listed through the stale context: " + console.getOutput());
	}

	@Test
	void refusesWhenTheActiveApiNoLongerExists() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		// A context pointing at an API id that was never (or is no longer) defined in this vault at all.
		ApiSessionContextHolder.set(new ApiSessionContext("GHOST", "PROD"));

		CommandShowEndpoints cmd = newCommand(console, vault);
		cmd.execute("SHOW ENDPOINTS");

		Assertions.assertTrue(console.getOutput().contains("no longer exists"), console.getOutput());
	}

	// ------------------------------------------------------------------------------------------
	// API Quality and UX Consolidation sprint: SHOW ENDPOINTS API <apiId> - self-contained, no
	// active session required. Migrated from the retired CommandShowApiEndpoints' own test class.
	// ------------------------------------------------------------------------------------------

	@Test
	void withAnExplicitApiNoActiveSessionIsRequired() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Check email service", "GET", "/ping", 0);
		endpoint.setAlias("PINGMAIL");
		vault.saveEndpoint(endpoint);

		CommandShowEndpoints cmd = newCommand(console, vault);
		cmd.execute("SHOW ENDPOINTS API " + API_ID);

		Assertions.assertTrue(console.getOutput().contains("PINGMAIL"), console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("1 endpoint"), console.getOutput());
	}

	@Test
	void explicitApiRefusesAnUnknownApi() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();

		CommandShowEndpoints cmd = newCommand(console, vault);
		cmd.execute("SHOW ENDPOINTS API BOGUS");

		Assertions.assertTrue(console.getOutput().contains("not found"), console.getOutput());
	}

	@Test
	void explicitApiAppliesVerbAndMatchFilters() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveEndpoint(new ApiEndpoint(version.getId(), null, "Check email service", "GET", "/ping", 0));
		vault.saveEndpoint(new ApiEndpoint(version.getId(), null, "Create user", "POST", "/users", 1));

		CommandShowEndpoints cmd = newCommand(console, vault);
		cmd.execute("SHOW ENDPOINTS API " + API_ID + " VERB GET MATCH ping");

		Assertions.assertTrue(console.getOutput().contains("1 endpoint"), console.getOutput());
	}

	@Test
	void explicitApiReportsNoMatchWithoutAnErrorWhenTheFilterMatchesNothing() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveEndpoint(new ApiEndpoint(version.getId(), null, "Check email service", "GET", "/ping", 0));

		CommandShowEndpoints cmd = newCommand(console, vault);
		cmd.execute("SHOW ENDPOINTS API " + API_ID + " VERB DELETE");

		Assertions.assertTrue(console.getOutput().contains("No endpoint"), console.getOutput());
	}

	@Test
	void apiWithNoValueIsAUsageError() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();

		CommandShowEndpoints cmd = newCommand(console, vault);
		cmd.execute("SHOW ENDPOINTS API");

		Assertions.assertTrue(console.getOutput().contains("API requires a value"), console.getOutput());
	}

	// ------------------------------------------------------------------------------------------
	// API Quality and UX Consolidation sprint, section 3: only ID/VERB/FOLDER/NAME/ALIAS - no URL
	// (PATH), no EXECUTABLE.
	// ------------------------------------------------------------------------------------------

	@Test
	void tableHasOnlyTheRequiredColumnsNoPathNoExecutable() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Check email service", "GET", "/ping", 0);
		endpoint.setAlias("PINGMAIL");
		vault.saveEndpoint(endpoint);

		CommandShowEndpoints cmd = newCommand(console, vault);
		cmd.execute("SHOW ENDPOINTS API " + API_ID);

		String out = console.getOutput();
		Assertions.assertTrue(out.contains("ID"), out);
		Assertions.assertTrue(out.contains("VERB"), out);
		Assertions.assertTrue(out.contains("FOLDER"), out);
		Assertions.assertTrue(out.contains("NAME"), out);
		Assertions.assertTrue(out.contains("ALIAS"), out);
		Assertions.assertFalse(out.contains("PATH"), "PATH/URL column must be gone: " + out);
		Assertions.assertFalse(out.contains("EXECUTABLE"), "EXECUTABLE column must be gone: " + out);
		Assertions.assertFalse(out.contains("/ping"), "the URL value itself must no longer appear in this catalog table: " + out);
	}

	@Test
	void longFolderAndNameValuesAreEllipsizedForDisplay() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		String longName = "A very long endpoint name that should be truncated for display purposes only";
		vault.saveEndpoint(new ApiEndpoint(version.getId(), null, longName, "GET", "/x", 0));

		CommandShowEndpoints cmd = newCommand(console, vault);
		cmd.execute("SHOW ENDPOINTS API " + API_ID);

		String out = console.getOutput();
		Assertions.assertFalse(out.contains(longName), "the full untruncated name must not appear verbatim in a catalog table: " + out);
		Assertions.assertTrue(out.contains("..."), "an ellipsis ('...') is expected for a truncated cell: " + out);
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02B, section 9: SHOW ENDPOINTS GET; (bare method, canonical) alongside the legacy
	// SHOW ENDPOINTS VERB GET; form. Section 7: command synonyms.
	// ------------------------------------------------------------------------------------------

	@Test
	void bareMethodTokenIsTheCanonicalVerbFilterEndToEnd() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveEndpoint(new ApiEndpoint(version.getId(), null, "Check email service", "GET", "/ping", 0));
		vault.saveEndpoint(new ApiEndpoint(version.getId(), null, "Create user", "POST", "/users", 1));
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "PROD"));

		CommandShowEndpoints cmd = newCommand(console, vault);
		cmd.execute("SHOW ENDPOINTS GET");

		Assertions.assertTrue(console.getOutput().contains("1 endpoint"), console.getOutput());
	}

	@Test
	void synonymsAllTriggerTheSameCommand() throws BroadSQLException {
		for (String keyword : new String[] { "SHOW ENDPOINTS", "ENDPOINTS", "ALL ENDPOINTS", "SHENDS" }) {
			CapturingShellConsole console = new CapturingShellConsole();
			ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
			ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
			vault.saveEndpoint(new ApiEndpoint(version.getId(), null, "Check email service", "GET", "/ping", 0));
			ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "PROD"));

			CommandShowEndpoints cmd = newCommand(console, vault);
			cmd.execute(keyword);

			Assertions.assertTrue(console.getOutput().contains("1 endpoint"), keyword + " => " + console.getOutput());
			ApiSessionContextHolder.clear();
		}
	}
}
