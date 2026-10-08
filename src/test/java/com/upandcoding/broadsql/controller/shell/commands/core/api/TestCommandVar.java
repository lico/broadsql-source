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
import com.upandcoding.broadsql.dao.api.ApiSessionVariablesHolder;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/** SPRINT XT02A (URL-Native API Execution), section 2.5/3.2 - {@code VAR <name>=<value>;}. */
class TestCommandVar {

	private static final String API_ID = "DESK";

	private final CapturingShellConsole console = new CapturingShellConsole();

	@AfterEach
	void tearDown() {
		ApiSessionVariablesHolder.clearAll();
		ApiSessionContextHolder.clear();
	}

	private CommandVar newCommand() {
		return CommandTestSupport.create(CommandVar.class, console);
	}

	private CommandVar newCommand(ApiDefinitionsVault vault) {
		CommandVar cmd = CommandTestSupport.create(CommandVar.class, console);
		cmd.setApiDefinitionsVault(vault);
		return cmd;
	}

	@Test
	void setsASessionVariable() throws BroadSQLException {
		newCommand().execute("VAR ID=123");
		Assertions.assertEquals("123", ApiSessionVariablesHolder.get("id"));
	}

	@Test
	void lookupIsCaseInsensitive() throws BroadSQLException {
		newCommand().execute("VAR customer_id=456");
		Assertions.assertEquals("456", ApiSessionVariablesHolder.get("CUSTOMER_ID"));
	}

	@Test
	void aQuotedValueContainingSpacesIsAccepted() throws BroadSQLException {
		newCommand().execute("VAR NAME=\"John Doe\"");
		Assertions.assertEquals("John Doe", ApiSessionVariablesHolder.get("NAME"));
	}

	@Test
	void resolvesAnEnvironmentVariableAtAssignmentTime() throws BroadSQLException {
		String name = "PATH"; // present natively on every platform this build runs on
		org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv(name) != null);
		newCommand().execute("VAR P=${ENV:" + name + "}");
		Assertions.assertEquals(System.getenv(name), ApiSessionVariablesHolder.get("P"));
	}

	@Test
	void anUndefinedEnvironmentVariableFailsExplicitly() {
		Assertions.assertThrows(BroadSQLException.class, () -> newCommand().execute("VAR X=${ENV:BROADSQL_XT02A_DOES_NOT_EXIST}"));
	}

	@Test
	void malformedAssignmentIsAUsageError() throws BroadSQLException {
		newCommand().execute("VAR NOEQUALSSIGN");
		Assertions.assertTrue(console.getOutput().contains("Usage:"), console.getOutput());
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02B, section 5: VAR ... PERSIST - upserts into the current API environment's variable
	// set, then clears any session override of the same name.
	// ------------------------------------------------------------------------------------------

	private ApiEnvironment connect(ApiDefinitionsVault vault, String environmentName) throws BroadSQLException {
		vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment environment = new ApiEnvironment(API_ID, environmentName, "https://api.example.com", 0);
		vault.saveEnvironment(environment);
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, environmentName));
		return environment;
	}

	@Test
	void persistCreatesANewEnvironmentVariableWhenNoneExistsYet() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiEnvironment env = connect(vault, "PROD");

		newCommand(vault).execute("VAR ID=123 PERSIST");

		var variables = vault.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE);
		Assertions.assertEquals(1, variables.size());
		Assertions.assertEquals("ID", variables.get(0).getName());
		Assertions.assertEquals("123", variables.get(0).getValue());
		Assertions.assertTrue(variables.get(0).isEnabled(), "a newly-persisted variable must default to enabled");
	}

	@Test
	void persistReplacesAnExistingVariablesValueByCaseInsensitiveNameWithoutResettingItsOtherFields() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiEnvironment env = connect(vault, "PROD");
		ApiAttribute existing = new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "id", "OLD", true);
		existing.setEnabled(false);
		existing.setDescription("a description that must survive");
		vault.saveAttribute(existing);

		newCommand(vault).execute("VAR ID=456 PERSIST");

		var variables = vault.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE);
		Assertions.assertEquals(1, variables.size(), "must update the existing row, not add a second one");
		ApiAttribute updated = variables.get(0);
		Assertions.assertEquals("456", updated.getValue());
		Assertions.assertTrue(updated.isSecret(), "secret flag must survive a PERSIST update");
		Assertions.assertFalse(updated.isEnabled(), "enabled flag must survive a PERSIST update");
		Assertions.assertEquals("a description that must survive", updated.getDescription());
	}

	@Test
	void persistDoesNotWipeSiblingVariablesOfTheSameEnvironment() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiEnvironment env = connect(vault, "PROD");
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "other", "unchanged", false));

		newCommand(vault).execute("VAR ID=123 PERSIST");

		var variables = vault.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE);
		Assertions.assertEquals(2, variables.size());
		Assertions.assertTrue(variables.stream().anyMatch(v -> "other".equalsIgnoreCase(v.getName()) && "unchanged".equals(v.getValue())));
	}

	@Test
	void persistClearsAnExistingSessionOverrideOfTheSameNameCaseInsensitively() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		connect(vault, "PROD");
		ApiSessionVariablesHolder.set("id", "OLD_SESSION_VALUE");

		newCommand(vault).execute("VAR ID=123 PERSIST");

		Assertions.assertFalse(ApiSessionVariablesHolder.isSet("id"), "PERSIST must clear the session override so the persisted value is what resolves next");
	}

	@Test
	void persistIsImmediatelyVisibleThroughApiVariableResolver() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiEnvironment env = connect(vault, "PROD");

		newCommand(vault).execute("VAR key=toto PERSIST");

		var resolved = new com.upandcoding.broadsql.dao.api.ApiVariableResolver(vault).resolve(API_ID, env, null, null);
		Assertions.assertEquals("toto", resolved.get("key"));
	}

	@Test
	void persistWithoutAnActiveApiSessionFailsClearly() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();

		newCommand(vault).execute("VAR ID=123 PERSIST");

		Assertions.assertTrue(console.getOutput().contains("No API is connected"), console.getOutput());
	}

	@Test
	void plainVarWithoutPersistNeverTouchesTheDatabase() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiEnvironment env = connect(vault, "PROD");

		newCommand(vault).execute("VAR ID=123");

		Assertions.assertTrue(vault.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE).isEmpty());
		Assertions.assertEquals("123", ApiSessionVariablesHolder.get("ID"));
	}
}
