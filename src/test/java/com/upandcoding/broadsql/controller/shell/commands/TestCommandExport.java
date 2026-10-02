package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandExport;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class TestCommandExport {

	@Test
	void appendsTheDefaultExtensionAndPrefixesTheExportFolderWhenNeitherIsGiven() throws BroadSQLException {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName("C:\\export\\");
		CommandExport export = CommandTestSupport.create(CommandExport.class, null, new CapturingShellConsole(), settings);

		Assertions.assertEquals("C:\\export\\test.ods", export.getTargetFileName("exp test"));
	}

	@Test
	void keepsAnExplicitExtension() throws BroadSQLException {
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setExtractFolderName("C:\\export\\");
		CommandExport export = CommandTestSupport.create(CommandExport.class, null, new CapturingShellConsole(), settings);

		Assertions.assertEquals("C:\\export\\test.txt", export.getTargetFileName("exp test.txt"));
	}

	@Test
	void keepsAnExplicitDirectoryThatExists(@TempDir Path tempDir) throws BroadSQLException {
		CommandExport export = CommandTestSupport.create(CommandExport.class, new CapturingShellConsole());

		String target = tempDir.resolve("test.mdb").toString();
		Assertions.assertEquals(target, export.getTargetFileName("exp " + target));
	}

	@Test
	void failsWhenTheExplicitDirectoryDoesNotExist(@TempDir Path tempDir) {
		CommandExport export = CommandTestSupport.create(CommandExport.class, new CapturingShellConsole());

		String missingDir = tempDir.resolve("does-not-exist").toString();
		String target = missingDir + File.separator + "test.mdb";

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> export.getTargetFileName("exp " + target));
		Assertions.assertEquals("Folder " + missingDir + " does not exist", ex.getLocalizedMessage());
	}

	@Test
	void returnsNullWithNoFileNameArgument() throws BroadSQLException {
		CommandExport export = CommandTestSupport.create(CommandExport.class, new CapturingShellConsole());

		Assertions.assertNull(export.getTargetFileName("exp"));
		Assertions.assertNull(export.getTargetFileName("exp "));
	}

	@Test
	void turnsExportModeOnAndPrintsTheTargetFile(@TempDir Path tempDir) throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandExport export = CommandTestSupport.create(CommandExport.class, console);

		String target = tempDir.resolve("out.txt").toString();
		export.execute("EXP " + target);

		Assertions.assertTrue(export.isExtractMode());
		Assertions.assertEquals(target, export.getExtractFileName());
		Assertions.assertTrue(console.getOutput().contains("Export to file: " + target),
				"expected the target file in output, got:\n" + console.getOutput());
	}

	@Test
	void turnsExportModeOffWithNoArgument() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandExport export = CommandTestSupport.create(CommandExport.class, console);
		export.setExtractMode(true);

		export.execute("EXP");

		Assertions.assertFalse(export.isExtractMode());
		Assertions.assertTrue(console.getOutput().contains("Export mode: OFF"),
				"expected export mode off in output, got:\n" + console.getOutput());
	}

	/**
	 * Regression test for a real bug found while writing this suite, fixed in
	 * {@code CommandUtils.getArgumentsFromQuery} (missing {@code break} after an exact keyword match -
	 * see {@code docs/TECHNICAL_CHANGE.md}): bare {@code EXPORT}, the command's own first-declared
	 * keyword, used to be misparsed as an {@code EXPORT.ods} file name because a later-declared alias
	 * ({@code EXP}) is a proper prefix of it. Every alias, not just the shortest one, must turn export
	 * mode off with no argument.
	 */
	@Test
	void turnsExportModeOffWithNoArgumentUsingTheLongestKeywordToo() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandExport export = CommandTestSupport.create(CommandExport.class, console);
		export.setExtractMode(true);

		export.execute("EXPORT");

		Assertions.assertFalse(export.isExtractMode());
		Assertions.assertTrue(console.getOutput().contains("Export mode: OFF"),
				"expected export mode off in output, got:\n" + console.getOutput());
	}
}
