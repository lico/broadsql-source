package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibRun;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExternalFile;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptContextStack;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptRunResult;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptStatus;

/**
 * SPRINT 1909S: the one canonical Script execution pipeline, end to end (real interpreter, real H2):
 * {@code @} and {@code LIB RUN} resolution, extensions without meaning, nested {@code ./} resolution,
 * cycle protection, parameters, the historical statement-splitting and double-execution defects, and the
 * instance/environment safeguards evaluated for every executed Script.
 */
class TestScriptExecution {

	@TempDir
	Path tmp;

	private Path library;
	private Path elsewhere;
	private ConsoleSettings settings;
	private DatabaseConnection db;
	private CapturingShellConsole console;
	private CommandInterpreter interpreter;

	@BeforeEach
	void setUp() throws BroadSQLException, IOException {
		library = Files.createDirectories(tmp.resolve("scripts"));
		elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
		settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(library.toString());
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50))");
		console = new CapturingShellConsole();
		interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, db);
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
		LastQueryResultHolder.set(null);
	}

	// ---- helpers ----

	private Path script(Path dir, String relative, String text) throws IOException {
		Path file = dir.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

	private Path lib(String relative, String text) throws IOException {
		return script(library, relative, text);
	}

	private void at(String reference) throws BroadSQLException {
		CommandExternalFile cmd = CommandTestSupport.create(CommandExternalFile.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(interpreter);
		cmd.execute("@" + reference);
	}

	private void libRun(String reference) throws BroadSQLException {
		CommandLibRun cmd = CommandTestSupport.create(CommandLibRun.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(interpreter);
		cmd.execute("LIB RUN " + reference);
	}

	private int count() throws BroadSQLException {
		CapturingShellConsole probe = new CapturingShellConsole();
		db.setCmdLineConsole(probe);
		db.executeSelectQuery("SELECT COUNT(*) FROM CUSTOMER");
		db.setCmdLineConsole(console);
		String out = probe.getOutput();
		// the first number in a table cell (cells are bordered with |, see TableBorders)
		for (String token : out.split("[\\s|]+")) {
			if (token.matches("\\d+")) {
				return Integer.parseInt(token);
			}
		}
		throw new IllegalStateException("no count in: " + out);
	}

	private String output() {
		return console.getOutput();
	}

	// ---- the model: a Script is a text file, whatever it contains and whatever its extension ----

	@Test
	void aSingleSqlStatementIsAScript() throws Exception {
		lib("one.bsql", "SELECT 41 + 1 AS ANSWER;");
		at("one.bsql");
		Assertions.assertTrue(output().contains("ANSWER") && output().contains("42"), output());
	}

	@Test
	void severalSqlStatementsAndMultilineStatementsRunInOrder() throws Exception {
		lib("multi.bsql", "INSERT INTO CUSTOMER VALUES (1, 'Alice');\nINSERT INTO CUSTOMER\n   VALUES (2, 'Bob');\nSELECT COUNT(*) FROM CUSTOMER;\n");
		at("multi.bsql");
		Assertions.assertEquals(2, count(), output());
	}

	@Test
	void sqlMixedWithBroadSqlCommandsRuns() throws Exception {
		lib("mixed.bsql", "INSERT INTO CUSTOMER VALUES (1, 'Alice');\nSHOW TABLES;\nSELECT NAME FROM CUSTOMER;\n");
		at("mixed.bsql");
		Assertions.assertEquals(1, count(), output());
		Assertions.assertTrue(output().contains("CUSTOMER"), output());
	}

	@Test
	void extensionsHaveNoExecutionSemantics() throws Exception {
		String content = "INSERT INTO CUSTOMER VALUES (${a}, 'x');\nINSERT INTO CUSTOMER VALUES (${b}, 'y');\n";
		int id = 1;
		for (String name : new String[] { "s.bsql", "s.sql", "s.txt", "s.foo", "s" }) {
			lib(name, content);
			at(name + " a=" + id + " b=" + (id + 100));
			id++;
		}
		Assertions.assertEquals(10, count(), output());
		Assertions.assertFalse(output().toLowerCase().contains("error"), output());
	}

	@Test
	void aSqlExtensionMayHoldSeveralStatementsAndCommands() throws Exception {
		lib("many.sql", "INSERT INTO CUSTOMER VALUES (1, 'a');\nINSERT INTO CUSTOMER VALUES (2, 'b');\nSHOW TABLES;\n");
		at("many.sql");
		Assertions.assertEquals(2, count(), output());
	}

	// ---- typed at the prompt: the interpreter's own dispatch of @ and LIB RUN ----

	@Test
	void anAtLineTypedAtThePromptIsDispatchedLikeAnyCommandIncludingQuotedPathsAndArguments() throws Exception {
		lib("d.bsql", "INSERT INTO CUSTOMER VALUES (${id}, ${name});\n");
		Path spaced = script(elsewhere, "My Scripts/s.bsql", "INSERT INTO CUSTOMER VALUES (${id}, ${name});\n");

		interpreter.executeMultiStatementLine("@d.bsql id=1 name='first'; @\"" + spaced + "\" id=2 name='second one'; LIB RUN d.bsql id=3 name='third';");

		Assertions.assertEquals(3, count(), output());
		Assertions.assertFalse(output().toLowerCase().contains("error"), output());
		Assertions.assertEquals(0, interpreter.getScriptContext().depth());
	}

	@Test
	void aFailingAtLineStopsTheRestOfThatTypedLineButNotTheInteractiveSession() throws Exception {
		lib("ok.bsql", "INSERT INTO CUSTOMER VALUES (1, 'ok');\n");

		interpreter.executeMultiStatementLine("@missing.bsql; @ok.bsql;");

		Assertions.assertEquals(0, count(), "interactive input keeps stopping at the first failure: " + output());
		Assertions.assertTrue(output().contains("Script not found"), output());
		interpreter.executeMultiStatementLine("@ok.bsql;");
		Assertions.assertEquals(1, count(), output());
	}

	// ---- @ and LIB RUN converge ----

	private static String normalized(String out) {
		return out.replaceAll("in [0-9]+ ms", "in N ms").replaceAll("\\[run [0-9A-Z]{8}\\]", "[run ID]");
	}

	@Test
	void atAndLibRunProduceIdenticalOutputForTheSameScript() throws Exception {
		lib("maintenance/report.bsql", "INSERT INTO CUSTOMER VALUES (${id}, ${name});\nSELECT NAME FROM CUSTOMER WHERE ID = ${id};\n");
		at("maintenance/report.bsql id=1 name='alpha'");
		String viaAt = output();

		console = new CapturingShellConsole();
		interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, db);
		db.executeUpdateQuery("DELETE FROM CUSTOMER");
		console.clear();
		libRun("maintenance/report.bsql id=1 name='alpha'");
		String viaLibRun = output();

		Assertions.assertEquals(normalized(viaAt), normalized(viaLibRun));
		Assertions.assertTrue(viaAt.contains("alpha"), viaAt);
	}

	/** Runs {@code @reference} and returns the result the executor recorded for this statement. */
	private ScriptRunResult runAt(String reference) throws BroadSQLException {
		ScriptContextStack.StatementState state = interpreter.getScriptContext().beginStatement();
		at(reference);
		return state.getCalledRuns().get(state.getCalledRuns().size() - 1);
	}

	private ScriptRunResult runLib(String reference) throws BroadSQLException {
		ScriptContextStack.StatementState state = interpreter.getScriptContext().beginStatement();
		libRun(reference);
		return state.getCalledRuns().get(state.getCalledRuns().size() - 1);
	}

	/** A preflight failure: the run is FAILED with nothing executed, the error is printed, and the statement failed. */
	private void assertPreflightFailure(ScriptRunResult result, String expectedFragment) {
		Assertions.assertEquals(ScriptStatus.FAILED, result.getStatus(), output());
		Assertions.assertEquals(0, result.getExecuted());
		Assertions.assertTrue(result.failsCallingStatement());
		Assertions.assertTrue(output().contains(expectedFragment), output());
	}

	@Test
	void atAndLibRunReportTheSameErrorsForTheSameProblem() throws Exception {
		ScriptRunResult viaAt = runAt("missing.bsql");
		String atOutput = normalized(output());
		console.clear();
		ScriptRunResult viaLib = runLib("missing.bsql");
		Assertions.assertEquals(atOutput, normalized(output()));
		Assertions.assertEquals(ScriptStatus.FAILED, viaAt.getStatus());
		Assertions.assertEquals(ScriptStatus.FAILED, viaLib.getStatus());
		Assertions.assertTrue(atOutput.contains(library.resolve("missing.bsql").toString()), atOutput);
	}

	@Test
	void libRunIsConfinedToTheLibraryAndDoesNotAcceptExternalPaths() throws Exception {
		Path external = script(elsewhere, "x.bsql", "SELECT 1;");
		Assertions.assertEquals(ScriptStatus.FAILED, runLib(external.toString()).getStatus());
		Assertions.assertEquals(ScriptStatus.FAILED, runLib("../elsewhere/x.bsql").getStatus());
		Assertions.assertEquals(ScriptStatus.FAILED, runLib("./x.bsql").getStatus());
		Assertions.assertEquals(ScriptStatus.SUCCESS, runAt(external.toString()).getStatus()); // the same external file runs through @
		Assertions.assertTrue(output().contains("1"), output());
	}

	@Test
	void noExtensionIsAddedByEitherSyntax() throws Exception {
		lib("only.bsql", "SELECT 1;");
		Assertions.assertEquals(ScriptStatus.FAILED, runAt("only").getStatus());
		Assertions.assertEquals(ScriptStatus.FAILED, runLib("only").getStatus());
	}

	@Test
	void aFuzzyNameIsNotFoundEvenWhenABasenameOrAliasWouldMatch() throws Exception {
		lib("deep/thing.bsql", "-- @alias: nick\nSELECT 1;");
		Assertions.assertEquals(ScriptStatus.FAILED, runAt("thing.bsql").getStatus());
		Assertions.assertEquals(ScriptStatus.FAILED, runAt("nick").getStatus());
		Assertions.assertEquals(ScriptStatus.FAILED, runLib("nick").getStatus());
	}

	@Test
	void directoryUnreadableAndBinaryTargetsFailClearly() throws Exception {
		Files.createDirectories(library.resolve("adir"));
		assertPreflightFailure(runAt("adir"), "is a directory");

		Files.write(library.resolve("blob.bin"), new byte[] { 'M', 'Z', 0, 1, 2, 3, 0, 0 });
		assertPreflightFailure(runAt("blob.bin"), "not a text file");
	}

	// ---- nesting and context ----

	@Test
	void aNestedExplicitRelativeReferenceRunsExactlyOnceFromTheCallersDirectory() throws Exception {
		lib("maintenance/main.bsql", "@./helper.bsql;\nINSERT INTO CUSTOMER VALUES (2, 'main');\n");
		lib("maintenance/helper.bsql", "INSERT INTO CUSTOMER VALUES (1, 'helper');\n");
		at("maintenance/main.bsql");
		Assertions.assertEquals(2, count(), "the helper must run exactly once (the historical nested @ ran twice, failing on its primary key)");
		Assertions.assertFalse(output().toLowerCase().contains("error"), output());
	}

	@Test
	void threeLevelsOfExplicitRelativeReferencesRestoreTheContextAfterEachChild() throws Exception {
		lib("a.bsql", "INSERT INTO CUSTOMER VALUES (1, 'a1');\n@./sub/b.bsql;\nINSERT INTO CUSTOMER VALUES (4, 'a2');\n@./sub/c.bsql;\n");
		lib("sub/b.bsql", "INSERT INTO CUSTOMER VALUES (2, 'b');\n@./c.bsql;\n");
		lib("sub/c.bsql", "INSERT INTO CUSTOMER VALUES (3, 'c');\n");
		// after b (which called sub/c via b's own directory), a continues and calls ./sub/c again from a's directory:
		// c would insert id 3 twice, so the second call must be a reported failure of that statement, not a crash,
		// and a itself keeps going.
		at("a.bsql");
		Assertions.assertEquals(4, count(), output());
		Assertions.assertTrue(interpreter.getScriptContext().depth() == 0, "the context must be fully restored");
	}

	@Test
	void aPlainReferenceInsideANestedScriptResolvesFromTheLibraryRootNotFromTheCallersFolder() throws Exception {
		lib("admin/main.bsql", "@common/util.bsql;\n");
		lib("common/util.bsql", "INSERT INTO CUSTOMER VALUES (1, 'util');\n");
		lib("admin/common/util.bsql", "INSERT INTO CUSTOMER VALUES (99, 'WRONG');\n");
		at("admin/main.bsql");
		Assertions.assertEquals(1, count(), output());
		Assertions.assertTrue(output().contains("util"), output());
	}

	@Test
	void anExternalScriptBundleRunsFromItsOwnDirectory() throws Exception {
		Path main = script(elsewhere, "bundle/main.bsql", "@./helper.bsql;\n");
		script(elsewhere, "bundle/helper.bsql", "INSERT INTO CUSTOMER VALUES (7, 'ext');\n");
		at(main.toString());
		Assertions.assertEquals(1, count(), output());
	}

	@Test
	void aScriptCallingItselfIsRefusedImmediately() throws Exception {
		lib("self.bsql", "INSERT INTO CUSTOMER VALUES (1, 'once');\n@self.bsql;\n");
		at("self.bsql");
		Assertions.assertTrue(output().contains("Recursive script execution"), output());
		Assertions.assertEquals(1, count(), "the body ran once; the recursive call was refused, not repeated");
		Assertions.assertEquals(0, interpreter.getScriptContext().depth());
	}

	@Test
	void anIndirectCycleIsDetectedWithItsChain() throws Exception {
		lib("a.bsql", "@b.bsql;\n");
		lib("b.bsql", "@a.bsql;\n");
		at("a.bsql");
		String out = output();
		Assertions.assertTrue(out.contains("Recursive script execution"), out);
		Assertions.assertTrue(out.contains("a.bsql") && out.contains("b.bsql"), out);
		Assertions.assertEquals(0, interpreter.getScriptContext().depth());
	}

	@Test
	void aFailureInAGrandchildDoesNotCorruptTheContextAndLaterScriptsStillRun() throws Exception {
		lib("a.bsql", "@b.bsql;\nINSERT INTO CUSTOMER VALUES (10, 'after');\n");
		lib("b.bsql", "@c.bsql;\n");
		lib("c.bsql", "INSERT INTO CUSTOMER VALUES (1, 'ok');\nINSERT INTO NO_SUCH_TABLE VALUES (1);\nINSERT INTO CUSTOMER VALUES (2, 'still');\n");
		lib("unrelated.bsql", "INSERT INTO CUSTOMER VALUES (20, 'unrelated');\n");
		at("a.bsql");
		Assertions.assertEquals(3, count(), "c keeps going after its failing statement and a continues after b");
		Assertions.assertEquals(0, interpreter.getScriptContext().depth());
		at("unrelated.bsql");
		Assertions.assertEquals(4, count(), output());
	}

	@Test
	void aChainDeeperThanTheLimitIsRefused() throws Exception {
		for (int i = 1; i <= 34; i++) {
			lib("chain/s" + i + ".bsql", "@./s" + (i + 1) + ".bsql;\n");
		}
		lib("chain/s35.bsql", "SELECT 1;\n");
		at("chain/s1.bsql");
		Assertions.assertTrue(output().contains("nested more than 32 levels"), output());
		Assertions.assertEquals(0, interpreter.getScriptContext().depth());
	}

	// ---- named arguments (SPRINT 0110A: they replace the removed %1..%9) ----

	@Test
	void argumentsReachEveryStatementKindIncludingUpdates() throws Exception {
		lib("ins.bsql", "INSERT INTO CUSTOMER VALUES (${id}, ${first});\nUPDATE CUSTOMER SET NAME = ${third} WHERE ID = ${id};\n");
		at("ins.bsql id=5 first='first' third='third'");
		CapturingShellConsole probe = new CapturingShellConsole();
		db.setCmdLineConsole(probe);
		db.executeSelectQuery("SELECT NAME FROM CUSTOMER WHERE ID = 5");
		db.setCmdLineConsole(console);
		Assertions.assertTrue(probe.getOutput().contains("third"), probe.getOutput());
	}

	@Test
	void aQuotedReferenceWithSpacesAndQuotedValuesWork() throws Exception {
		Path spaced = script(elsewhere, "My Scripts/my script.bsql", "INSERT INTO CUSTOMER VALUES (${id}, ${name});\n");
		CommandExternalFile cmd = CommandTestSupport.create(CommandExternalFile.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(interpreter);
		cmd.execute("@\"" + spaced + "\" id=3 name='value 2'");
		CapturingShellConsole probe = new CapturingShellConsole();
		db.setCmdLineConsole(probe);
		db.executeSelectQuery("SELECT NAME FROM CUSTOMER WHERE ID = 3");
		db.setCmdLineConsole(console);
		Assertions.assertTrue(probe.getOutput().contains("value 2"), probe.getOutput());

		lib("folder/my script.bsql", "INSERT INTO CUSTOMER VALUES (${id}, ${name});\n");
		libRun("\"folder/my script.bsql\" id=4 name='value 4'");
		Assertions.assertEquals(2, count(), output());
	}

	@Test
	void aMissingDeclaredArgumentFailsBeforeAnythingRuns() throws Exception {
		lib("needs.bsql", "-- @params: a, b\nINSERT INTO CUSTOMER VALUES (1, 'x');\nINSERT INTO CUSTOMER VALUES (${b}, 'y');\n");
		assertPreflightFailure(runAt("needs.bsql a=9"), "requires argument b (declared in @params)");
		Assertions.assertEquals(0, count(), output());
	}

	@Test
	void extraArgumentsAreAcceptedAndBecomeVariables() throws Exception {
		lib("one.bsql", "INSERT INTO CUSTOMER VALUES (${id}, 'x');\n");
		Assertions.assertEquals(ScriptStatus.SUCCESS, runAt("one.bsql id=1 unused=2 other='z'").getStatus(), output());
		Assertions.assertEquals(1, count(), output());
		Assertions.assertEquals(2L, interpreter.getScriptVariables().get("unused").getValue());
	}

	@Test
	void aValueContainingSemicolonsCannotChangeStatementBoundaries() throws Exception {
		lib("val.bsql", "INSERT INTO CUSTOMER VALUES (1, ${v});\n");
		interpreter.executeMultiStatementLine("@val.bsql v='a;b';");
		CapturingShellConsole probe = new CapturingShellConsole();
		db.setCmdLineConsole(probe);
		db.executeSelectQuery("SELECT NAME FROM CUSTOMER");
		db.setCmdLineConsole(console);
		Assertions.assertTrue(probe.getOutput().contains("a;b"), probe.getOutput());
	}

	// ---- statement splitting defects of the old script loader ----

	@Test
	void anIndentedOrTrailingCommentDoesNotSwallowTheRestOfTheScript() throws Exception {
		lib("comments.bsql", "INSERT INTO CUSTOMER VALUES (1, 'a'); -- trailing\n   -- indented note\nINSERT INTO CUSTOMER VALUES (2, 'b');\n"
				+ "/* block\n comment */ INSERT INTO CUSTOMER VALUES (3, 'c');\n\n\nINSERT INTO CUSTOMER VALUES (4, 'a/*not a comment*/');\n");
		at("comments.bsql");
		Assertions.assertEquals(4, count(), output());
	}

	@Test
	void semicolonsAndCommentMarkersInsideStringLiteralsAreLiteral() throws Exception {
		lib("lit.bsql", "INSERT INTO CUSTOMER VALUES (1, 'a;b');\nINSERT INTO CUSTOMER VALUES (2, 'x--y');\n");
		at("lit.bsql");
		Assertions.assertEquals(2, count(), output());
	}

	@Test
	void anUnterminatedQuoteFailsTheWholeScriptBeforeRunningAnything() throws Exception {
		lib("bad.bsql", "INSERT INTO CUSTOMER VALUES (1, 'a');\nINSERT INTO CUSTOMER VALUES (2, 'oops);\n");
		assertPreflightFailure(runAt("bad.bsql"), "Unterminated single quote");
		Assertions.assertTrue(output().contains("line 2"), output());
		Assertions.assertEquals(0, count(), output());
	}

	@Test
	void anEmptyOrCommentOnlyScriptIsReported() throws Exception {
		lib("empty.bsql", "-- nothing here\n");
		assertPreflightFailure(runAt("empty.bsql"), "does not contain queries");
	}

	@Test
	void aFailingStatementIsReportedAndTheRestOfTheScriptStillRuns() throws Exception {
		lib("partial.bsql", "INSERT INTO CUSTOMER VALUES (1, 'a');\nINSERT INTO NOPE VALUES (1);\nINSERT INTO CUSTOMER VALUES (2, 'b');\n");
		at("partial.bsql");
		Assertions.assertEquals(2, count(), output());
		Assertions.assertTrue(output().toLowerCase().contains("error"), output());
	}

	// ---- text encodings ----

	@Test
	void aUtf8ScriptWithAccentsRunsCorrectly() throws Exception {
		lib("accents.bsql", "INSERT INTO CUSTOMER VALUES (1, 'Éléonore');\n");
		at("accents.bsql");
		CapturingShellConsole probe = new CapturingShellConsole();
		db.setCmdLineConsole(probe);
		db.executeSelectQuery("SELECT NAME FROM CUSTOMER");
		db.setCmdLineConsole(console);
		Assertions.assertTrue(probe.getOutput().contains("Éléonore"), probe.getOutput());
	}

	@Test
	void aBomPrefixedScriptRuns() throws Exception {
		byte[] body = "INSERT INTO CUSTOMER VALUES (1, 'bom');".getBytes(StandardCharsets.UTF_8);
		byte[] all = new byte[body.length + 3];
		all[0] = (byte) 0xEF;
		all[1] = (byte) 0xBB;
		all[2] = (byte) 0xBF;
		System.arraycopy(body, 0, all, 3, body.length);
		Files.write(library.resolve("bom.bsql"), all);
		at("bom.bsql");
		Assertions.assertEquals(1, count(), output());
	}

	// ---- instance/environment safeguards: evaluated for every executed Script ----

	private void asConnectedTo(Command host, String instance, String environment) throws BroadSQLException {
		CommandTestSupport.wireInstanceAndEnvironment(host, "SAFEGUARD", instance, environment);
		interpreter.setPlatform("SAFEGUARD");
		CommandTestSupport.shareVault(interpreter, host.getDatabaseConnectionsVault());
	}

	private void atWithSafeguards(String reference, String instance, String environment) throws BroadSQLException {
		CommandExternalFile cmd = CommandTestSupport.create(CommandExternalFile.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(interpreter);
		asConnectedTo(cmd, instance, environment);
		cmd.execute("@" + reference);
	}

	@Test
	void aTaggedScriptWarnsWhenItDoesNotMatchTheConnection() throws Exception {
		lib("tagged.bsql", "-- @instance: BILLING\n-- @environment: PROD\nSELECT 1;\n");
		atWithSafeguards("tagged.bsql", "CRM", "QA");
		String out = output();
		Assertions.assertTrue(out.contains("tagged.bsql is tagged for instance BILLING"), out);
		Assertions.assertTrue(out.contains("tagged.bsql is tagged for environment PROD"), out);
	}

	@Test
	void aNestedTaggedScriptIsCheckedToo() throws Exception {
		lib("outer.bsql", "SELECT 1;\n@inner.bsql;\n");
		lib("inner.bsql", "-- @environment: PROD\nSELECT 2;\n");
		atWithSafeguards("outer.bsql", "CRM", "QA");
		Assertions.assertTrue(output().contains("inner.bsql is tagged for environment PROD"), output());
		Assertions.assertFalse(output().contains("outer.bsql is tagged"), output());
	}

	@Test
	void anExternalTaggedScriptIsCheckedToo() throws Exception {
		Path external = script(elsewhere, "ext.bsql", "-- @environment: PROD\nSELECT 1;\n");
		atWithSafeguards(external.toString(), "CRM", "QA");
		Assertions.assertTrue(output().contains("ext.bsql is tagged for environment PROD"), output());
	}

	@Test
	void aMatchingUntaggedOrAllTaggedScriptDoesNotWarn() throws Exception {
		lib("ok1.bsql", "-- @environment: QA\nSELECT 1;\n");
		lib("ok2.bsql", "-- @instance: ALL\nSELECT 2;\n");
		lib("ok3.bsql", "SELECT 3;\n");
		for (String name : List.of("ok1.bsql", "ok2.bsql", "ok3.bsql")) {
			atWithSafeguards(name, "CRM", "QA");
		}
		Assertions.assertFalse(output().contains("is tagged for"), output());
	}

	@Test
	void withNoConnectionThereIsNothingToCheckAgainstAndNothingIsSaid() throws Exception {
		lib("tagged.bsql", "-- @instance: BILLING\nSELECT 1;\n");
		at("tagged.bsql"); // interpreter platform is null: no connection context
		Assertions.assertFalse(output().contains("is tagged for"), output());
	}

	@Test
	void descriptiveMetadataNeverAffectsResolutionOrExecution() throws Exception {
		lib("desc.bsql", "-- @description: nice\n-- @tags: a,b\n-- @status: deprecated\nINSERT INTO CUSTOMER VALUES (1, 'x');\n");
		at("desc.bsql");
		Assertions.assertEquals(1, count(), output());
		Assertions.assertFalse(output().contains("is tagged for"), output());
	}
}
