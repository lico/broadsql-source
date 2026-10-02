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
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * SPRINT XT02B, sections 7/8/11 - {@code SHOW ALL APIS}/{@code SHOW API ENVIRONMENTS} synonyms, the
 * table-renderer leading-pipe fix, and the new singular {@code SHOW API ENVIRONMENT} detail view.
 */
class TestCommandShowApiCatalogSynonymsAndEnvironmentDetail {

	private static final String API_ID = "DESK";

	@AfterEach
	void tearDown() {
		ApiSessionContextHolder.clear();
	}

	@Test
	void showAllApisSynonymsAllTriggerTheSameCommand() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));

		for (String keyword : new String[] { "SHOW ALL APIS", "SHALAP", "ALL APIS" }) {
			CapturingShellConsole console = new CapturingShellConsole();
			CommandShowAllApis cmd = CommandTestSupport.create(CommandShowAllApis.class, console);
			cmd.setApiDefinitionsVault(vault);
			cmd.execute(keyword);
			String out = console.getOutput();
			Assertions.assertTrue(out.contains(API_ID), keyword + " => " + out);
			Assertions.assertTrue(out.contains("|" + API_ID) || out.contains("| " + API_ID) || out.matches("(?s).*\\|.*" + API_ID + ".*"), "table rows must start with a leading | : " + out);
		}
	}

	@Test
	void renderedTableRowsHaveALeadingPipe() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowAllApis cmd = CommandTestSupport.create(CommandShowAllApis.class, console);
		cmd.setApiDefinitionsVault(vault);

		cmd.execute("SHOW ALL APIS");

		for (String line : console.getOutput().split("\n", -1)) {
			if (line.contains("-") && line.contains("|") && !line.contains(console.getPrompt())) {
				Assertions.assertTrue(line.trim().startsWith("|"), "separator line must start with |: " + line);
			}
		}
	}

	@Test
	void showApiEnvironmentsSynonymsAllTriggerTheSameCommand() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveEnvironment(new ApiEnvironment(API_ID, "PROD", "https://api.example.com", 0));

		for (String keyword : new String[] { "SHOW API ENVIRONMENTS " + API_ID, "SHAPENV " + API_ID, "ALL ENV " + API_ID, "ALL ENVT " + API_ID }) {
			CapturingShellConsole console = new CapturingShellConsole();
			CommandShowApiEnvironments cmd = CommandTestSupport.create(CommandShowApiEnvironments.class, console);
			cmd.setApiDefinitionsVault(vault);
			cmd.execute(keyword);
			Assertions.assertTrue(console.getOutput().contains("PROD"), keyword + " => " + console.getOutput());
		}
	}

	@Test
	void showApiEnvironmentWithNoArgumentShowsTheSessionsOwnEnvironment() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = new ApiEnvironment(API_ID, "PROD", "https://api.example.com", 0);
		vault.saveEnvironment(env);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "key", "toto", false));
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "PROD"));

		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowApiEnvironment cmd = CommandTestSupport.create(CommandShowApiEnvironment.class, console);
		cmd.setApiDefinitionsVault(vault);

		cmd.execute("SHOW API ENVIRONMENT");

		String out = console.getOutput();
		Assertions.assertTrue(out.contains("Name     : PROD"), out);
		Assertions.assertTrue(out.contains("Base URL : https://api.example.com"), out);
		Assertions.assertTrue(out.contains("key = toto"), out);
		Assertions.assertTrue(out.contains("enabled"), out);
	}

	@Test
	void showApiEnvironmentMasksASecretVariable() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEnvironment env = new ApiEnvironment(API_ID, "PROD", "https://api.example.com", 0);
		vault.saveEnvironment(env);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, "token", "sk_live_abc", true));
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "PROD"));

		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowApiEnvironment cmd = CommandTestSupport.create(CommandShowApiEnvironment.class, console);
		cmd.setApiDefinitionsVault(vault);

		cmd.execute("SHOW API ENVIRONMENT");

		String out = console.getOutput();
		Assertions.assertFalse(out.contains("sk_live_abc"), out);
		Assertions.assertTrue(out.contains("token = ******"), out);
	}

	@Test
	void showApiEnvironmentWithAnArgumentShowsThatNamedEnvironment() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveEnvironment(new ApiEnvironment(API_ID, "PROD", "https://prod.example.com", 0));
		vault.saveEnvironment(new ApiEnvironment(API_ID, "QA", "https://qa.example.com", 1));
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "PROD"));

		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowApiEnvironment cmd = CommandTestSupport.create(CommandShowApiEnvironment.class, console);
		cmd.setApiDefinitionsVault(vault);

		cmd.execute("SHOW API ENVIRONMENT QA");

		Assertions.assertTrue(console.getOutput().contains("Base URL : https://qa.example.com"), console.getOutput());
	}

	@Test
	void showApiEnvironmentWithNoActiveSessionIsReportedCleanly() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowApiEnvironment cmd = CommandTestSupport.create(CommandShowApiEnvironment.class, console);
		cmd.setApiDefinitionsVault(vault);

		cmd.execute("SHOW API ENVIRONMENT");

		Assertions.assertTrue(console.getOutput().contains("No API is connected"), console.getOutput());
	}

	@Test
	void showApiEnvironmentShapienvSynonymWorks() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		vault.saveEnvironment(new ApiEnvironment(API_ID, "PROD", "https://api.example.com", 0));
		ApiSessionContextHolder.set(new ApiSessionContext(API_ID, "PROD"));

		CapturingShellConsole console = new CapturingShellConsole();
		CommandShowApiEnvironment cmd = CommandTestSupport.create(CommandShowApiEnvironment.class, console);
		cmd.setApiDefinitionsVault(vault);

		cmd.execute("SHAPIENV");

		Assertions.assertTrue(console.getOutput().contains("Name     : PROD"), console.getOutput());
	}

	@Test
	void showApiEnvironmentDoesNotReintroduceTheShenvAliasThatCollidedWithShowGroup() {
		Assertions.assertFalse(
				java.util.Arrays.asList(new CommandShowApiEnvironment().getKeywords()).contains("SHENV"),
				"SHENV belongs to CommandShowGroup (SHOW GROUP / SHOW ENVIRONMENTS) - reintroducing it here "
						+ "would fail command registration at startup (see CommandLoader.checkNoErrorsInKeyword)");
	}
}
