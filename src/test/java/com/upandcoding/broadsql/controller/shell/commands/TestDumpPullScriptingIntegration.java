package com.upandcoding.broadsql.controller.shell.commands;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptRunResult;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptStatus;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * SPRINT 0110A: {@code DUMP}/{@code PULL} and SQL scripting (spec section 19): references bound in the
 * parenthesized source query (with the {@code AS H2 MODE APPEND} watermark bound after them), nothing else of the
 * command processed, script sources with named arguments and {@code @params}, and export only after a
 * {@code SUCCESS} run.
 */
class TestDumpPullScriptingIntegration extends ScriptingTestBase {

	private Path out(String name) {
		return Path.of(settings.getExtractFolderName()).resolve(name);
	}

	private String csv(String name) throws Exception {
		Assertions.assertTrue(Files.exists(out(name)), name + " was not written: " + output());
		return Files.readString(out(name), StandardCharsets.UTF_8);
	}

	private void seed() {
		line("INSERT INTO customer VALUES (1, 'O''Brien', 'FR'), (2, 'Bob', 'DE'), (3, 'Carol', 'FR');");
	}

	// ---- query source ----

	@Test
	void referencesInTheSourceQueryAreBound() throws Exception {
		seed();
		line("LET c = 'FR'; LET n = 'O''Brien';");
		line("DUMP (SELECT id, name FROM customer WHERE country = ${c} AND name <> ${n}) TO only_carol AS CSV;");
		String text = csv("only_carol.csv");
		Assertions.assertTrue(text.contains("Carol") && !text.contains("Bob") && !text.contains("Brien"), text + output());
	}

	@Test
	void numbersTemporalsAndNullAreBound() throws Exception {
		seed();
		line("LET min_id = 2; LET d = SELECT DATE '2026-01-31'; LET nothing = SELECT CAST(NULL AS VARCHAR(5));");
		line("DUMP (SELECT id, ${d} AS D, COALESCE(${nothing}, 'none') AS N FROM customer WHERE id >= ${min_id}) TO typed AS CSV;");
		String text = csv("typed.csv");
		Assertions.assertTrue(text.contains("2026-01-31") && text.contains("none"), text + output());
		Assertions.assertFalse(text.contains(";1;"), text);
		Assertions.assertEquals(3, text.strip().split("\n").length, "header + 2 rows: " + text);
	}

	@Test
	void anUntypedNullColumnIsBoundButRefusedByTheFlatFileWriterAsBefore() throws Exception {
		seed();
		line("LET nothing = NULL; DUMP (SELECT ${nothing} AS N FROM customer) TO untyped AS CSV;");
		Assertions.assertTrue(output().contains("Column 'N' has type NULL"), output());
		Assertions.assertFalse(Files.exists(out("untyped.csv")));
	}

	@Test
	void pullIsTheSameAsDump() throws Exception {
		seed();
		line("LET c = 'DE'; PULL (SELECT name FROM customer WHERE country = ${c}) TO pulled AS CSV;");
		Assertions.assertTrue(csv("pulled.csv").contains("Bob"), output());
	}

	@Test
	void anUndefinedReferenceExportsNothing() throws Exception {
		seed();
		line("DUMP (SELECT * FROM customer WHERE id = ${nope}) TO f AS CSV;");
		Assertions.assertTrue(output().contains("Variable nope is not defined."), output());
		Assertions.assertFalse(Files.exists(out("f.csv")));
	}

	@Test
	void theDestinationIsNeverProcessed() throws Exception {
		seed();
		line("LET file = 'other'; DUMP (SELECT 1) TO ${file} AS CSV;");
		Assertions.assertFalse(Files.exists(out("other.csv")), output());
	}

	@Test
	void aQueryWithoutReferencesIsExportedExactlyAsBefore() throws Exception {
		seed();
		line("DUMP (SELECT id FROM customer WHERE name = '${not_a_reference}' OR id = 1) TO plain AS CSV;");
		Assertions.assertTrue(csv("plain.csv").contains("1"), output());
	}

	@Test
	void appendModeBindsTheUserValuesFirstAndTheWatermarkLast() throws Exception {
		seed();
		DatabaseDefinition target = TestDatabaseConnections.newInMemoryTarget("TARGETDB");
		CommandPull pull = CommandTestSupport.create(CommandPull.class, db, console, settings);
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault();
		HashMap<String, DatabaseDefinition> connections = new HashMap<>();
		connections.put("TARGETDB", target);
		vault.setDatabaseConnections(connections);
		pull.setDatabaseConnectionsVault(vault);
		pull.setConsoleCommandInterpreter(interpreter);

		line("LET c = 'FR';");
		pull.execute("PULL (SELECT id, name FROM customer WHERE country = ${c}) TO TARGETDB.T AS H2 MODE APPEND KEY(ID)");
		line("INSERT INTO customer VALUES (4, 'Dan', 'FR'), (5, 'Eve', 'DE');");
		pull.execute("PULL (SELECT id, name FROM customer WHERE country = ${c}) TO TARGETDB.T AS H2 MODE APPEND KEY(ID)");
		try (Connection c = DriverManager.getConnection(target.getUrl()); Statement s = c.createStatement();
				ResultSet rs = s.executeQuery("SELECT LISTAGG(CAST(ID AS VARCHAR), ',') WITHIN GROUP (ORDER BY ID) FROM \"T\"")) {
			rs.next();
			Assertions.assertEquals("1,3,4", rs.getString(1), "FR rows only, the second pull added only the delta: " + output());
		}
		Assertions.assertTrue(output().contains("1 row(s) pulled into TARGETDB.T (appended"), output());
	}

