package com.upandcoding.broadsql.controller.shell.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExternalFile;

/**
 * SPRINT 3009A (#189): the command line that runs a Scripts Library Script, as the Editor's Send to CLI and Copy CLI
 * Command produce it. Checked against the real pieces the prompt uses to run it: the interpreter's {@code ;}
 * termination, the {@code @} command's argument parsing ({@link CommandUtils#getArgumentsFromQuery}) and
 * {@link ScriptResolver#resolveForExecution}, so the line runs this very file.
 */
class TestScriptLibraryInvocation {

	/** What the prompt does with the line: strip the final {@code ;} (CommandInterpreter), parse the {@code @} arguments, resolve the Script. */
	private static Path runTarget(String line, Path libRoot) throws BroadSQLException {
		Assertions.assertTrue(line.endsWith(";"), "the line ends with ; so pressing Enter runs it: " + line);
		String query = StringUtils.substringBeforeLast(line, ";").trim();
		String[] args = CommandUtils.getArgumentsFromQuery(query, new CommandExternalFile().getKeywords());
		Assertions.assertEquals(1, args.length, "one argument, no parameter: " + line);
		return new ScriptResolver(libRoot.toString()).resolveForExecution(args[0], null).getPath();
	}

	@Test
	void aPlainLibraryPathIsGluedToTheAtCommand(@TempDir Path lib) throws IOException, BroadSQLException {
		Files.createDirectories(lib.resolve("reports"));
		Files.writeString(lib.resolve("reports/QR13.sql"), "select 1;");

		String line = ScriptResolver.libraryInvocation("reports/QR13.sql");

		Assertions.assertEquals("@reports/QR13.sql;", line);
		Assertions.assertTrue(Files.isSameFile(lib.resolve("reports/QR13.sql"), runTarget(line, lib)));
	}

	@Test
	void aPathWithSpacesIsQuotedTheWayTheArgumentParserReadsIt(@TempDir Path lib) throws IOException, BroadSQLException {
		Files.createDirectories(lib.resolve("my reports"));
		Files.writeString(lib.resolve("my reports/QR 13.sql"), "select 1;");

		String line = ScriptResolver.libraryInvocation("my reports/QR 13.sql");

		Assertions.assertEquals("@\"my reports/QR 13.sql\";", line);
		Assertions.assertTrue(Files.isSameFile(lib.resolve("my reports/QR 13.sql"), runTarget(line, lib)));
	}

	@Test
	void aPathTheParserCannotRepresentIsRefusedRatherThanMangled() {
		Assertions.assertThrows(BroadSQLException.class, () -> ScriptResolver.libraryInvocation("odd\"name.sql"));
		Assertions.assertThrows(BroadSQLException.class, () -> ScriptResolver.libraryInvocation(" "));
	}

	@Test
	void theAtCommandKeywordIsTheSharedConstant() {
		Assertions.assertArrayEquals(new String[] { ScriptResolver.RUN_KEYWORD }, new CommandExternalFile().getKeywords());
	}

	@Test
	void quotingIsTheCompletionRule() {
		Assertions.assertEquals("plain.sql", CommandUtils.quoteArgumentIfNeeded("plain.sql"));
		Assertions.assertEquals("\"a b\"", CommandUtils.quoteArgumentIfNeeded("a b"));
		Assertions.assertEquals("\"a\tb\"", CommandUtils.quoteArgumentIfNeeded("a\tb"));
	}
}
