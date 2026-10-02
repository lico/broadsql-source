package com.upandcoding.broadsql.controller.shell.commands;

import java.sql.Types;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.shell.scripts.ScriptRunResult;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptStatus;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptValue;

/**
 * SPRINT 0110A: named script arguments for {@code @} and {@code LIB RUN} (spec section 17), {@code -- @params}
 * (17.6), the one shared namespace (7.4, 7.5, 16), atomicity of a call (17.3), and the removal of {@code %1..%9}
 * (25.2), end to end.
 */
class TestNamedScriptArguments extends ScriptingTestBase {

	private void echoScript() throws Exception {
		lib("x.bsql", "INSERT INTO customer VALUES (1, CAST(${id} AS VARCHAR), 'X');\n");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = { "id=1|1|BIGINT", "id='A'|A|VARCHAR", "id=TRUE|TRUE|BOOLEAN", "id=FALSE|FALSE|BOOLEAN",
			"id=1.50|1.50|DECIMAL" })
	void everyLiteralFormIsTypedAndReachesTheScript(String argument, String stored, String type) throws Exception {
		echoScript();
		for (String call : new String[] { "@x.bsql " + argument, "LIB RUN x.bsql " + argument }) {
			line("DELETE FROM customer;");
			ScriptRunResult r = run(call + ";");
			Assertions.assertEquals(ScriptStatus.SUCCESS, r.getStatus(), call + ": " + output());
			Assertions.assertEquals(stored, sql("SELECT NAME FROM CUSTOMER WHERE ID = 1"), call);
			Assertions.assertEquals(type, var("id").getTypeName());
		}
	}

	@Test
	void nullAndTypedNullAreTransmitted() throws Exception {
		lib("n.bsql", "INSERT INTO customer VALUES (1, ${v}, ${w});\n");
		line("LET t = SELECT CAST(NULL AS VARCHAR(10));");
		ScriptRunResult r = run("@n.bsql v=NULL w=${t};");
		Assertions.assertEquals(ScriptStatus.SUCCESS, r.getStatus(), output());
		Assertions.assertTrue(var("v").isUntypedNull());
		Assertions.assertSame(var("t"), var("w"), "typed NULL transferred as the same value object");
		Assertions.assertEquals(Types.VARCHAR, var("w").getJdbcType());
		Assertions.assertEquals(1L, ((Number) sql("SELECT COUNT(*) FROM CUSTOMER WHERE NAME IS NULL AND COUNTRY IS NULL")).longValue());
	}

	@Test
	void aReferenceArgumentTransfersTheTypedValueObjectNeverText() throws Exception {
		lib("d.bsql", "LET seen = ${day};\n");
		line("LET d = SELECT DATE '2026-01-31';");
		run("@d.bsql day=${d};");
		Assertions.assertSame(var("d"), var("day"));
		Assertions.assertSame(var("d"), var("seen"));
		Assertions.assertEquals(java.time.LocalDate.of(2026, 1, 31), value("seen"));
	}

	@Test
	void manyArgumentsInAnyOrder() throws Exception {
		StringBuilder sum = new StringBuilder("LET total = SELECT 0");
		StringBuilder args = new StringBuilder();
		for (int i = 12; i >= 1; i--) {
			args.append(" p").append(i).append('=').append(i);
			sum.append(" + ${p").append(13 - i).append('}');
		}
		lib("many.bsql", sum + ";\n");
		Assertions.assertEquals(ScriptStatus.SUCCESS, run("@many.bsql" + args + ";").getStatus(), output());
		Assertions.assertEquals(78L, value("total"), "more than nine arguments, order irrelevant");
	}

	@Test
	void fiftyArguments() throws Exception {
		StringBuilder args = new StringBuilder();
		for (int i = 1; i <= 50; i++) {
			args.append(" a").append(i).append('=').append(i);
		}
		lib("fifty.bsql", "LET last = ${a50};\n");
		Assertions.assertEquals(ScriptStatus.SUCCESS, run("@fifty.bsql" + args + ";").getStatus(), output());
		Assertions.assertEquals(50L, value("last"));
	}

