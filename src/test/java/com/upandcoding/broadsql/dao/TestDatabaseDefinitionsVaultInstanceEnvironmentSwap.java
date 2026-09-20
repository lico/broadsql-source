package com.upandcoding.broadsql.dao;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Covers {@link DatabaseDefinitionsVault#backupCdfFile} and
 * {@link DatabaseDefinitionsVault#swapInstanceAndEnvironment} - the correction for the data inversion
 * documented in docs/TECHNICAL_CHANGE.md, 2026-09-05 ("Instance/Environment data swap"): every real
 * connection's {@code INSTANCE_ID} has always held a deployment-stage value and its
 * {@code ENVIRONMENT_ID} a product/application name - the opposite of what those column names say.
 *
 * <p>Seeds a connection the way the real data actually looks (instance-shaped value under
 * {@code ENVIRONMENT_ID}, environment-shaped value under {@code INSTANCE_ID} - i.e. pre-swap), plus one
 * reference row in each of {@code INSTANCE} and {@code ENVIRONMENT}. The {@code ENVIRONMENT} row is
 * inserted via raw JDBC rather than {@link DatabaseDefinitionsVault#saveEnvironment} - at the time this
 * swap ran (2026-09-05), Phase 1's Environment CRUD did not exist yet, and this test intentionally
 * reproduces that pre-swap CDF state exactly, not today's API surface - then asserts the swap relocates
 * everything to the column/table that actually matches it, and that a backup {@code .zip} exists before
 * it runs.
 *
 * <p>{@link #backsUpSuccessfullyWhileAnotherConnectionHoldsTheFileOpen} is the regression test for the
 * real failure reported against the user's live CDF: the first implementation of {@link
 * DatabaseDefinitionsVault#backupCdfFile} did a plain {@code java.nio.file.Files.copy} of
 * {@code <fileName>.mv.db}, which fails with {@code FileSystemException} on Windows whenever anything
 * else already holds the file open - exactly the situation every real invocation of {@code FIX INSTANCE
 * ENVIRONMENT SWAP} is in, since the user is necessarily connected to {@code $CDF} when running it. The
 * other tests here never hit this because {@link TestDatabaseConnections#newFileBackedVault} and every
 * vault method open-and-close their own connection per call, with nothing left holding the file open in
 * between - this test deliberately keeps a separate connection open throughout to reproduce the real
 * scenario.
 */
class TestDatabaseDefinitionsVaultInstanceEnvironmentSwap {

	@Test
	void swapsConnectionColumnsAndReferenceTablesAndBacksUpFirst() throws BroadSQLException, SQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		vault.saveGroup("DEV", "Development"); // pre-swap: a stage-shaped value, wrongly under INSTANCE
		insertRawEnvironmentRow(vault, "DESK", "Desk product"); // pre-swap: a product-shaped value, wrongly under ENVIRONMENT

		DatabaseDefinition connection = new DatabaseDefinition("DESKCONN", "H2", "org.h2.Driver", "jdbc:h2:mem:deskconn", "sa", "sa", "Desk");
		connection.setDatabaseGroup("DEV"); // pre-swap, matching real production data
		connection.setEnvironment("DESK"); // pre-swap, matching real production data
		vault.saveDatabaseDefinition(connection);

		String backupPath = vault.swapInstanceAndEnvironment();

		assertIsAValidBackupZip(backupPath);

		DatabaseDefinition reloaded = vault.getDatabaseConnection("DESKCONN");
		Assertions.assertEquals("DESK", reloaded.getDatabaseGroup(), "the product-shaped value must now be under Instance");
		Assertions.assertEquals("DEV", reloaded.getEnvironment(), "the stage-shaped value must now be under Environment");

		Assertions.assertTrue(vault.getGroups().contains("DESK"), "the INSTANCE table must now hold what used to be the ENVIRONMENT row");
		Assertions.assertFalse(vault.getGroups().contains("DEV"), "the old, wrongly-placed INSTANCE row must be gone");
		Assertions.assertTrue(vault.getEnvironments().contains("DEV"), "the ENVIRONMENT table must now hold what used to be the INSTANCE row");
		Assertions.assertFalse(vault.getEnvironments().contains("DESK"), "the old, wrongly-placed ENVIRONMENT row must be gone");
	}

	@Test
	void backsUpSuccessfullyWhileAnotherConnectionHoldsTheFileOpen() throws BroadSQLException, SQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		String connectionStr = "jdbc:h2:" + vault.getFileName() + ";CIPHER=AES";
		String aesPassword = vault.getPassword() + " " + vault.getPassword();

		try (Connection heldOpen = DriverManager.getConnection(connectionStr, "ADMIN", aesPassword)) {
			String backupPath = vault.backupCdfFile();
			assertIsAValidBackupZip(backupPath);
		}
	}

	@Test
	void backupFailsClearlyWhenTheVaultHasNoBackingFile() throws BroadSQLException {
		DatabaseDefinitionsVault vault = TestDatabaseConnections.newVault();

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, vault::backupCdfFile);
		Assertions.assertTrue(ex.getMessage().contains("not backed by a CDF file"));
	}

	private static void assertIsAValidBackupZip(String backupPath) throws SQLException {
		File backupFile = new File(backupPath);
		Assertions.assertTrue(backupFile.exists(), "a backup file must be created");
		Assertions.assertTrue(backupPath.endsWith(".zip"), "H2's BACKUP TO always produces a zip archive");
		try (ZipFile zip = new ZipFile(backupFile)) {
			Assertions.assertTrue(zip.stream().map(ZipEntry::getName).anyMatch(name -> name.endsWith(".db")),
					"the backup archive must contain the database file");
		} catch (Exception ex) {
			Assertions.fail("backup file is not a readable zip archive: " + ex.getMessage());
		}
	}

	private static void insertRawEnvironmentRow(DatabaseDefinitionsVault vault, String id, String descr) throws SQLException {
		String connectionStr = "jdbc:h2:" + vault.getFileName() + ";CIPHER=AES";
		String aesPassword = vault.getPassword() + " " + vault.getPassword();
		try (Connection conn = DriverManager.getConnection(connectionStr, "ADMIN", aesPassword);
				PreparedStatement stat = conn.prepareStatement("INSERT INTO ENVIRONMENT (ID, DESCR, STATUS_ID) VALUES (?, ?, 'ACTIVE')")) {
			stat.setString(1, id);
			stat.setString(2, descr);
			stat.executeUpdate();
			conn.commit();
		}
	}
}
