package com.upandcoding.broadsql.dao;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandSyncTypeCatalog;
import com.upandcoding.broadsql.dao.model.TypeDefinition;

/**
 * The {@code TYPE} catalog of the CDF BroadSQL ships agrees with the canonical catalog in the code
 * ({@link CommandSyncTypeCatalog#recognizedTypes()}) and with the packaged README, and every driver bundled in the
 * JAR can be loaded. The shipped {@code Oracle} row had an empty {@code DRIVER}, so a new Oracle connection could not
 * be saved ("No JDBC driver class is known for database type 'Oracle'"), and the {@code DERBY} rows named driver
 * classes that the bundled Derby 10.17 no longer contains.
 */
class TestShippedTypeCatalog {

	private static final Path SHIPPED_CDF = Path.of("src", "main", "resources", "release-template", "conf", "ConnectionsDefinitionFile.cdf.mv.db");
	private static final String PASSWORD = "clipper8AD";

	/** The databases whose driver is bundled in the JAR (pom.xml): their type must name a driver class on the classpath. */
	private static final Set<String> BUNDLED = Set.of("H2", "PostgreSQL", "HSQL", "SQLite", "DERBY Embedded");

	@TempDir
	Path dir;
	private final Map<String, String> activeDrivers = new LinkedHashMap<>();

	@BeforeEach
	void readTheShippedCatalog() throws Exception {
		Files.copy(SHIPPED_CDF, dir.resolve("C.mv.db"), StandardCopyOption.REPLACE_EXISTING);
		try (Connection conn = DriverManager.getConnection("jdbc:h2:" + dir.resolve("C") + ";CIPHER=AES", DatabaseDefinitionsVault.H2_ADMIN, PASSWORD + " " + PASSWORD);
				Statement stat = conn.createStatement(); ResultSet rs = stat.executeQuery("SELECT ID, DRIVER FROM TYPE WHERE STATUS_ID='ACTIVE' ORDER BY ID")) {
			while (rs.next()) {
				activeDrivers.put(rs.getString(1), rs.getString(2));
			}
		}
		Assertions.assertFalse(activeDrivers.isEmpty());
	}

	@Test
	void everyShippedTypeHasADriver() {
		activeDrivers.forEach((type, driver) -> Assertions.assertTrue(driver != null && !driver.isBlank(), "type " + type + " has no driver"));
	}

	@Test
	void theShippedOracleTypeUsesTheCanonicalDriver() {
		Assertions.assertEquals("oracle.jdbc.OracleDriver", activeDrivers.get("Oracle"));
	}

	@Test
	void everyTypeWithAKnownDriverIsShippedWithThatDriver() {
		for (TypeDefinition type : CommandSyncTypeCatalog.recognizedTypes()) {
			if (type.getDriver() != null && !type.getDriver().isBlank()) {
				Assertions.assertEquals(type.getDriver(), activeDrivers.get(type.getId()), "type " + type.getId());
			}
		}
	}

	@Test
	void theDriverOfEveryBundledDatabaseLoads() throws Exception {
		for (String type : BUNDLED) {
			String driver = activeDrivers.get(type);
			Assertions.assertNotNull(driver, type);
			Assertions.assertTrue(java.sql.Driver.class.isAssignableFrom(Class.forName(driver)), type + ": " + driver);
		}
	}

	@Test
	void thePackagedReadmeListsTheSameDrivers() throws Exception {
		String readme = Files.readString(Path.of("deploy", "README.txt"), StandardCharsets.ISO_8859_1);
		Matcher m = Pattern.compile("(?m)^- ([A-Za-z0-9 -]+): ([A-Za-z0-9_.]+Driver|org\\.sqlite\\.JDBC)\\s*$").matcher(readme);
		int listed = 0;
		while (m.find()) {
			listed++;
			Assertions.assertEquals(activeDrivers.get(m.group(1)), m.group(2), "README driver of " + m.group(1));
		}
		Assertions.assertTrue(listed >= 15, "the README lists the default types, found " + listed);
		Assertions.assertFalse(readme.contains("oracle.jdbc.driver.OracleDriver"), "one Oracle driver class everywhere");
	}
}