	@Test
	void argumentsPersistAfterTheScriptReturnsWithTheirLastValue() throws Exception {
		lib("child.bsql", "LET customer_id = ${customer_id} ;\nLET other = 'set by child';\n");
		line("LET customer_id = 10;");
		run("@child.bsql customer_id=42;");
		Assertions.assertEquals(42L, value("customer_id"), "10 is not restored");
		Assertions.assertEquals("set by child", value("other"));
		lib("reassigns.bsql", "LET customer_id = 99;\n");
		run("@reassigns.bsql customer_id=1;");
		Assertions.assertEquals(99L, value("customer_id"), "the value it last received");
	}

	@Test
	void evaluationHappensBeforeAssignment() throws Exception {
		lib("ab.bsql", "LET seen_a = ${a}; LET seen_b = ${b};\n");
		line("LET b = 5;");
		run("@ab.bsql a=${b} b=1;");
		Assertions.assertEquals(5L, value("seen_a"));
		Assertions.assertEquals(1L, value("seen_b"));
		line("LET b = 5;");
		run("@ab.bsql b=1 a=${b};");
		Assertions.assertEquals(5L, value("seen_a"), "order is irrelevant");
	}

	// ---- atomicity: a failing call assigns none of its arguments (spec 17.3) ----

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = { "invalid literal|@ok.bsql a=1 b=abc", "undefined source|@ok.bsql a=1 b=${undefined}",
			"malformed reference|@ok.bsql a=1 b=${1}", "query value|@ok.bsql a=1 b=SELECT 1", "duplicate|@ok.bsql a=1 A=2",
			"missing script|@missing.bsql a=1 b=2", "invalid syntax in the script|@unterminated.bsql a=1 b=2", "missing @params|@needs.bsql a=1 b=2",
			"invalid @params declaration|@badparams.bsql a=1 b=2", "positional|@ok.bsql a=1 42" })
	void aFailedCallAssignsNothing(String category, String call) throws Exception {
		lib("ok.bsql", "SELECT 1;\n");
		lib("unterminated.bsql", "SELECT 'x;\n");
		lib("needs.bsql", "-- @params: a, b, c\nSELECT 1;\n");
		lib("badparams.bsql", "-- @params: true\nSELECT 1;\n");
		line("LET a = 'before a'; LET b = 'before b';");
		ScriptRunResult r = run(call + ";");
		Assertions.assertEquals(ScriptStatus.FAILED, r.getStatus(), category + ": " + output());
		Assertions.assertEquals(0, r.getExecuted());
		Assertions.assertEquals("before a", value("a"), category);
		Assertions.assertEquals("before b", value("b"), category);
		Assertions.assertTrue(output().contains("FAILED (0 statements, 0 failed)"), output());
	}

	@Test
	void recursionAndDepthAssignNothing() throws Exception {
		lib("self.bsql", "LET depth_seen = ${d};\n@self.bsql d=2;\n");
		line("LET d = 'before';");
		run("@self.bsql d=1;");
		Assertions.assertEquals(1L, value("d"), "the recursive call assigned nothing, the first call did");
		Assertions.assertTrue(output().contains("Recursive script execution"), output());

		for (int i = 1; i <= 33; i++) {
			lib("chain/s" + i + ".bsql", "@./s" + (i + 1) + ".bsql level=" + (i + 1) + ";\n");
		}
		lib("chain/s34.bsql", "SELECT 1;\n");
		run("@chain/s1.bsql level=1;");
		Assertions.assertEquals(32L, value("level"), "the call that exceeded the depth limit (level 33) assigned nothing");
	}

	// ---- @params (spec 17.6) ----

	@Test
	void allDeclaredArgumentsGivenRuns() throws Exception {
		lib("r.bsql", "-- @params: customer_id, country\nINSERT INTO customer VALUES (${customer_id}, 'x', ${country});\n");
		Assertions.assertEquals(ScriptStatus.SUCCESS, run("@r.bsql country='FR' customer_id=7 extra=1;").getStatus(), output());
		Assertions.assertEquals(1L, value("extra"), "an argument not declared is accepted");
	}

	@Test
	void anExistingSessionVariableDoesNotSatisfyTheDeclarationButAnExplicitCopyDoes() throws Exception {
		lib("r.bsql", "-- @params: customer_id\nINSERT INTO customer VALUES (${customer_id}, 'x', 'FR');\n");
		line("LET customer_id = 42;");
		ScriptRunResult r = run("@r.bsql;");
		Assertions.assertEquals(ScriptStatus.FAILED, r.getStatus());
		Assertions.assertTrue(output().contains("r.bsql requires argument customer_id (declared in @params)"), output());
		Assertions.assertEquals(0L, count("CUSTOMER"));
		Assertions.assertEquals(ScriptStatus.SUCCESS, run("@r.bsql customer_id=${customer_id};").getStatus(), output());
		Assertions.assertEquals(1L, count("CUSTOMER"));
	}

	@Test
	void everyMissingNameIsListed() throws Exception {
		lib("r.bsql", "-- @params: a, b, c\nSELECT 1;\n");
		run("@r.bsql b=1;");
		Assertions.assertTrue(output().contains("requires arguments a, c (declared in @params)"), output());
	}

	@Test
	void paramsDoNotCreateALocalScope() throws Exception {
		lib("r.bsql", "-- @params: id\nLET id = ${id} ;\n");
		line("LET id = 1;");
		run("@r.bsql id=2;");
		Assertions.assertEquals(2L, value("id"), "the declared argument is an ordinary shared session variable afterwards");
	}

	// ---- one shared namespace (spec 7.4, 7.5, 16) ----

	@Test
	void nestedScriptsReadAndWriteTheSameVariablesAsThePrompt() throws Exception {
		line("CREATE TABLE ORDERS (ID INT, CUSTOMER_ID INT, AMOUNT INT);");
		line("INSERT INTO customer VALUES (1, 'a', 'FR'), (2, 'b', 'FR'); INSERT INTO orders VALUES (1, 2, 150), (2, 2, 50), (3, 1, 500);");
		lib("parent.bsql", "LET max_id = SELECT MAX(id) FROM customer;\n@child.bsql threshold=100;\nECHO 'Child computed ${order_count} orders for ${max_id}';\n");
		lib("child.bsql", "LET order_count = SELECT COUNT(*) FROM orders WHERE customer_id = ${max_id} AND amount > ${threshold};\n"
				+ "LET max_id = SELECT MAX(id) FROM customer WHERE id < ${max_id};\n");
		run("@parent.bsql;");
		Assertions.assertTrue(output().contains("Child computed 1 orders for 1"), "the parent sees the child's values: " + output());
		Assertions.assertEquals(100L, value("threshold"));
		Assertions.assertEquals(1L, value("order_count"));
		Assertions.assertEquals(1L, value("max_id"));
	}

	@Test
	void assignmentsCompletedBeforeAFailureOrStopRemain() throws Exception {
		lib("s.bsql", "ON ERROR STOP;\nLET kept = 1;\nINSERT INTO nope VALUES (1);\nLET never = 1;\n");
		run("@s.bsql;");
		Assertions.assertEquals(1L, value("kept"));
		Assertions.assertNull(var("never"));
	}

	@Test
	void variablesBelongToTheSessionNotToThePhysicalConnection() throws Exception {
		line("LET cid = SELECT 42;");
		db.close(false); // DISCONNECT, then a new physical connection
		db.connect();
		line("LET again = ${cid}; LET bound = SELECT ${cid} + 0;");
		Assertions.assertEquals(42L, value("cid"));
		Assertions.assertSame(var("cid"), var("again"));
		Assertions.assertEquals(42L, value("bound"), "bound on the new connection: " + output());
	}

	// ---- %1..%9 removed (spec 25.2) ----

	@ParameterizedTest
	@ValueSource(strings = { "@report.bsql 42 FR", "@report.bsql 42", "@report.bsql \"value\"", "LIB RUN report.bsql 42" })
	void positionalCallsFailWithAMigrationMessage(String call) throws Exception {
		lib("report.bsql", "SELECT 1;\n");
		ScriptRunResult r = run(call + ";");
		Assertions.assertEquals(ScriptStatus.FAILED, r.getStatus(), output());
		Assertions.assertTrue(output().contains("positional script arguments (%1..%9) were removed: pass arguments as name=value and read them with ${name}"), output());
	}

	@Test
	void percentDigitIsOrdinaryTextEverywhere() throws Exception {
		lib("pct.bsql", "INSERT INTO customer VALUES (1, '%1', 'X');\nINSERT INTO customer VALUES (2, 'a' || '%2b', 'X');\nECHO 'value %1';\n");
		ScriptRunResult r = run("@pct.bsql;");
		Assertions.assertEquals(ScriptStatus.SUCCESS, r.getStatus(), output());
		Assertions.assertEquals("%1", sql("SELECT NAME FROM CUSTOMER WHERE ID = 1"));
		Assertions.assertEquals("a%2b", sql("SELECT NAME FROM CUSTOMER WHERE ID = 2"));
		Assertions.assertTrue(output().contains("value %1"), output());
		Assertions.assertFalse(output().contains("%%"), "the statement echo shows the statement as written: " + output());
	}

	@Test
	void aLikePatternIsNoLongerAlteredAtRunTime() throws Exception {
		line("INSERT INTO customer VALUES (1, 'x1y', 'FR'), (2, 'xy', 'FR');");
		lib("like.bsql", "LET n = SELECT COUNT(*) FROM customer WHERE name LIKE '%1%';\n");
		run("@like.bsql a=9;");
		Assertions.assertEquals(1L, value("n"), output());
	}

	@Test
	void theOldMissingAndExtraParameterMessagesAreGone() throws Exception {
		lib("old.bsql", "SELECT '%2';\n");
		run("@old.bsql a=1 b=2 c=3;");
		Assertions.assertFalse(output().contains("enough parameters"), output());
		Assertions.assertFalse(output().contains("extra parameter(s) ignored"), output());
	}

	@Test
	void libShowDisplaysTheDeclaredParameters() throws Exception {
		lib("withp.bsql", "-- @params: customer_id, country\nSELECT 1;\n");
		lib("without.bsql", "SELECT 1;\n");
		line("LIB SHOW withp.bsql;");
		Assertions.assertTrue(output().contains("Parameters: customer_id, country"), output());
		clearOutput();
		line("LIB SHOW without.bsql;");
		Assertions.assertTrue(output().contains("Parameters: none declared"), output());
	}

	@Test
	void anArgumentStringLiteralIsNeverInterpolatedOrMacroExpanded() throws Exception {
		lib("lit.bsql", "SELECT 1;\n");
		line("LET x = 'X';");
		run("@lit.bsql label='${x}' file='<@nowhere.txt>';");
		Assertions.assertEquals("${x}", value("label"));
		Assertions.assertEquals("<@nowhere.txt>", value("file"));
	}

	@Test
	void anArgumentValueCanContainSpacesSemicolonsAndCommentMarkers() throws Exception {
		lib("lit.bsql", "SELECT 1;\n");
		run("@lit.bsql v='a b; c -- d';");
		ScriptValue v = var("v");
		Assertions.assertEquals("a b; c -- d", v.getValue(), output());
	}
}
