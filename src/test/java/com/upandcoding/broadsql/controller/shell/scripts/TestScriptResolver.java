package com.upandcoding.broadsql.controller.shell.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * SPRINT 1909S: {@link ScriptResolver}, the only place a Script reference becomes a path. Native
 * java.nio semantics, deterministic, no alias/basename/extension guessing, library references confined.
 */
class TestScriptResolver {

	@TempDir
	Path tmp;

	Path library;
	Path elsewhere;
	ScriptResolver resolver;

	@BeforeEach
	void setUp() throws IOException {
		library = Files.createDirectories(tmp.resolve("scripts")).toRealPath();
		elsewhere = Files.createDirectories(tmp.resolve("elsewhere")).toRealPath();
		resolver = new ScriptResolver(library.toString());
	}

	private Path write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	@Test
	void plainNameIsRelativeToTheLibraryRoot() throws Exception {
		Path foo = write(library.resolve("foo.bsql"), "SELECT 1;");
		ScriptResolver.ResolvedScript resolved = resolver.resolveForExecution("foo.bsql", null);
		Assertions.assertEquals(foo, resolved.getPath());
		Assertions.assertTrue(resolved.isInLibrary());
	}

	@Test
	void subfolderReferenceIsRelativeToTheLibraryRoot() throws Exception {
		Path foo = write(library.resolve("maintenance").resolve("foo.bsql"), "SELECT 1;");
		Assertions.assertEquals(foo, resolver.resolveForExecution("maintenance/foo.bsql", null).getPath());
		Assertions.assertEquals(foo, resolver.resolveLibraryScript("maintenance/foo.bsql").getPath());
	}

	@Test
	void plainReferenceInsideANestedScriptStillResolvesFromTheLibraryRoot() throws Exception {
		Path util = write(library.resolve("common").resolve("util.bsql"), "SELECT 1;");
		Path main = write(library.resolve("admin").resolve("main.bsql"), "SELECT 1;");
		ScriptContextStack stack = new ScriptContextStack();
		stack.push(main.toRealPath());
		Assertions.assertEquals(util, resolver.resolveForExecution("common/util.bsql", stack).getPath());
	}

