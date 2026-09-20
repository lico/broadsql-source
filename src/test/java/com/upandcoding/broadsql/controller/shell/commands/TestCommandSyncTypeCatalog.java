package com.upandcoding.broadsql.controller.shell.commands;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandSyncTypeCatalog;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.TypeDefinition;

/**
 * Covers {@code SYNC TYPE CATALOG} end to end through the command layer - the vault-level mechanics
 * ({@code getTypeDetails}/{@code syncTypeCatalog}) are covered in
 * {@code TestDatabaseDefinitionsVaultTypeCatalog}; this only checks the command reports the "before"
 * catalog and the outcome, and stays hidden from {@code HELP}.
 */
class TestCommandSyncTypeCatalog {

	@Test
	void printsTheExistingCatalogThenAddsMissingRecognizedTypesAndReportsThem() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		CapturingShellConsole console = new CapturingShellConsole();
		CommandSyncTypeCatalog cmd = CommandTestSupport.create(CommandSyncTypeCatalog.class, console);
		cmd.setDatabaseConnectionsVault(vault);

		cmd.execute("SYNC TYPE CATALOG");

		String output = console.getOutput();
		Assertions.assertTrue(output.contains("Current TYPE catalog (1 row(s))"), "expected the pre-sync catalog to be printed first, got:\n" + output);
		Assertions.assertTrue(output.contains("H2"), "expected the pre-existing H2 row in the printed catalog, got:\n" + output);
		Assertions.assertTrue(output.contains("Added 15 missing type(s)"),
				"expected 15 of the 21 recognized types to be newly added (H2 already existed, 5 have no known driver), got:\n" + output);
		Assertions.assertTrue(output.contains("Refreshed 1 existing type(s)"), "expected the pre-existing H2 row to be refreshed, got:\n" + output);
		Assertions.assertTrue(output.contains("Skipped 5 recognized type(s) with no known driver class"), "expected the 5 no-driver types to be reported as skipped, got:\n" + output);
		for (String noDriverId : new String[] { "Intersys", "JDBC-ODBC Bridge", "InstantDB", "Cloudscape", "Pointbase" }) {
			Assertions.assertTrue(output.contains(noDriverId), "expected '" + noDriverId + "' to be named among the skipped types, got:\n" + output);
		}

		List<TypeDefinition> details = vault.getTypeDetails();
		Assertions.assertEquals(16, details.size(), "the 5 no-driver types must never get a row - 21 recognized minus 5 = 16");
		Assertions.assertTrue(details.stream().anyMatch(t -> "DERBY Client".equals(t.getId()) && "Client".equals(t.getMode())));
		Assertions.assertTrue(details.stream().anyMatch(t -> "DERBY Embedded".equals(t.getId()) && "Embedded".equals(t.getMode())));
		Assertions.assertTrue(details.stream().noneMatch(t -> "Intersys".equals(t.getId())), "'Intersys' has no known driver and must not exist in TYPE");
	}

	@Test
	void isHiddenFromHelp() {
		CommandSyncTypeCatalog cmd = new CommandSyncTypeCatalog();
		Assertions.assertTrue(cmd.isHidden(), "must stay hidden from HELP and the generated command reference");
	}
}
