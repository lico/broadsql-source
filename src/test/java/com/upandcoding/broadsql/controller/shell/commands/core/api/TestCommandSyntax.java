package com.upandcoding.broadsql.controller.shell.commands.core.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
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
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/** SPRINT XT02A (URL-Native API Execution), section 3.4 - {@code SYNTAX <endpoint-alias>;}. */
class TestCommandSyntax {

	private static final String API_ID = "DESK";

	private ApiDefinitionsVault vault;
	private ApiVersion version;
	private final CapturingShellConsole console = new CapturingShellConsole();

	@BeforeEach
	void setUp() throws BroadSQLException {
		vault = TestApiDefinitionsVaults.newFileBackedVault();
		version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
	}

	@AfterEach
	void tearDown() {
		ApiSessionContextHolder.clear();
	}

	private CommandSyntax newCommand() {
		CommandSyntax cmd = CommandTestSupport.create(CommandSyntax.class, console);
		cmd.setApiDefinitionsVault(vault);
		return cmd;
	}

	@Test
	void showsCanonicalUrlAndRequiredParameters() throws BroadSQLException {
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Get customer", "GET", "${baseUrl}/api/customer/${id}", 0);
		endpoint.setAlias("CUST");
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.PATH_PARAMETER, "id", "123", false));
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "Test"));

		newCommand().execute("SYNTAX CUST");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("GET /api/customer/:id"), output);
		Assertions.assertTrue(output.contains(":id"), output);
		Assertions.assertTrue(output.contains("RUN /api/customer/123;"), output);
	}

	@Test
	void unknownAliasIsReportedCleanly() throws BroadSQLException {
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "Test"));

		newCommand().execute("SYNTAX DOES_NOT_EXIST");

		Assertions.assertTrue(console.getOutput().contains("does not match any endpoint"), console.getOutput());
	}

	@Test
	void resolvesByNumericIdToo() throws BroadSQLException {
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/todos", 0);
		vault.saveEndpoint(endpoint);
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "Test"));

		newCommand().execute("SYNTAX " + endpoint.getId());

		Assertions.assertTrue(console.getOutput().contains("GET /api/todos"), console.getOutput());
	}

	@Test
	void resolvesByUniqueNameToo() throws BroadSQLException {
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/todos", 0);
		vault.saveEndpoint(endpoint);
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "Test"));

		newCommand().execute("SYNTAX findAll");

		Assertions.assertTrue(console.getOutput().contains("GET /api/todos"), console.getOutput());
	}

	@Test
	void ambiguousNameListsCandidatesInsteadOfGuessing() throws BroadSQLException {
		ApiEndpoint first = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/customers", 0);
		vault.saveEndpoint(first);
		ApiEndpoint second = new ApiEndpoint(version.getId(), null, "findAll", "GET", "${baseUrl}/api/orders", 1);
		vault.saveEndpoint(second);
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "Test"));

		newCommand().execute("SYNTAX findAll");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains(String.valueOf(first.getId())), output);
		Assertions.assertTrue(output.contains(String.valueOf(second.getId())), output);
		Assertions.assertTrue(output.toLowerCase().contains("use the id or alias"), output);
	}

	@Test
	void noActiveApiIsReportedCleanly() throws BroadSQLException {
		newCommand().execute("SYNTAX CUST");
		Assertions.assertTrue(console.getOutput().contains("No API is connected"), console.getOutput());
	}
}
