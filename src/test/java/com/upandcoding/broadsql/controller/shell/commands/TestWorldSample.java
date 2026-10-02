package com.upandcoding.broadsql.controller.shell.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;

import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandDescr;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowForeignKeys;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowReferences;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowTables;
import com.upandcoding.broadsql.controller.shell.completion.EntityCompletionService;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * The WORLD sample BroadSQL ships: the connection in the shipped CDF template, the sample database it points
 * to, and the packaging that puts that database where the connection's relative URL finds it. The shipped
 * files are never opened in place (H2 writes to a database it opens): tests work on copies.
 */
class TestWorldSample {

	private static final Path SHIPPED_CDF = Path.of("src", "main", "resources", "release-template", "conf", "ConnectionsDefinitionFile.cdf.mv.db");
	private static final Path SHIPPED_SAMPLE = Path.of("src", "main", "release-template", "samples", "WorldDB.mv.db");
	private static final String SHIPPED_CDF_PASSWORD = "clipper8AD";

	@TempDir
	Path dir;
	private DatabaseConnection db;

	@AfterEach
	void tearDown() throws Exception {
		TestDatabaseConnections.close(db);
	}

	private DatabaseDefinition shippedWorld() throws Exception {
		Files.copy(SHIPPED_CDF, dir.resolve("ConnectionsDefinitionFile.cdf.mv.db"), StandardCopyOption.REPLACE_EXISTING);
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault(dir.resolve("ConnectionsDefinitionFile.cdf").toString(), SHIPPED_CDF_PASSWORD);
		vault.load();
		DatabaseDefinition world = vault.getDatabaseConnection("WORLD");
		Assertions.assertNotNull(world, "the shipped CDF has no WORLD connection");
		return world;
	}

	@Test
	void theShippedConnectionIsExactlyTheWorldDefinition() throws Exception {
		DatabaseDefinition world = shippedWorld();
		Assertions.assertEquals("Sample World Database", world.getDbName());
		Assertions.assertEquals("jdbc:h2:./samples/WorldDB", world.getUrl());
		Assertions.assertEquals("H2", world.getDbType());
		Assertions.assertEquals("org.h2.Driver", world.getDbDriver());
		Assertions.assertEquals("sa", world.getUserName());
		Assertions.assertTrue(world.getUserPassword() == null || world.getUserPassword().isEmpty(), "no password");
		Assertions.assertNull(world.getDatabaseGroup(), "no Database Group");
		Assertions.assertEquals("LOCAL", world.getEnvironment());
	}

	@Test
	void theUrlNamesTheShippedDatabaseFileAndTheBuildPutsItThere() throws Exception {
		// jdbc:h2:./samples/WorldDB is the file samples/WorldDB.mv.db of the installation folder
		String relative = shippedWorld().getUrl().substring("jdbc:h2:./".length()) + ".mv.db";
		Assertions.assertEquals("samples/WorldDB.mv.db", relative);
		Assertions.assertTrue(Files.isRegularFile(SHIPPED_SAMPLE), SHIPPED_SAMPLE + " is missing");
		// How the private build and the release ZIP copy this file: TestWorldSamplePackaging (release tooling).
	}

	@Test
	void theLaunchersRunFromTheInstallationFolderSoRelativeUrlsResolveThere() throws Exception {
		Assertions.assertTrue(Files.readString(Path.of("deploy", "BroadSQL.bat")).contains("pushd \"%~dp0\""));
		Assertions.assertTrue(Files.readString(Path.of("deploy", "connect.bat")).contains("call \"%~dp0BroadSQL.bat\""));
		Assertions.assertTrue(Files.readString(Path.of("deploy", "BroadSQL.ps1")).contains("$PSScriptRoot\\BroadSQL.bat"));
		// resolved from the script's own location, symbolic links followed (run: TestLinuxLauncher)
		Assertions.assertTrue(Files.readString(Path.of("deploy", "broadsql.sh")).contains("cd \"$BROADSQL_HOME\""));
	}

	/** A copy of the shipped sample, opened with the shipped connection's own driver, type, user and password. */
	private DatabaseConnection connectToACopy() throws Exception {
		DatabaseDefinition world = shippedWorld();
		Path samples = Files.createDirectories(dir.resolve("samples"));
		Files.copy(SHIPPED_SAMPLE, samples.resolve("WorldDB.mv.db"), StandardCopyOption.REPLACE_EXISTING);
		DatabaseDefinition copy = new DatabaseDefinition("WORLD", world.getDbType(), world.getDbDriver(),
				"jdbc:h2:" + samples.resolve("WorldDB").toAbsolutePath(), world.getUserName(), world.getUserPassword() == null ? "" : world.getUserPassword(),
				world.getDbName());
		return TestDatabaseConnections.connect(copy);
	}

	@Test
	void theSampleAnswersTheDocumentedFirstCommands() throws Exception {
		db = connectToACopy();
		CapturingShellConsole console = new CapturingShellConsole();
		db.setCmdLineConsole(console);

		CommandTestSupport.create(CommandShowTables.class, db, console).execute("SHOW TABLES");
		for (String table : new String[] { "CITY", "COUNTRY", "COUNTRYLANGUAGE", "CURRENCY", "REGION" }) {
			Assertions.assertTrue(console.getOutput().contains("|" + table + " "), table + " missing:\n" + console.getOutput());
		}
		console.clear();
		CommandTestSupport.create(CommandDescr.class, db, console).execute("DESCR COUNTRY");
		Assertions.assertTrue(console.getOutput().contains("|CODE ") && console.getOutput().contains("|CONTINENT "), console.getOutput());
		console.clear();
		db.executeSelectQuery("SELECT CODE, NAME FROM COUNTRY WHERE CODE = 'FRA'");
		Assertions.assertTrue(console.getOutput().contains("France"), console.getOutput());
		console.clear();
		CommandTestSupport.create(CommandShowReferences.class, db, console).execute("SHOW REFERENCES COUNTRY");
		Assertions.assertTrue(console.getOutput().contains("REGION"), "REGION references COUNTRY:\n" + console.getOutput());
		console.clear();
		CommandTestSupport.create(CommandShowForeignKeys.class, db, console).execute("SHOW FK REGION");
		Assertions.assertTrue(console.getOutput().contains("COUNTRY"), console.getOutput());
	}

	@Test
	void theSampleIsWritable() throws Exception {
		db = connectToACopy();
		db.executeUpdateQuery("CREATE TABLE MY_NOTES (ID INT PRIMARY KEY, NOTE VARCHAR(40))");
		db.executeUpdateQuery("INSERT INTO MY_NOTES VALUES (1, 'mine')");
		db.executeUpdateQuery("UPDATE COUNTRY SET POPULATION = POPULATION + 1 WHERE CODE = 'FRA'");
	}

	@Test
	void tableNamesOfTheSampleAreCompleted() throws Exception {
		db = connectToACopy();
		CommandList commands = new CommandList();
		for (String keyword : new CommandDescr().getKeywords()) {
			commands.put(keyword, new CommandDescr());
		}
		String line = "DESCR COU";
		List<String> words = new DefaultParser().parse(line, line.length(), org.jline.reader.Parser.ParseContext.COMPLETE).words();
		List<String> values = EntityCompletionService.standard(commands, db).complete(words, words.size() - 1).stream().map(CompletionCandidate::getValue)
				.collect(Collectors.toList());
		Assertions.assertTrue(values.containsAll(List.of("COUNTRY", "COUNTRYLANGUAGE")), values.toString());
	}
}