	@Test
	void noExtensionIsEverGuessed() throws Exception {
		write(library.resolve("foo.bsql"), "SELECT 1;");
		write(library.resolve("bar.sql"), "SELECT 1;");
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveForExecution("foo", null));
		Assertions.assertTrue(e.getMessage().contains("not found"), e.getMessage());
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveLibraryScript("bar"));
	}

	@Test
	void aFileLiterallyNamedWithoutExtensionResolves() throws Exception {
		Path plain = write(library.resolve("foo"), "SELECT 1;");
		Assertions.assertEquals(plain, resolver.resolveForExecution("foo", null).getPath());
	}

	@Test
	void noBasenameSearch() throws Exception {
		write(library.resolve("deep").resolve("foo.bsql"), "SELECT 1;");
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveForExecution("foo.bsql", null));
	}

	@Test
	void anyTextExtensionIsAScript() throws Exception {
		for (String name : new String[] { "a.bsql", "a.sql", "a.txt", "a.foo", "a" }) {
			Path file = write(library.resolve(name), "SELECT 1;");
			Assertions.assertEquals(file, resolver.resolveForExecution(name, null).getPath(), name);
		}
	}

	@Test
	void parentTraversalCannotEscapeTheLibrary() throws Exception {
		write(elsewhere.resolve("secret.bsql"), "SELECT 1;");
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class,
				() -> resolver.resolveLibraryScript("../elsewhere/secret.bsql"));
		Assertions.assertTrue(e.getMessage().contains("outside the Scripts Library"), e.getMessage());
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveForExecution("../elsewhere/secret.bsql", null));
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveInLibrary("sub/../../elsewhere/secret.bsql"));
	}

	@Test
	void dotDotThatStaysInsideTheLibraryIsNormalized() throws Exception {
		Path foo = write(library.resolve("a").resolve("foo.bsql"), "SELECT 1;");
		Assertions.assertEquals(foo, resolver.resolveLibraryScript("a/b/../foo.bsql").getPath());
	}

	@Test
	void duplicateSeparatorsAreCollapsedNatively() throws Exception {
		Path foo = write(library.resolve("a").resolve("foo.bsql"), "SELECT 1;");
		Assertions.assertEquals(foo, resolver.resolveLibraryScript("a//foo.bsql").getPath());
	}

	@Test
	void theRootItselfAndTheReservedArchivesFolderAreRejected() throws Exception {
		Files.createDirectories(library.resolve("archives"));
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveInLibrary("."));
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveInLibrary("archives/foo.bsql.20260101-000000"));
		Assertions.assertTrue(e.getMessage().contains("reserved"), e.getMessage());
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveInLibrary("ARCHIVES/x"));
	}

	@Test
	void libCommandsRejectAbsoluteAndExplicitRelativeReferences() throws Exception {
		Path external = write(elsewhere.resolve("x.bsql"), "SELECT 1;");
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveLibraryScript(external.toString()));
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveLibraryScript("./x.bsql"));
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveInLibrary(external.toString()));
	}

	@Test
	void absolutePathRunsAnExternalScriptWithoutTouchingTheLibrary() throws Exception {
		Path external = write(elsewhere.resolve("x.bsql"), "SELECT 1;");
		ScriptResolver noLibrary = new ScriptResolver(tmp.resolve("does-not-exist").toString());
		ScriptResolver.ResolvedScript resolved = noLibrary.resolveForExecution(external.toString(), null);
		Assertions.assertEquals(external, resolved.getPath());
		Assertions.assertFalse(resolved.isInLibrary());
	}

	@Test
	void explicitRelativeIsRelativeToTheWorkingDirectoryWhenInteractive() throws Exception {
		Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		Path probe = write(cwd.resolve("target").resolve("resolver-probe-1909s.bsql"), "SELECT 1;");
		try {
			Assertions.assertEquals(probe.normalize(), resolver.resolveForExecution("./target/resolver-probe-1909s.bsql", null).getPath());
			Assertions.assertEquals(probe.normalize(), resolver.resolveForExecution("./target/resolver-probe-1909s.bsql", new ScriptContextStack()).getPath());
		} finally {
			Files.deleteIfExists(probe);
		}
	}

	@Test
	void explicitRelativeInsideAScriptIsRelativeToThatScriptsDirectory() throws Exception {
		Path main = write(library.resolve("maintenance").resolve("main.bsql"), "SELECT 1;");
		Path helper = write(library.resolve("maintenance").resolve("helper.bsql"), "SELECT 2;");
		ScriptContextStack stack = new ScriptContextStack();
		stack.push(main.toRealPath());
		Assertions.assertEquals(helper, resolver.resolveForExecution("./helper.bsql", stack).getPath());
	}

	@Test
	void explicitRelativeInsideAnExternalScriptUsesTheExternalScriptsDirectory() throws Exception {
		Path main = write(elsewhere.resolve("bundle").resolve("main.bsql"), "SELECT 1;");
		Path helper = write(elsewhere.resolve("bundle").resolve("helper.bsql"), "SELECT 2;");
		ScriptContextStack stack = new ScriptContextStack();
		stack.push(main.toRealPath());
		Assertions.assertEquals(helper, resolver.resolveForExecution("./helper.bsql", stack).getPath());
	}

	@Test
	void explicitRelativeMayLeaveTheLibraryButAPlainOneMayNot() throws Exception {
		Path main = write(library.resolve("main.bsql"), "SELECT 1;");
		Path outside = write(elsewhere.resolve("out.bsql"), "SELECT 2;");
		ScriptContextStack stack = new ScriptContextStack();
		stack.push(main.toRealPath());
		Assertions.assertEquals(outside, resolver.resolveForExecution("./../elsewhere/out.bsql", stack).getPath());
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveForExecution("../elsewhere/out.bsql", stack));
	}

	@Test
	void directoryTargetIsReportedAsADirectory() throws Exception {
		Files.createDirectories(library.resolve("adir"));
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveForExecution("adir", null));
		Assertions.assertTrue(e.getMessage().contains("is a directory"), e.getMessage());
	}

	@Test
	void missingTargetNamesTheResolvedPath() {
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveForExecution("nope.bsql", null));
		Assertions.assertTrue(e.getMessage().contains(library.resolve("nope.bsql").toString()), e.getMessage());
	}

	@Test
	void blankReferenceIsRejected() {
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveForExecution("  ", null));
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveInLibrary(null));
	}

	@Test
	void libraryRootMissingOrAFileIsAClearError() throws Exception {
		BroadSQLException missing = Assertions.assertThrows(BroadSQLException.class,
				() -> new ScriptResolver(tmp.resolve("nope").toString()).resolveInLibrary("a.bsql"));
		Assertions.assertTrue(missing.getMessage().contains("does not exist"), missing.getMessage());
		Path file = write(tmp.resolve("afile"), "x");
		BroadSQLException isFile = Assertions.assertThrows(BroadSQLException.class,
				() -> new ScriptResolver(file.toString()).resolveInLibrary("a.bsql"));
		Assertions.assertTrue(isFile.getMessage().contains("file, not a folder"), isFile.getMessage());
		Assertions.assertThrows(BroadSQLException.class, () -> new ScriptResolver("").resolveInLibrary("a.bsql"));
	}

	@Test
	void symbolicLinkEscapeIsRejected() throws Exception {
		Path target = write(elsewhere.resolve("real.bsql"), "SELECT 1;");
		Path link = library.resolve("link.bsql");
		try {
			Files.createSymbolicLink(link, target);
		} catch (UnsupportedOperationException | IOException | SecurityException e) {
			Assumptions.abort("symbolic links are not available here: " + e);
		}
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveLibraryScript("link.bsql"));
		Assertions.assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
	}

	@Test
	void relativeKeyUsesForwardSlashes() throws Exception {
		Path file = write(library.resolve("a").resolve("b").resolve("c.bsql"), "x");
		Assertions.assertEquals("a/b/c.bsql", ScriptResolver.relativeKey(library, file));
	}

	// ---- platform specific path forms: only meaningful on the platform that defines them ----

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void windowsDriveAndBackslashForms() throws Exception {
		Path foo = write(library.resolve("maintenance").resolve("foo.bsql"), "SELECT 1;");
		// natively, both separators are accepted on Windows, and .\ is an explicit relative reference
		Assertions.assertEquals(foo, resolver.resolveLibraryScript("maintenance\\foo.bsql").getPath());
		Assertions.assertEquals(foo, resolver.resolveLibraryScript("maintenance/foo.bsql").getPath());
		Path external = write(elsewhere.resolve("x.bsql"), "SELECT 1;");
		Assertions.assertEquals(external, resolver.resolveForExecution(external.toString(), null).getPath()); // C:\...
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveLibraryScript(".\\maintenance\\foo.bsql"));
		// drive-relative and root-relative forms are ambiguous and refused
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveForExecution("C:foo.bsql", null));
		Assertions.assertThrows(BroadSQLException.class, () -> resolver.resolveForExecution("\\foo.bsql", null));
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void windowsExplicitRelativeWithBackslash() throws Exception {
		Path main = write(library.resolve("m").resolve("main.bsql"), "SELECT 1;");
		Path helper = write(library.resolve("m").resolve("helper.bsql"), "SELECT 2;");
		ScriptContextStack stack = new ScriptContextStack();
		stack.push(main.toRealPath());
		Assertions.assertEquals(helper, resolver.resolveForExecution(".\\helper.bsql", stack).getPath());
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void unixBackslashIsAnOrdinaryFilenameCharacterAndNotASeparator() throws Exception {
		Path odd = write(library.resolve("a\\b.bsql"), "SELECT 1;");
		Assertions.assertEquals(odd, resolver.resolveLibraryScript("a\\b.bsql").getPath());
		// .\x is a plain library file name on Unix, not an explicit relative reference
		Path dotBackslash = write(library.resolve(".\\x.bsql"), "SELECT 1;");
		Assertions.assertEquals(dotBackslash, resolver.resolveLibraryScript(".\\x.bsql").getPath());
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void unixAbsolutePathIsExternal() throws Exception {
		Path external = write(elsewhere.resolve("x.bsql"), "SELECT 1;");
		Assertions.assertEquals(external, resolver.resolveForExecution(external.toString(), null).getPath());
	}
}
