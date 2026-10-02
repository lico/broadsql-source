package com.upandcoding.broadsql.controller.shell.commands.core.api;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandTestSupport;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.LastApiExecutionResultHolder;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.ApiSessionVariablesHolder;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.auth.TestLocalHttpServer;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * SPRINT XT02A (URL-Native API Execution): {@code RUN [HTTP_METHOD] <relative-api-url> [TABLE|RAW];}.
 * Fully replaces this class's earlier id/alias-based grammar (API Quality and UX Consolidation
 * sprint, 2026-09-14) - see {@link CommandRun}'s own Javadoc for the breaking-change rationale.
 * {@link CommandApiExecuteEndpoint} (the retired, hidden {@code EXECUTE API ENDPOINT} keyword) keeps
 * its own, separate tests unchanged - it shares no code with this class any more.
 */
class TestCommandRun {

	private static final String API_ID = "DESK";

	private TestLocalHttpServer server;
	private ApiDefinitionsVault vault;
	private ApiVersion version;
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws IOException, BroadSQLException {
		server = new TestLocalHttpServer();
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() {
		server.close();
		LastApiExecutionResultHolder.set(null);
		ApiSessionContextHolder.clear();
		ApiSessionVariablesHolder.clearAll();
	}

	private CommandRun newCommand() {
		CommandRun cmd = CommandTestSupport.create(CommandRun.class, console);
		cmd.setApiDefinitionsVault(vault);
		return cmd;
	}

	private void connect(String environmentName) throws BroadSQLException {
		ApiEnvironment environment = new ApiEnvironment(API_ID, environmentName, null, 0);
		vault.setEnvironmentBaseUrl(environment, server.baseUrl());
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, environmentName));
	}

	/** Same as {@link #connect(String)}, but returns the environment so a test can attach {@code ApiAttribute} variables to it. */
	private ApiEnvironment connectAndGetEnvironment(String environmentName) throws BroadSQLException {
		ApiEnvironment environment = new ApiEnvironment(API_ID, environmentName, null, 0);
		vault.setEnvironmentBaseUrl(environment, server.baseUrl());
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, environmentName));
		return vault.getEnvironmentsForApi(API_ID).stream().filter(e -> environmentName.equals(e.getName())).findFirst().orElseThrow();
	}

	private ApiEndpoint endpoint(String method, String path) throws BroadSQLException {
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, method + " " + path, method, path, 0);
		vault.saveEndpoint(endpoint);
		return endpoint;
	}

	@Test
	void refusesWithoutAnActiveApiContext() throws BroadSQLException {
		newCommand().execute("RUN /api/customer/123");
		Assertions.assertTrue(console.getOutput().contains("No API is connected"), console.getOutput());
	}

	@Test
	void getIsTheDefaultMethod() throws BroadSQLException {
		server.setRoute("/api/customer/123", 200, "{\"status\":\"ok\"}");
		endpoint("GET", "${baseUrl}/api/customer/${id}");
		connect("Test");

		newCommand().execute("RUN /api/customer/123");

		Assertions.assertEquals(1, server.countRequestsTo("/api/customer/123"));
		Assertions.assertTrue(console.getOutput().contains("HTTP 200"), console.getOutput());
	}

	@Test
	void explicitMethodSelectsTheMatchingEndpointAmongSeveral() throws BroadSQLException {
		server.setRoute("/api/customer/123", 200, "{\"status\":\"ok\"}");
		endpoint("GET", "${baseUrl}/api/customer/${id}");
		endpoint("DELETE", "${baseUrl}/api/customer/${id}");
		connect("Test");

		newCommand().execute("RUN DELETE /api/customer/123");

		Assertions.assertEquals(1, server.countRequestsTo("/api/customer/123"));
		Assertions.assertTrue(console.getOutput().contains("Endpoint: DELETE"), console.getOutput());
	}

	@Test
	void bareAliasIsRejectedNotExecuted() throws BroadSQLException {
		ApiEndpoint ep = endpoint("GET", "${baseUrl}/api/customer/${id}");
		ep.setAlias("CUST");
		vault.saveEndpoint(ep);
		connect("Test");

		newCommand().execute("RUN CUST");

		Assertions.assertTrue(console.getOutput().contains("is an endpoint reference, not a URL"), console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("Use TAB to expand"), console.getOutput());
		Assertions.assertEquals(0, server.countRequestsTo("/api/customer/123"));
	}

	@Test
	void bareEndpointNameWithoutAnAliasIsRejectedWithAnActionableDiagnostic() throws BroadSQLException {
		// SPRINT XT02B acceptance correction: most imported endpoints (e.g. from Bruno) have no alias set
		// at all - only an auto-derived name - so the same diagnostic must recognize a bare NAME too, not
		// only an alias.
		ApiEndpoint ep = endpoint("GET", "${baseUrl}/api/users/${userName}");
		ep.setName("userByUserName");
		vault.saveEndpoint(ep);
		connect("Test");

		newCommand().execute("RUN userByUserName");

		Assertions.assertTrue(console.getOutput().contains("is an endpoint reference, not a URL"), console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("Use TAB to expand"), console.getOutput());
		Assertions.assertEquals(0, server.countRequestsTo("/api/users/123"));
	}

	@Test
	void unknownTokenIsAUsageError() throws BroadSQLException {
		connect("Test");

		newCommand().execute("RUN DOES_NOT_EXIST");

		Assertions.assertTrue(console.getOutput().contains("Usage:"), console.getOutput());
	}

	@Test
	void noMatchingEndpointIsReportedCleanly() throws BroadSQLException {
		connect("Test");

		newCommand().execute("RUN /api/does/not/exist");

		Assertions.assertTrue(console.getOutput().contains("No endpoint matches") || console.getOutput().contains("endpoint matches"), console.getOutput());
	}

	@Test
	void literalPathValueIsUsedDirectly() throws BroadSQLException {
		server.setRoute("/api/customer/456", 200, "{\"status\":\"ok\"}");
		endpoint("GET", "${baseUrl}/api/customer/${id}");
		connect("Test");

		newCommand().execute("RUN /api/customer/456");

		Assertions.assertEquals(1, server.countRequestsTo("/api/customer/456"));
	}

	@Test
	void placeholderResolvesFromSessionVar() throws BroadSQLException {
		server.setRoute("/api/customer/789", 200, "{\"status\":\"ok\"}");
		endpoint("GET", "${baseUrl}/api/customer/${id}");
		connect("Test");
		ApiSessionVariablesHolder.set("id", "789");

		newCommand().execute("RUN /api/customer/:id");

		Assertions.assertEquals(1, server.countRequestsTo("/api/customer/789"));
	}

	@Test
	void placeholderResolvesFromPersistedValueWhenNoSessionVar() throws BroadSQLException {
		server.setRoute("/api/customer/123", 200, "{\"status\":\"ok\"}");
		ApiEndpoint ep = endpoint("GET", "${baseUrl}/api/customer/${id}");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(ep.getId()), ApiAttributeKind.PATH_PARAMETER, "id", "123", false));
		connect("Test");

		newCommand().execute("RUN /api/customer/:id");

		Assertions.assertEquals(1, server.countRequestsTo("/api/customer/123"));
	}

	@Test
	void sessionVarOverridesThePersistedValue() throws BroadSQLException {
		server.setRoute("/api/customer/456", 200, "{\"status\":\"ok\"}");
		ApiEndpoint ep = endpoint("GET", "${baseUrl}/api/customer/${id}");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(ep.getId()), ApiAttributeKind.PATH_PARAMETER, "id", "123", false));
		connect("Test");
		ApiSessionVariablesHolder.set("ID", "456");

		newCommand().execute("RUN /api/customer/:id");

		Assertions.assertEquals(1, server.countRequestsTo("/api/customer/456"));
		Assertions.assertEquals(0, server.countRequestsTo("/api/customer/123"));
	}

	@Test
	void placeholderResolvesFromEndpointDefaultWhenNoVarOrPersistedValue() throws BroadSQLException {
		server.setRoute("/api/customer/10", 200, "{\"status\":\"ok\"}");
		ApiEndpoint ep = endpoint("GET", "${baseUrl}/api/customer/${id}");
		ApiAttribute meta = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(ep.getId()), ApiAttributeKind.PATH_PARAMETER, "id", null, false);
		meta.setDefaultValue("10");
		vault.saveAttribute(meta);
		connect("Test");

		newCommand().execute("RUN /api/customer/:id");

		Assertions.assertEquals(1, server.countRequestsTo("/api/customer/10"));
	}

	@Test
	void missingRequiredPlaceholderIsAClearError() throws BroadSQLException {
		endpoint("GET", "${baseUrl}/api/customer/${id}");
		connect("Test");

		newCommand().execute("RUN /api/customer/:id");

		Assertions.assertTrue(console.getOutput().contains("Missing required parameter 'id'"), console.getOutput());
	}

	/**
	 * SPRINT XT02B acceptance correction (release blocker): a real Bruno-imported endpoint's stored path
	 * can legitimately use Bruno's own native {@code :name} colon-style path-parameter syntax (never
	 * converted to {@code ${name}} by the importer - see {@code ApiPathTemplate}'s own class javadoc),
	 * distinct from the {@code ${baseUrl}/api/customer/${id}} style every other test in this class uses.
	 * The exact reported failure: {@code VAR siteId=163;} then {@code RUN /api/sites/:siteId;} sent the
	 * literal, unresolved {@code :siteId} text to the remote server instead of {@code 163}.
	 */
	@Test
	void colonStyleStoredPathParameterResolvesCorrectlyNotAsALiteral() throws BroadSQLException {
		server.setRoute("/api/sites/163", 200, "{\"status\":\"ok\"}");
		endpoint("GET", "${baseUrl}/api/sites/:siteId");
		connect("Test");
		ApiSessionVariablesHolder.set("siteId", "163");

		newCommand().execute("RUN /api/sites/:siteId");

		Assertions.assertEquals(1, server.countRequestsTo("/api/sites/163"));
		Assertions.assertEquals(0, server.countRequestsTo("/api/sites/:siteId"));
		// The displayed "Endpoint: ..." line legitimately echoes the endpoint's own descriptive name (set
		// by the endpoint() test helper to include its raw stored path, colon segment and all) - only the
		// resolved request-URL line matters here, not the whole console transcript.
		Assertions.assertTrue(console.getOutput().contains("GET " + server.baseUrl() + "/api/sites/163"),
				"expected the resolved URL to be displayed, got:\n" + console.getOutput());
	}

	@Test
	void queryParametersAreSentAsGiven() throws BroadSQLException {
		server.setRoute("/api/customer/1", 200, "{\"status\":\"ok\"}");
		endpoint("GET", "${baseUrl}/api/customer/${id}");
		connect("Test");

		newCommand().execute("RUN /api/customer/1?expand=mail&showall=true");

		TestLocalHttpServer.RecordedRequest last = server.lastRequestTo("/api/customer/1");
		Assertions.assertNotNull(last);
		String query = last.uri.getQuery();
		Assertions.assertTrue(query.contains("expand=mail"), query);
		Assertions.assertTrue(query.contains("showall=true"), query);
	}

	@Test
	void queryParameterOutsideAllowedValuesIsRejected() throws BroadSQLException {
		ApiEndpoint ep = endpoint("GET", "${baseUrl}/api/customer/${id}");
		ApiAttribute expand = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(ep.getId()), ApiAttributeKind.QUERY_PARAMETER, "expand", null, false);
		expand.setAllowedValues("mail|orders|profile");
		vault.saveAttribute(expand);
		connect("Test");

		newCommand().execute("RUN /api/customer/1?expand=foobar");

		Assertions.assertTrue(console.getOutput().contains("Invalid value 'foobar'"), console.getOutput());
		Assertions.assertEquals(0, server.countRequestsTo("/api/customer/1"));
	}

	@Test
	void requiredQueryParameterWithNoUrlValueIsAutoAttachedFromItsDefault() throws BroadSQLException {
		server.setRoute("/api/customer/1", 200, "{\"status\":\"ok\"}");
		ApiEndpoint ep = endpoint("GET", "${baseUrl}/api/customer/${id}");
		ApiAttribute tenant = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(ep.getId()), ApiAttributeKind.QUERY_PARAMETER, "tenant", null, false);
		tenant.setRequired(true);
		tenant.setDefaultValue("acme");
		vault.saveAttribute(tenant);
		connect("Test");

		newCommand().execute("RUN /api/customer/1");

		TestLocalHttpServer.RecordedRequest last = server.lastRequestTo("/api/customer/1");
		Assertions.assertNotNull(last);
		Assertions.assertTrue(last.uri.getQuery() != null && last.uri.getQuery().contains("tenant=acme"), String.valueOf(last.uri.getQuery()));
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02A corrective pass (16/09/2026): explicit required-vs-optional query-parameter
	// resolution proof, verified against the exact semantics requested:
	//   required, unresolvable -> ERROR (never silently omitted from the outgoing request)
	//   optional, unresolvable -> simply omitted
	//   optional, resolvable via persisted/default even though omitted from the URL -> still auto-attached
	// ------------------------------------------------------------------------------------------

	@Test
	void requiredQueryParameterWithNoUrlValueAndNoResolutionIsAClearErrorNeverSilentlyOmitted() throws BroadSQLException {
		ApiEndpoint ep = endpoint("GET", "${baseUrl}/api/customer/${id}");
		ApiAttribute tenant = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(ep.getId()), ApiAttributeKind.QUERY_PARAMETER, "tenant", null, false);
		tenant.setRequired(true); // no persisted value, no default, no session VAR either
		vault.saveAttribute(tenant);
		connect("Test");

		newCommand().execute("RUN /api/customer/1");

		Assertions.assertTrue(console.getOutput().contains("Missing required parameter 'tenant'"), console.getOutput());
		Assertions.assertEquals(0, server.countRequestsTo("/api/customer/1"), "a required-but-unresolvable parameter must never let the request go out without it");
	}

	@Test
	void optionalQueryParameterWithNoUrlValueAndNoResolutionIsSimplyOmitted() throws BroadSQLException {
		server.setRoute("/api/customer/1", 200, "{\"status\":\"ok\"}");
		ApiEndpoint ep = endpoint("GET", "${baseUrl}/api/customer/${id}");
		ApiAttribute expand = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(ep.getId()), ApiAttributeKind.QUERY_PARAMETER, "expand", null, false);
		expand.setRequired(false); // no persisted value, no default, no session VAR either
		vault.saveAttribute(expand);
		connect("Test");

		newCommand().execute("RUN /api/customer/1");

		TestLocalHttpServer.RecordedRequest last = server.lastRequestTo("/api/customer/1");
		Assertions.assertNotNull(last);
		Assertions.assertTrue(last.uri.getQuery() == null || !last.uri.getQuery().contains("expand"),
				"an optional, unresolvable parameter must simply be omitted, not block the request: " + last.uri.getQuery());
	}

	@Test
	void optionalQueryParameterWithAPersistedValueIsStillAutoAttachedEvenThoughOptional() throws BroadSQLException {
		// Corrective pass: an earlier version of this engine only ever auto-attached a REQUIRED
		// omitted parameter; the corrected semantics apply the same VAR -> persisted -> default chain
		// to an optional one too - "optional" only changes what happens if none of those resolve.
		server.setRoute("/api/customer/1", 200, "{\"status\":\"ok\"}");
		ApiEndpoint ep = endpoint("GET", "${baseUrl}/api/customer/${id}");
		ApiAttribute locale = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(ep.getId()), ApiAttributeKind.QUERY_PARAMETER, "locale", "en-US", false);
		locale.setRequired(false);
		vault.saveAttribute(locale);
		connect("Test");

		newCommand().execute("RUN /api/customer/1");

		TestLocalHttpServer.RecordedRequest last = server.lastRequestTo("/api/customer/1");
		Assertions.assertNotNull(last);
		Assertions.assertTrue(last.uri.getQuery() != null && last.uri.getQuery().contains("locale=en-US"), String.valueOf(last.uri.getQuery()));
	}

	@Test
	void optionalQueryParameterResolvesFromSessionVarEvenThoughOmittedFromTheUrl() throws BroadSQLException {
		server.setRoute("/api/customer/1", 200, "{\"status\":\"ok\"}");
		ApiEndpoint ep = endpoint("GET", "${baseUrl}/api/customer/${id}");
		ApiAttribute locale = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(ep.getId()), ApiAttributeKind.QUERY_PARAMETER, "locale", null, false);
		locale.setRequired(false);
		vault.saveAttribute(locale);
		connect("Test");
		ApiSessionVariablesHolder.set("locale", "fr-FR");

		newCommand().execute("RUN /api/customer/1");

		TestLocalHttpServer.RecordedRequest last = server.lastRequestTo("/api/customer/1");
		Assertions.assertNotNull(last);
		Assertions.assertTrue(last.uri.getQuery() != null && last.uri.getQuery().contains("locale=fr-FR"), String.valueOf(last.uri.getQuery()));
	}

	@Test
	void undefinedEnvironmentVariableFailsExplicitly() throws BroadSQLException {
		endpoint("GET", "${baseUrl}/api/probe");
		connect("Test");

		newCommand().execute("RUN /api/probe?marker=${ENV:BROADSQL_XT02A_DOES_NOT_EXIST}");

		Assertions.assertTrue(console.getOutput().contains("Environment variable 'BROADSQL_XT02A_DOES_NOT_EXIST' is not defined"), console.getOutput());
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02B, section 3/4: API environment variables (CONFIG API's Environment tab) must be
	// usable during RUN, both as literal ${name}/{{name}} text in the URL and as the source of a
	// :name placeholder, between session VAR and the endpoint's own persisted value in precedence.
	// ------------------------------------------------------------------------------------------

	@Test
	void dollarBraceVariableInTheRunUrlResolvesFromTheApiEnvironment() throws BroadSQLException {
		server.setRoute("/api/senders/list", 200, "{\"status\":\"ok\"}");
		endpoint("GET", "${baseUrl}/api/senders/list");
		ApiEnvironment env = connectAndGetEnvironment("Test");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "key", "toto", false));

		newCommand().execute("RUN /api/senders/list?apikey=${key}");

		TestLocalHttpServer.RecordedRequest last = server.lastRequestTo("/api/senders/list");
		Assertions.assertNotNull(last);
		Assertions.assertTrue(last.uri.getQuery() != null && last.uri.getQuery().contains("apikey=toto"), String.valueOf(last.uri.getQuery()));
	}

	@Test
	void mustacheVariableInTheRunUrlResolvesFromTheApiEnvironmentTheSameAsDollarBrace() throws BroadSQLException {
		server.setRoute("/api/senders/list", 200, "{\"status\":\"ok\"}");
		endpoint("GET", "${baseUrl}/api/senders/list");
		ApiEnvironment env = connectAndGetEnvironment("Test");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "key", "toto", false));

		newCommand().execute("RUN /api/senders/list?apikey={{key}}");

		TestLocalHttpServer.RecordedRequest last = server.lastRequestTo("/api/senders/list");
		Assertions.assertNotNull(last);
		Assertions.assertTrue(last.uri.getQuery() != null && last.uri.getQuery().contains("apikey=toto"), String.valueOf(last.uri.getQuery()));
	}

	@Test
	void anUndefinedDollarBraceVariableInTheRunUrlFailsExplicitly() throws BroadSQLException {
		endpoint("GET", "${baseUrl}/api/senders/list");
		connect("Test");

		newCommand().execute("RUN /api/senders/list?apikey=${doesNotExist}");

		Assertions.assertTrue(console.getOutput().contains("Unable to resolve") && console.getOutput().contains("doesNotExist"), console.getOutput());
	}

	@Test
	void colonPlaceholderResolvesFromTheApiEnvironmentVariableWhenNoSessionVarIsSet() throws BroadSQLException {
		server.setRoute("/api/customer/toto", 200, "{\"status\":\"ok\"}");
		endpoint("GET", "${baseUrl}/api/customer/${id}");
		ApiEnvironment env = connectAndGetEnvironment("Test");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "id", "toto", false));

		newCommand().execute("RUN /api/customer/:id");

		Assertions.assertEquals(1, server.countRequestsTo("/api/customer/toto"));
	}

	@Test
	void sessionVarStillOverridesTheApiEnvironmentVariableForAColonPlaceholder() throws BroadSQLException {
		server.setRoute("/api/customer/456", 200, "{\"status\":\"ok\"}");
		endpoint("GET", "${baseUrl}/api/customer/${id}");
		ApiEnvironment env = connectAndGetEnvironment("Test");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "id", "toto", false));
		ApiSessionVariablesHolder.set("id", "456");

		newCommand().execute("RUN /api/customer/:id");

		Assertions.assertEquals(1, server.countRequestsTo("/api/customer/456"));
		Assertions.assertEquals(0, server.countRequestsTo("/api/customer/toto"));
	}

	@Test
	void apiEnvironmentVariableTakesPrecedenceOverTheEndpointsOwnPersistedValueForAColonPlaceholder() throws BroadSQLException {
		server.setRoute("/api/customer/toto", 200, "{\"status\":\"ok\"}");
		ApiEndpoint ep = endpoint("GET", "${baseUrl}/api/customer/${id}");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(ep.getId()), ApiAttributeKind.PATH_PARAMETER, "id", "123", false));
		ApiEnvironment env = connectAndGetEnvironment("Test");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "id", "toto", false));

		newCommand().execute("RUN /api/customer/:id");

		Assertions.assertEquals(1, server.countRequestsTo("/api/customer/toto"));
		Assertions.assertEquals(0, server.countRequestsTo("/api/customer/123"));
	}

	@Test
	void trailingRawForcesRawRendering() throws BroadSQLException {
		server.setRoute("/api/list", 200, "[{\"id\":1}]");
		endpoint("GET", "${baseUrl}/api/list");
		connect("Test");

		newCommand().execute("RUN /api/list RAW");

		Assertions.assertFalse(console.getOutput().contains("|"), "RAW must skip tabular rendering: " + console.getOutput());
	}

	@Test
	void aMalformedRunDoesNotClearAPreviouslyHeldResult() throws BroadSQLException {
		server.setRoute("/api/probe", 200, "{\"status\":\"ok\"}");
		endpoint("GET", "${baseUrl}/api/probe");
		connect("Test");

		newCommand().execute("RUN /api/probe");
		Assertions.assertNotNull(LastApiExecutionResultHolder.get());

		newCommand().execute("RUN"); // no token at all - a usage error, not a resolution failure

		Assertions.assertNotNull(LastApiExecutionResultHolder.get(), "a pure usage error must not clear an existing held result");
	}

	@Test
	void aFailedRunWithNoActiveApiContextClearsAnyPreviouslyHeldResult() throws BroadSQLException {
		server.setRoute("/api/probe", 200, "{\"status\":\"ok\"}");
		endpoint("GET", "${baseUrl}/api/probe");
		connect("Test");

		newCommand().execute("RUN /api/probe");
		Assertions.assertNotNull(LastApiExecutionResultHolder.get(), "the successful RUN must populate the holder");

		ApiSessionContextHolder.clear(); // equivalent to DISCONNECT API
		console = new CapturingShellConsole();

		newCommand().execute("RUN /api/probe");

		Assertions.assertTrue(console.getOutput().contains("No API is connected"), console.getOutput());
		Assertions.assertNull(LastApiExecutionResultHolder.get(),
				"a RUN attempt that fails only because there is no active API context must still clear the "
						+ "previously-held result - PULL API RESULT must have nothing stale to export");
	}
}
