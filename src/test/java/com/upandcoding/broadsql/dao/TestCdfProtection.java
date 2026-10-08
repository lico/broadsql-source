package com.upandcoding.broadsql.dao;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/** SPRINT 1005C (#169): how a URL or file name is reduced to the physical H2 database it opens. */
class TestCdfProtection {

	@TempDir
	Path tmp;

	private File base(String urlOrName) {
		return CdfProtection.h2DatabaseBase(urlOrName);
	}

	@Test
	void everySpellingOfALocalFileDatabaseGivesTheSameBase() {
		String dir = tmp.toString();
		File expected = base(dir + File.separator + "cdf");
		Assertions.assertNotNull(expected);
		Assertions.assertEquals(expected, base("jdbc:h2:" + dir + File.separator + "cdf;CIPHER=AES"));
		Assertions.assertEquals(expected, base("JDBC:H2:file:" + dir + File.separator + "cdf;IFEXISTS=TRUE"));
		Assertions.assertEquals(expected, base("jdbc:h2:" + dir + File.separator + "sub" + File.separator + ".." + File.separator + "cdf"));
		Assertions.assertEquals(expected, base("jdbc:h2:nio:" + dir + File.separator + "cdf.mv.db"));
		Assertions.assertEquals(expected, base("jdbc:h2:split:16:" + dir + File.separator + "cdf"));
		Assertions.assertEquals(expected, base("jdbc:h2:tcp://localhost:9092/" + dir.replace('\\', '/') + "/cdf"));
	}

	@Test
	void aRelativePathResolvesAgainstTheWorkingDirectoryAndTildeAgainstHome() {
		Assertions.assertEquals(new File(System.getProperty("user.dir"), "conf/x").getAbsoluteFile().toPath().normalize().toFile(),
				base("./conf/x;TRACE_LEVEL_SYSTEM_OUT=0"));
		Assertions.assertEquals(new File(System.getProperty("user.home"), "x").getAbsoluteFile(), base("jdbc:h2:~/x"));
	}

	@Test
	void anythingThatIsNotALocalFileDatabaseHasNoBase() {
		Assertions.assertNull(base(null));
		Assertions.assertNull(base(" "));
		Assertions.assertNull(base("jdbc:h2:mem:cdf"));
		Assertions.assertNull(base("jdbc:h2:zip:/data/x.zip!/cdf"));
		Assertions.assertNull(base("jdbc:postgresql://localhost/cdf"));
		Assertions.assertNull(base("jdbc:h2:tcp://dbserver/C:/data/cdf"), "a remote server's file cannot be identified");
		Assertions.assertNull(base("jdbc:h2:tcp://localhost/relative/cdf"), "relative to the server's own base directory");
	}

	@Test
	void theCdfIsRecognizedByNameOrByItsConfiguredFile() {
		String cdf = tmp.resolve("ConnectionsDefinitionFile.cdf").toString();
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault(cdf + ";TRACE_LEVEL_SYSTEM_OUT=0", "pwd");
		vault.setDatabaseConnections(new HashMap<>());

		Assertions.assertTrue(CdfProtection.isCdf(vault, definition(SpringPropertiesConfig.CDF_ID, "jdbc:h2:mem:anything")));
		Assertions.assertTrue(CdfProtection.isCdf(vault, definition("ALIAS", "jdbc:h2:file:" + cdf + ";CIPHER=AES")));
		Assertions.assertTrue(CdfProtection.isCdf(vault, definition("ALIAS", "jdbc:h2:" + cdf.toLowerCase())) == isCaseInsensitiveFileSystem(),
				"case follows the platform's file names");
		Assertions.assertFalse(CdfProtection.isCdf(vault, definition("OTHER", "jdbc:h2:" + tmp.resolve("other"))));
		Assertions.assertFalse(CdfProtection.isCdf(vault, definition("MEM", "jdbc:h2:mem:x")));
		Assertions.assertFalse(CdfProtection.isCdf(vault, null));
	}

	private static boolean isCaseInsensitiveFileSystem() {
		return new File("A").equals(new File("a"));
	}

	private static DatabaseDefinition definition(String id, String url) {
		DatabaseDefinition def = new DatabaseDefinition(id);
		def.setDbType(SpringPropertiesConfig.DBTYPE_H2);
		def.setUrl(url);
		return def;
	}
}