	// ---- script sources ----

	@Test
	void dumpLibAndDumpAtPassNamedArgumentsAndRespectParams() throws Exception {
		seed();
		lib("monthly.bsql", "-- @params: region\nLET m = SELECT MAX(id) FROM customer;\nSELECT id, name FROM customer WHERE country = ${region} AND id <= ${m};\n");
		line("DUMP LIB monthly.bsql region='DE' TO revenue_de AS CSV;");
		Assertions.assertTrue(csv("revenue_de.csv").contains("Bob"), output());
		Assertions.assertFalse(csv("revenue_de.csv").contains("Carol"));
		line("DUMP @monthly.bsql region='FR' TO revenue_fr AS CSV;");
		Assertions.assertTrue(csv("revenue_fr.csv").contains("Carol"), output());
		Assertions.assertEquals("FR", value("region"), "arguments persist like any assignment");

		clearOutput();
		line("LET region = 'DE'; DUMP LIB monthly.bsql TO missing AS CSV;");
		Assertions.assertTrue(output().contains("monthly.bsql requires argument region (declared in @params)"), output());
		Assertions.assertTrue(output().contains("not exported: the script finished with status FAILED"), output());
		Assertions.assertFalse(Files.exists(out("missing.csv")));
	}

	@Test
	void aLetQueryNeverBecomesTheCapturedResult() throws Exception {
		seed();
		lib("only_let.bsql", "LET m = SELECT MAX(id) FROM customer;\n");
		line("DUMP LIB only_let.bsql TO f AS CSV;");
		Assertions.assertTrue(output().contains("produced no exportable tabular result"), output());
		lib("let_after.bsql", "SELECT name FROM customer WHERE id = 2;\nLET m = SELECT MAX(id) FROM customer;\n");
		line("DUMP LIB let_after.bsql TO g AS CSV;");
		Assertions.assertTrue(csv("g.csv").contains("Bob"), "the final tabular result is the SELECT, not the LET: " + output());
	}

	@Test
	void moreThanNineTypedArguments() throws Exception {
		seed();
		StringBuilder args = new StringBuilder();
		StringBuilder in = new StringBuilder();
		for (int i = 1; i <= 12; i++) {
			args.append(" a").append(i).append('=').append(i);
			in.append(i == 1 ? "" : ", ").append("${a").append(i).append('}');
		}
		lib("in.bsql", "SELECT id FROM customer WHERE id IN (" + in + ");\n");
		line("DUMP LIB in.bsql" + args + " TO twelve AS CSV;");
		Assertions.assertEquals(4, csv("twelve.csv").strip().split("\n").length, csv("twelve.csv") + output());
	}

	@ParameterizedTest
	@ValueSource(strings = { "INSERT INTO nope VALUES (1);\nSELECT 1;\n", "ON ERROR STOP;\nSELECT 1;\nINSERT INTO nope VALUES (1);\n", "SELECT 'oops;\n" })
	void anythingButSuccessExportsNothing(String body) throws Exception {
		lib("s.bsql", body);
		line("DUMP LIB s.bsql TO nothing AS CSV;");
		Assertions.assertTrue(output().contains("DUMP LIB s.bsql not exported: the script finished with status"), output());
		Assertions.assertFalse(Files.exists(out("nothing.csv")));
	}

	@Test
	void aCancelledScriptExportsNothing() throws Exception {
		interpreter.getCommands().put("CANCEL PROBE", new Command("CANCEL PROBE") {
			@Override
			public void execute(String query) {
				CommandCancellation.request();
			}
		});
		lib("c.bsql", "SELECT 1;\nCANCEL PROBE;\nSELECT 2;\n");
		line("DUMP LIB c.bsql TO cancelled AS CSV;");
		Assertions.assertFalse(Files.exists(out("cancelled.csv")), output());
		Assertions.assertTrue(output().contains("CANCELLED"), output());
	}

	@Test
	void aSuccessfulScriptExports() throws Exception {
		seed();
		lib("ok.bsql", "SELECT name FROM customer WHERE id = 3;\n");
		ScriptRunResult r = run("DUMP LIB ok.bsql TO ok AS CSV;");
		Assertions.assertEquals(ScriptStatus.SUCCESS, r.getStatus());
		Assertions.assertTrue(csv("ok.csv").contains("Carol"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "DUMP LIB ok.bsql 42 TO f AS CSV", "PULL @ok.bsql FR TO f AS CSV" })
	void aPositionalArgumentIsADumpSyntaxError(String statement) throws Exception {
		lib("ok.bsql", "SELECT 1;\n");
		line(statement + ";");
		Assertions.assertTrue(output().contains("syntax error: unexpected") && output().contains("were removed"), output());
		Assertions.assertFalse(Files.exists(out("f.csv")));
	}

	@Test
	void aScriptSourceNestedInAScriptIsANestedRun() throws Exception {
		seed();
		lib("src.bsql", "SELECT name FROM customer;\n");
		lib("outer.bsql", "DUMP LIB src.bsql TO nested AS CSV;\n");
		ScriptRunResult r = run("@outer.bsql;");
		Assertions.assertEquals(ScriptStatus.SUCCESS, r.getStatus(), output());
		Assertions.assertEquals(2, r.getExecuted(), "the DUMP statement + the nested run's statement");
		Assertions.assertEquals(1, occurrences(output(), "SUCCESS ("), "one status line: " + output());
		Assertions.assertTrue(Files.exists(out("nested.csv")));
	}
}
