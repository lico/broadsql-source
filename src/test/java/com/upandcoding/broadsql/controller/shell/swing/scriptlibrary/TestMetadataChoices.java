package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/** Where the Database Group and Environment selectors take their values and relations from. */
class TestMetadataChoices {

	/** MYWORLD has DEV and PROD; SALES has PROD; HR has no connection. */
	static DatabaseDefinitionsVault vault() {
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault();
		vault.getGroups().addAll(List.of("SALES", "MYWORLD", "HR"));
		vault.getEnvironments().addAll(List.of("PROD", "DEV", "QA"));
		connection(vault, "WORLD_DEV", "MYWORLD", "DEV");
		connection(vault, "WORLD_PROD", "MYWORLD", "PROD");
		connection(vault, "SALES_PROD", "SALES", "PROD");
		connection(vault, "STANDALONE", null, null);
		return vault;
	}

	private static void connection(DatabaseDefinitionsVault vault, String id, String group, String environment) {
		DatabaseDefinition connection = new DatabaseDefinition(id);
		connection.setDatabaseGroup(group);
		connection.setEnvironment(environment);
		vault.getPlatforms().put(id, connection);
	}

	@Test
	void theOfferedValuesAreTheIdsSaveAccepts() {
		DatabaseDefinitionsVault vault = vault();

		MetadataChoices choices = MetadataChoices.fromVault(vault);

		Assertions.assertEquals(List.of("HR", "MYWORLD", "SALES"), choices.groups());
		Assertions.assertEquals(List.of("DEV", "PROD", "QA"), choices.environments());
		Assertions.assertEquals(Set.copyOf(choices.groups()), Set.copyOf(vault.getGroups()), "the same set MetadataIntegrityContext.fromVault checks");
	}

	@Test
	void relationsComeFromTheConnections() {
		MetadataChoices choices = MetadataChoices.fromVault(vault());

		Assertions.assertEquals(Set.of("DEV", "PROD"), Set.copyOf(choices.environmentsCompatibleWith(List.of("myworld"))));
		Assertions.assertEquals(Set.of("DEV", "PROD"), Set.copyOf(choices.environmentsCompatibleWith(List.of("MYWORLD", "SALES"))), "a union over the selection");
		Assertions.assertEquals(Set.of("MYWORLD", "SALES"), Set.copyOf(choices.groupsCompatibleWith(List.of("PROD"))));
		Assertions.assertTrue(choices.groupsCompatibleWith(List.of("QA")).isEmpty());
		Assertions.assertNull(choices.groupsCompatibleWith(List.of()), "no selection: no context");
		Assertions.assertNull(choices.environmentsCompatibleWith(null));
	}

	@Test
	void withoutAVaultNothingIsKnown() {
		MetadataChoices choices = MetadataChoices.fromVault(null);

		Assertions.assertNull(choices.groups());
		Assertions.assertNull(choices.environments());
		Assertions.assertTrue(choices.groupsCompatibleWith(List.of("PROD")).isEmpty(), "no Connection known: nothing is marked compatible");
	}
}
