package com.upandcoding.broadsql.controller.shell.commands.core.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandTestSupport;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * {@code SHOW ENDPOINT <id>;} - API Quality and UX Consolidation sprint: the single-endpoint detail
 * view. A pure global id lookup (endpoint ids are a single {@code AUTO_INCREMENT} primary key, unique
 * across every API) - no active {@code CONNECT API} session or {@code API} clause required.
 */
class TestCommandShowEndpoint {

	private static final String API_ID = "DEMO";

	private CapturingShellConsole console;
	private ApiDefinitionsVault vault;

	private CommandShowEndpoint newCommand() throws BroadSQLException {
		console = new CapturingShellConsole();
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		CommandShowEndpoint cmd = CommandTestSupport.create(CommandShowEndpoint.class, console);
		cmd.setApiDefinitionsVault(vault);
		return cmd;
	}

	@Test
	void showsCoreFieldsWithNoActiveSessionRequired() throws BroadSQLException {
		CommandShowEndpoint cmd = newCommand();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpointGroup group = new ApiEndpointGroup(version.getId(), null, "Products", 0);
		vault.saveEndpointGroup(group);
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), group.getId(), "Get product", "GET", "${baseUrl}/products", 0);
		endpoint.setAlias("GETPRODUCT");
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "limit", "10", false));
		ApiAttribute disabledExpand = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "expand", "full", false);
		disabledExpand.setEnabled(false);
		vault.saveAttribute(disabledExpand);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.HEADER, "Accept", "application/json", false));

		cmd.execute("SHOW ENDPOINT " + endpoint.getId());

		String out = console.getOutput();
		Assertions.assertTrue(out.contains("ID          : " + endpoint.getId()), out);
		Assertions.assertTrue(out.contains("API         : Demo API") || out.contains("API         : " + API_ID), out);
		Assertions.assertTrue(out.contains("Folder      : Products"), out);
		Assertions.assertTrue(out.contains("Name        : Get product"), out);
		Assertions.assertTrue(out.contains("Alias       : GETPRODUCT"), out);
		Assertions.assertTrue(out.contains("Method      : GET"), out);
		// The composed effective URL includes the enabled 'limit' param but omits the disabled 'expand'.
		Assertions.assertTrue(out.contains("URL         : ${baseUrl}/products?limit=10"), out);
		Assertions.assertFalse(out.contains("expand=full"), out);
		Assertions.assertTrue(out.contains("limit : 10   enabled"), out);
		Assertions.assertTrue(out.contains("expand : full   disabled"), out);
		Assertions.assertTrue(out.contains("Accept : application/json"), out);
		Assertions.assertTrue(out.contains("Authentication: Inherited"), out);
	}

	@Test
	void masksASecretQueryParameterValue() throws BroadSQLException {
		CommandShowEndpoint cmd = newCommand();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Search", "GET", "${baseUrl}/search", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "apiKey", "sk_live_abc123", true));

		cmd.execute("SHOW ENDPOINT " + endpoint.getId());

		String out = console.getOutput();
		Assertions.assertFalse(out.contains("sk_live_abc123"), "a secret-flagged value must never appear in plain text: " + out);
		Assertions.assertTrue(out.contains("apiKey : ******"), out);
	}

	@Test
	void masksASecretHeaderValue() throws BroadSQLException {
		CommandShowEndpoint cmd = newCommand();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Search", "GET", "${baseUrl}/search", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.HEADER, "Authorization", "Bearer sk_live_abc123", true));

		cmd.execute("SHOW ENDPOINT " + endpoint.getId());

		String out = console.getOutput();
		Assertions.assertFalse(out.contains("sk_live_abc123"), out);
		Assertions.assertTrue(out.contains("Authorization : ******"), out);
	}

	@Test
	void aHeaderReferencingAVariableIsShownAsIs() throws BroadSQLException {
		CommandShowEndpoint cmd = newCommand();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Search", "GET", "${baseUrl}/search", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.HEADER, "Authorization", "Bearer ${token}", false));

		cmd.execute("SHOW ENDPOINT " + endpoint.getId());

		Assertions.assertTrue(console.getOutput().contains("Authorization : Bearer ${token}"), console.getOutput());
	}

	@Test
	void showsTheRequestBodyInFull() throws BroadSQLException {
		CommandShowEndpoint cmd = newCommand();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Create", "POST", "${baseUrl}/things", 0);
		endpoint.setBodyMode("json");
		endpoint.setBodyContent("{\n  \"name\": \"${name}\"\n}");
		vault.saveEndpoint(endpoint);

		cmd.execute("SHOW ENDPOINT " + endpoint.getId());

		String out = console.getOutput();
		Assertions.assertTrue(out.contains("Body (json)"), out);
		Assertions.assertTrue(out.contains("\"name\": \"${name}\""), out);
	}

	@Test
	void invalidIdIsAUsageError() throws BroadSQLException {
		CommandShowEndpoint cmd = newCommand();
		cmd.execute("SHOW ENDPOINT notanumber");
		Assertions.assertTrue(console.getOutput().contains("not a valid endpoint id"), console.getOutput());
	}

	@Test
	void unknownIdIsReportedCleanly() throws BroadSQLException {
		CommandShowEndpoint cmd = newCommand();
		cmd.execute("SHOW ENDPOINT 999999");
		Assertions.assertTrue(console.getOutput().contains("not found"), console.getOutput());
	}

	@Test
	void noArgumentIsAUsageError() throws BroadSQLException {
		CommandShowEndpoint cmd = newCommand();
		cmd.execute("SHOW ENDPOINT");
		Assertions.assertTrue(console.getOutput().contains("Usage"), console.getOutput());
	}

	@Test
	void resolvesByAliasWithinAnActiveSession() throws BroadSQLException {
		CommandShowEndpoint cmd = newCommand();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/todos", 0);
		endpoint.setAlias("PINGMAIL");
		vault.saveEndpoint(endpoint);
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "Test"));
		try {
			cmd.execute("SHOW ENDPOINT PINGMAIL");
			Assertions.assertTrue(console.getOutput().contains("Alias       : PINGMAIL"), console.getOutput());
		} finally {
			ApiSessionContextHolder.clear();
		}
	}

	@Test
	void resolvesByUniqueNameWithinAnActiveSession() throws BroadSQLException {
		CommandShowEndpoint cmd = newCommand();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/todos", 0);
		vault.saveEndpoint(endpoint);
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "Test"));
		try {
			cmd.execute("SHOW ENDPOINT findAll");
			Assertions.assertTrue(console.getOutput().contains("Name        : findAll"), console.getOutput());
		} finally {
			ApiSessionContextHolder.clear();
		}
	}

	@Test
	void aNameWithoutAnActiveSessionIsReportedCleanly() throws BroadSQLException {
		CommandShowEndpoint cmd = newCommand();
		vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		cmd.execute("SHOW ENDPOINT findAll");
		Assertions.assertTrue(console.getOutput().contains("no API is connected"), console.getOutput());
	}

	@Test
	void anAmbiguousNameListsCandidatesInsteadOfGuessing() throws BroadSQLException {
		CommandShowEndpoint cmd = newCommand();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint first = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/customers", 0);
		vault.saveEndpoint(first);
		ApiEndpoint second = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/orders", 1);
		vault.saveEndpoint(second);
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "Test"));
		try {
			cmd.execute("SHOW ENDPOINT findAll");
			String out = console.getOutput();
			Assertions.assertTrue(out.contains(String.valueOf(first.getId())), out);
			Assertions.assertTrue(out.contains(String.valueOf(second.getId())), out);
			Assertions.assertTrue(out.toLowerCase().contains("use the id or alias"), out);
		} finally {
			ApiSessionContextHolder.clear();
		}
	}

	@Test
	void outputNeverStartsOrCarriesTheShellPrompt() throws BroadSQLException {
		CommandShowEndpoint cmd = newCommand();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/todos", 0);
		vault.saveEndpoint(endpoint);

		cmd.execute("SHOW ENDPOINT " + endpoint.getId());

		for (String line : console.getOutput().split("\n", -1)) {
			Assertions.assertFalse(line.contains(console.getPrompt()), "no output line may carry the shell prompt: " + line);
		}
	}
}
