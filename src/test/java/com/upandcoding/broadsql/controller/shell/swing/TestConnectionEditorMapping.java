package com.upandcoding.broadsql.controller.shell.swing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * The connection editor (CONFIG) from its fields to the CDF, without a display: the Driver field filled from the
 * selected type ({@link JSettingsFrame#driverForType}, what the type list's listener does), the definition built
 * from the form ({@link JSettingsFrame#connectionFromForm}, what Save and Test use), saved through the real vault on
 * a copy of the shipped CDF. The DRIVER defect sat exactly on this path: the form had the value, the save lost it.
 */
class TestConnectionEditorMapping {

	private static final String SHIPPED_CDF_PASSWORD = "clipper8AD";
	private static final Path SHIPPED_CDF = Path.of("src", "main", "resources", "release-template", "conf", "ConnectionsDefinitionFile.cdf.mv.db");

	@TempDir
	Path dir;

	private DatabaseDefinitionsVault vault() throws Exception {
		Path copy = dir.resolve("ConnectionsDefinitionFile.cdf.mv.db");
		if (!Files.exists(copy)) {
			Files.copy(SHIPPED_CDF, copy, StandardCopyOption.REPLACE_EXISTING);
		}
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault(dir.resolve("ConnectionsDefinitionFile.cdf").toString(), SHIPPED_CDF_PASSWORD);
		vault.load();
		return vault;
	}

	private String storedDriver(String id) throws Exception {
		try (Connection conn = DriverManager.getConnection("jdbc:h2:" + dir.resolve("ConnectionsDefinitionFile.cdf") + ";CIPHER=AES", DatabaseDefinitionsVault.H2_ADMIN,
				SHIPPED_CDF_PASSWORD + " " + SHIPPED_CDF_PASSWORD); PreparedStatement stat = conn.prepareStatement("SELECT DRIVER FROM CONNECTIONS WHERE ID=?")) {
			stat.setString(1, id);
			try (ResultSet rs = stat.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	@Test
	void theDriverFieldShowsTheSelectedTypesDriver() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		Assertions.assertEquals("org.h2.Driver", JSettingsFrame.driverForType(vault.getDbDrivers(), "H2"));
		Assertions.assertEquals("org.postgresql.Driver", JSettingsFrame.driverForType(vault.getDbDrivers(), "PostgreSQL"));
		Assertions.assertEquals("", JSettingsFrame.driverForType(vault.getDbDrivers(), "-- Select type"), "no type selected");
		Assertions.assertEquals("", JSettingsFrame.driverForType(vault.getDbDrivers(), null));
	}

	@Test
	void everyFormFieldReachesTheDefinition() {
		DatabaseDefinition c = JSettingsFrame.connectionFromForm("WORLD", "Sample World Database", "org.h2.Driver", "jdbc:h2:./samples/WorldDB", "H2", "sa",
				"pw".toCharArray(), "SALES", "LOCAL", "a comment");
		Assertions.assertEquals("WORLD", c.getId());
		Assertions.assertEquals("Sample World Database", c.getDbName());
		Assertions.assertEquals("org.h2.Driver", c.getDbDriver());
		Assertions.assertEquals("jdbc:h2:./samples/WorldDB", c.getUrl());
		Assertions.assertEquals("H2", c.getDbType());
		Assertions.assertEquals("sa", c.getUserName());
		Assertions.assertTrue("pw".equals(c.getUserPassword()), "password not carried over");
		Assertions.assertEquals("SALES", c.getDatabaseGroup());
		Assertions.assertEquals("LOCAL", c.getEnvironment());
		Assertions.assertEquals("a comment", c.getComment());
		Assertions.assertNull(JSettingsFrame.connectionFromForm(" ", "n", "d", "u", "H2", "sa", new char[0], JSettingsFrame.NO_GROUP_LABEL, "LOCAL", ""), "no ID, no definition");
		Assertions.assertNull(JSettingsFrame.connectionFromForm("X", "n", "d", "u", "H2", "sa", new char[0], JSettingsFrame.NO_GROUP_LABEL, "LOCAL", "").getDatabaseGroup(),
				"the no-group entry means no Database Group");
	}

	@Test
	void theDriverShownInTheFormIsTheDriverStored() throws Exception {
		DatabaseDefinitionsVault vault = vault();
		String driverShown = JSettingsFrame.driverForType(vault.getDbDrivers(), "H2");
		DatabaseDefinition fromForm = JSettingsFrame.connectionFromForm("WORLD_FORM", "Sample World Database", driverShown, "jdbc:h2:./samples/WorldDB", "H2", "sa",
				new char[0], JSettingsFrame.NO_GROUP_LABEL, vault.resolveLocalEnvironmentId(), "");
		fromForm.setStatus(DatabaseDefinition.STATUS_ACTIVE); // as Save does

		vault.saveDatabaseDefinition(fromForm);

		Assertions.assertEquals("org.h2.Driver", storedDriver("WORLD_FORM"));
		DatabaseDefinition reopened = vault().getDatabaseConnection("WORLD_FORM");
		Assertions.assertEquals("org.h2.Driver", reopened.getDbDriver(), "the Driver field when the connection is reopened");
	}

	@Test
	void aPasswordAndADifferentRetypeDoNotMatch() {
		Assertions.assertTrue(JSettingsFrame.passwordsMatch("abc".toCharArray(), "abc".toCharArray()));
		Assertions.assertTrue(JSettingsFrame.passwordsMatch(new char[0], new char[0]));
		Assertions.assertFalse(JSettingsFrame.passwordsMatch("abc".toCharArray(), "abd".toCharArray()), "a mistyped retype must be caught");
		Assertions.assertFalse(JSettingsFrame.passwordsMatch("abc".toCharArray(), new char[0]));
	}
}
