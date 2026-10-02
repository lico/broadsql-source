package com.upandcoding.broadsql.dao;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.model.UserScriptLine;

/**
 * Covers the {@code USERS_SCRIPT} CRUD methods added/fixed for docs/TODO.md item 13 ("Add the user
 * scripts in the GUI of the config Swing screens"): {@link DatabaseDefinitionsVault#saveUserScriptLines},
 * {@link DatabaseDefinitionsVault#getUserScriptLines} and the fixed {@link
 * DatabaseDefinitionsVault#getUserLoginScript} - against a real, file-backed, AES-encrypted CDF
 * ({@link TestDatabaseConnections#newFileBackedVault}), not a mock, per docs/TESTS_STRATEGY.md.
 *
 * <p>The core regression this guards: {@code getUserLoginScript} used to collect rows into a
 * {@code TreeSet<String>}, which re-sorted them alphabetically by SQL text instead of preserving
 * {@code sql_order}, and silently dropped duplicate lines. {@link
 * #preservesSqlOrderNotAlphabeticalOrder()} and {@link #keepsDuplicateLinesInsteadOfSilentlyDroppingThem()}
 * exercise exactly that.
 */
class TestDatabaseDefinitionsVaultUserScripts {

	@Test
	void savesLinesInOrderAndReadsThemBackTheSameWay() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		List<UserScriptLine> lines = Arrays.asList(new UserScriptLine(null, "SET SCHEMA HR", 0, UserScriptLine.STATUS_ACTIVE, null),
				new UserScriptLine(null, "SET ROLE READER", 0, UserScriptLine.STATUS_ACTIVE, null));

		vault.saveUserScriptLines("HRQA", lines);

		List<UserScriptLine> reloaded = vault.getUserScriptLines(vault.getFileName(), vault.getPassword(), "HRQA");
		Assertions.assertEquals(2, reloaded.size());
		Assertions.assertEquals("SET SCHEMA HR", reloaded.get(0).getSqlCommand());
		Assertions.assertEquals(1, reloaded.get(0).getSqlOrder());
		Assertions.assertEquals("SET ROLE READER", reloaded.get(1).getSqlCommand());
		Assertions.assertEquals(2, reloaded.get(1).getSqlOrder());
	}

	@Test
	void savingReplacesThePreviousScriptEntirelyRatherThanAppending() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveUserScriptLines("HRQA", Arrays.asList(new UserScriptLine(null, "SET SCHEMA OLD", 0, UserScriptLine.STATUS_ACTIVE, null)));

		vault.saveUserScriptLines("HRQA", Arrays.asList(new UserScriptLine(null, "SET SCHEMA NEW", 0, UserScriptLine.STATUS_ACTIVE, null)));

		List<UserScriptLine> reloaded = vault.getUserScriptLines(vault.getFileName(), vault.getPassword(), "HRQA");
		Assertions.assertEquals(1, reloaded.size(), "saving must replace the whole script, not append to it");
		Assertions.assertEquals("SET SCHEMA NEW", reloaded.get(0).getSqlCommand());
	}

	@Test
	void preservesSqlOrderNotAlphabeticalOrder() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		// Deliberately alphabetically reversed from sql_order, so a TreeSet<String> regression
		// (sorting by text) would be caught immediately.
		vault.saveUserScriptLines("HRQA", Arrays.asList(new UserScriptLine(null, "ZZZ FIRST", 0, UserScriptLine.STATUS_ACTIVE, null),
				new UserScriptLine(null, "AAA SECOND", 0, UserScriptLine.STATUS_ACTIVE, null)));

		List<String> activeScript = vault.getUserLoginScript(vault.getFileName(), vault.getPassword(), "HRQA");

		Assertions.assertEquals(Arrays.asList("ZZZ FIRST", "AAA SECOND"), activeScript, "must run in sql_order, not alphabetical order");
	}

	@Test
	void keepsDuplicateLinesInsteadOfSilentlyDroppingThem() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveUserScriptLines("HRQA", Arrays.asList(new UserScriptLine(null, "COMMIT", 0, UserScriptLine.STATUS_ACTIVE, null),
				new UserScriptLine(null, "COMMIT", 0, UserScriptLine.STATUS_ACTIVE, null)));

		List<String> activeScript = vault.getUserLoginScript(vault.getFileName(), vault.getPassword(), "HRQA");

		Assertions.assertEquals(2, activeScript.size(), "two identical lines must both run, not be silently deduplicated");
	}

	@Test
	void loginScriptSkipsDisabledLinesButKeepsThemInGetUserScriptLines() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveUserScriptLines("HRQA", Arrays.asList(new UserScriptLine(null, "SET SCHEMA HR", 0, UserScriptLine.STATUS_ACTIVE, null),
				new UserScriptLine(null, "DROP TABLE OBSOLETE", 0, UserScriptLine.STATUS_INACTIVE, "disabled, kept for reference")));

		List<String> activeScript = vault.getUserLoginScript(vault.getFileName(), vault.getPassword(), "HRQA");
		List<UserScriptLine> allLines = vault.getUserScriptLines(vault.getFileName(), vault.getPassword(), "HRQA");

		Assertions.assertEquals(List.of("SET SCHEMA HR"), activeScript, "the disabled line must not run at connect time");
		Assertions.assertEquals(2, allLines.size(), "the management screen must still show the disabled line");
	}

	@Test
	void scriptsAreScopedPerConnectionNotSharedAcrossConnections() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveUserScriptLines("HRQA", Arrays.asList(new UserScriptLine(null, "SET SCHEMA HR", 0, UserScriptLine.STATUS_ACTIVE, null)));
		vault.saveUserScriptLines("FINDEV", Arrays.asList(new UserScriptLine(null, "SET SCHEMA FIN", 0, UserScriptLine.STATUS_ACTIVE, null)));

		Assertions.assertEquals(List.of("SET SCHEMA HR"), vault.getUserLoginScript(vault.getFileName(), vault.getPassword(), "HRQA"));
		Assertions.assertEquals(List.of("SET SCHEMA FIN"), vault.getUserLoginScript(vault.getFileName(), vault.getPassword(), "FINDEV"));
	}
}
