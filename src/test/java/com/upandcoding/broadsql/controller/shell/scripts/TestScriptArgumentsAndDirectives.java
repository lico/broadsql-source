package com.upandcoding.broadsql.controller.shell.scripts;

import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;

/**
 * SPRINT 0110A: the named-argument grammar and evaluation (spec section 17), the {@code ON ERROR}/{@code OUTPUT}
 * grammar (11.1, 12.1), the {@code -- @params} directive (17.6), the reserved batch exit codes (14.6), and the
 * multi-word keyword whitespace rule (6.2). Pure unit tests.
 */
class TestScriptArgumentsAndDirectives {

	private static BroadSQLException parseError(String text) {
		return Assertions.assertThrows(BroadSQLException.class, () -> ScriptArguments.parse(text), text);
	}

	// ---- arguments: syntax ----

	@Test
	void noArgument() throws Exception {
		Assertions.assertTrue(ScriptArguments.parse("").isEmpty());
		Assertions.assertTrue(ScriptArguments.parse("   ").isEmpty());
		Assertions.assertTrue(ScriptArguments.parse(null).isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = { "id=1", "id = 1", "id= 1", "id =1", "  id=1  ", "id\t=\t1" })
	void whitespaceAroundTheEqualsSignIsOptional(String text) throws Exception {
		List<ScriptArguments.Argument> args = ScriptArguments.parse(text).getArguments();
		Assertions.assertEquals(1, args.size());
		Assertions.assertEquals("id", args.get(0).name());
		Assertions.assertEquals("1", args.get(0).valueText());
	}

	@Test
	void everyValueForm() throws Exception {
		List<ScriptArguments.Argument> args = ScriptArguments
				.parse("a=1 b=-2.5 c='x y; -- z' d=TRUE e=false f=NULL g=${other} h='' i='O''Brien' j='<@file.txt>'").getArguments();
		Assertions.assertEquals(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"), args.stream().map(ScriptArguments.Argument::name).toList());
		Assertions.assertEquals("'x y; -- z'", args.get(2).valueText());
		Assertions.assertEquals("${other}", args.get(6).valueText());
		Assertions.assertEquals("'O''Brien'", args.get(8).valueText());
	}

	@Test
	void thereIsNoLimitOnTheNumberOfArguments() throws Exception {
		StringBuilder text = new StringBuilder();
		for (int i = 1; i <= 50; i++) {
			text.append("p").append(i).append('=').append(i).append(' ');
		}
		Assertions.assertEquals(50, ScriptArguments.parse(text.toString()).getArguments().size());
	}

	@ParameterizedTest
	@ValueSource(strings = { "42", "42 FR", "\"value\"", "'value'", "1 id=2" })
	void positionalArgumentsAreRefusedWithTheMigrationMessage(String text) {
		BroadSQLException e = parseError(text);
		Assertions.assertTrue(e.getMessage().contains("positional script arguments (%1..%9) were removed: pass arguments as name=value and read them with ${name}"),
				e.getMessage());
	}

	@Test
	void aBareNameIsAMissingValueThatStillExplainsTheRemoval() {
		BroadSQLException e = parseError("id");
		Assertions.assertTrue(e.getMessage().startsWith("Missing value for argument id"), e.getMessage());
		Assertions.assertTrue(e.getMessage().contains("were removed"), e.getMessage());
	}

	@ParameterizedTest
	@ValueSource(strings = { "id=", "id =", "a=1 id=" })
	void missingValue(String text) {
		Assertions.assertTrue(parseError(text).getMessage().startsWith("Missing value for argument id"));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = { "id=1 id=2|Duplicate argument id", "ID=1 id=2|Duplicate argument id",
			"1d=5|Invalid argument name '1d'", "env='QA'|Invalid argument name 'env'", "true=1|Invalid argument name 'true'", "a-b=1|Invalid argument name 'a-b'",
			"=5|Missing argument name", "id=SELECT MAX(id) FROM t|queries are not allowed as argument values",
			"id=select 1|queries are not allowed", "id=WITH|queries are not allowed", "id=VALUES(1)|queries are not allowed",
			"id='abc|unterminated quoted string" })
	void argumentErrors(String text, String expected) {
		BroadSQLException e = parseError(text);
		Assertions.assertTrue(e.getMessage().contains(expected), e.getMessage());
	}

	// ---- arguments: evaluation ----

	@Test
	void valuesAreTypedExactlyLikeLet() throws Exception {
		ScriptVariables vars = new ScriptVariables();
		LinkedHashMap<String, ScriptValue> values = ScriptArguments.parse("id=42 rate=12.5 country='FR' enabled=TRUE note=NULL").evaluate(vars);
		Assertions.assertEquals("BIGINT", values.get("id").getTypeName());
		Assertions.assertEquals("DECIMAL", values.get("rate").getTypeName());
		Assertions.assertEquals("VARCHAR", values.get("country").getTypeName());
		Assertions.assertEquals("BOOLEAN", values.get("enabled").getTypeName());
		Assertions.assertTrue(values.get("note").isUntypedNull());
		Assertions.assertTrue(vars.isEmpty(), "evaluation assigns nothing");
	}

	@Test
	void aReferenceTransfersTheValueObjectItselfNeverAReparsedText() throws Exception {
		ScriptVariables vars = new ScriptVariables();
		ScriptValue typedNull = ScriptValue.fromQuery(null, Types.DATE, "DATE", "X");
		vars.assign("d", typedNull);
		vars.assign("s", ScriptValue.ofString("42"));
		LinkedHashMap<String, ScriptValue> values = ScriptArguments.parse("a=${d} b=${s} c='${s}'").evaluate(vars);
		Assertions.assertSame(typedNull, values.get("a"));
		Assertions.assertSame(vars.get("s"), values.get("b"));
		Assertions.assertEquals("VARCHAR", values.get("b").getTypeName(), "the string '42' stays a string");
		Assertions.assertEquals("${s}", values.get("c").getValue(), "a string literal argument is never interpolated");
	}

	@Test
	void everyValueIsEvaluatedBeforeAnyAssignment() throws Exception {
		ScriptVariables vars = new ScriptVariables();
		vars.assign("b", ScriptValue.ofLong(5));
		LinkedHashMap<String, ScriptValue> values = ScriptArguments.parse("a=${b} b=1").evaluate(vars);
		Assertions.assertEquals(5L, values.get("a").getValue());
		values = ScriptArguments.parse("b=1 a=${b}").evaluate(vars);
		Assertions.assertEquals(5L, values.get("a").getValue(), "argument order never changes the result");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = { "id=${undefined}|Variable undefined is not defined", "id=${1}|Malformed variable reference '${1}'",
			"id=${a}+1|Invalid value for argument id", "id=(1)|Invalid value for argument id", "id=abc|Invalid value for argument id",
			"id=1e3|Invalid value for argument id", "id='a'b|Invalid value for argument id", "id=${a}${a}|Invalid value for argument id" })
	void evaluationErrors(String text, String expected) {
		ScriptVariables vars = new ScriptVariables();
		vars.assign("a", ScriptValue.ofLong(1));
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> ScriptArguments.parse(text).evaluate(vars), text);
		Assertions.assertTrue(e.getMessage().startsWith(expected), e.getMessage());
	}

	@Test
	void theScriptReferenceIsSplitFromItsArguments() throws Exception {
		Assertions.assertArrayEquals(new String[] { "reports/x.bsql", "id=1 c='a b'" }, ScriptArguments.splitReference("reports/x.bsql id=1 c='a b'"));
		Assertions.assertArrayEquals(new String[] { "my reports/QR 13.bsql", "id=1" }, ScriptArguments.splitReference("\"my reports/QR 13.bsql\" id=1"));
		Assertions.assertArrayEquals(new String[] { "x.bsql", "" }, ScriptArguments.splitReference("x.bsql"));
		Assertions.assertArrayEquals(new String[] { "", "" }, ScriptArguments.splitReference(""));
		Assertions.assertThrows(BroadSQLException.class, () -> ScriptArguments.splitReference("\"unclosed id=1"));
	}

	@Test
	void dumpArgumentsEndAtToOrAs() throws Exception {
		Assertions.assertArrayEquals(new String[] { "region='EU' year=2026", "TO f AS CSV" }, ScriptArguments.splitBeforeToOrAs("region='EU' year=2026 TO f AS CSV", "DUMP"));
		Assertions.assertArrayEquals(new String[] { "", "AS CSV" }, ScriptArguments.splitBeforeToOrAs("AS CSV", "DUMP"));
		Assertions.assertArrayEquals(new String[] { "to=1", "TO f" }, ScriptArguments.splitBeforeToOrAs("to=1 TO f", "DUMP"), "TO= is an argument name");
		Assertions.assertArrayEquals(new String[] { "a='x TO y'", "" }, ScriptArguments.splitBeforeToOrAs("a='x TO y'", "DUMP"));
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> ScriptArguments.splitBeforeToOrAs("42 TO f", "PULL"));
		Assertions.assertTrue(e.getMessage().startsWith("PULL syntax error: unexpected '42'") && e.getMessage().contains("were removed"), e.getMessage());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = { "1|", "'a'|", "TRUE|", "NULL|", "${x}|", "|a value is required", "  |a value is required",
			"abc|a value is a number", "${x}+1|one variable reference only", "${ x}|malformed variable reference", "SELECT 1|queries are not allowed" })
	void editorFieldValidation(String value, String expectedProblem) {
		String problem = ScriptArguments.valueSyntaxProblem(value);
		if (expectedProblem == null) {
			Assertions.assertNull(problem, value);
		} else {
			Assertions.assertNotNull(problem, value);
			Assertions.assertTrue(problem.startsWith(expectedProblem), problem);
		}
	}

	@Test
	void editorValuesAreFormattedAsNamedArgumentsThatParseBack() throws Exception {
		LinkedHashMap<String, String> fields = new LinkedHashMap<>();
		fields.put("customer_id", " 42 ");
		fields.put("country", "'F R'");
		fields.put("since", "${last_run}");
		String text = ScriptArguments.format(fields);
		Assertions.assertEquals("customer_id=42 country='F R' since=${last_run}", text);
		Assertions.assertEquals(3, ScriptArguments.parse(text).getArguments().size());
	}

	// ---- @params ----

	@Test
	void paramsDirectiveForms() {
		Assertions.assertEquals(List.of("id"), EntryMetadata.parse("-- @params: id\nselect 1;").getParams());
		Assertions.assertEquals(List.of("id", "country"), EntryMetadata.parse("-- @params id, country\nselect 1;").getParams());
		Assertions.assertEquals(List.of("a", "b", "c"), EntryMetadata.parse("-- @params: a,b\n/* @params: c */\nselect 1;").getParams(), "repeatable, accumulates");
		Assertions.assertEquals(List.of("id"), EntryMetadata.parse("-- @params: id, ID ,  id\nselect 1;").getParams(), "duplicates removed for calls");
		Assertions.assertEquals(List.of("id", "ID", "id"), EntryMetadata.parse("-- @params: id, ID ,  id\nselect 1;").getDeclaredParams());
		Assertions.assertTrue(EntryMetadata.parse("select '-- @params: x';").getParams().isEmpty(), "never inside a string");
		Assertions.assertTrue(EntryMetadata.parse("select 1;").getParams().isEmpty());
		Assertions.assertEquals("none declared", EntryMetadata.parse("select 1;").getParamsDisplayValue());
		Assertions.assertEquals("customer_id, country", EntryMetadata.parse("-- @params: customer_id, country\nselect 1;").getParamsDisplayValue());
	}

	// ---- ON ERROR / OUTPUT ----

	@ParameterizedTest
	@CsvSource({ "ON ERROR STOP,STOP", "on error stop,STOP", "On   Error   Continue,CONTINUE", "ON ERROR CONTINUE,CONTINUE" })
	void onErrorForms(String statement, ErrorPolicy expected) throws Exception {
		Assertions.assertTrue(ScriptDirectives.isOnError(statement));
		Assertions.assertEquals(expected, ScriptDirectives.parseOnError(statement));
	}

	@ParameterizedTest
	@ValueSource(strings = { "ON ERROR", "ON ERROR EXIT", "ON ERROR STOP NOW", "ON ERROR RESUME" })
	void invalidOnError(String statement) {
		Assertions.assertTrue(ScriptDirectives.isOnError(statement));
		Assertions.assertThrows(BroadSQLException.class, () -> ScriptDirectives.parseOnError(statement));
	}

	@Test
	void onErrorsIsNotTheOnErrorKeyword() {
		Assertions.assertFalse(ScriptDirectives.isOnError("ON ERRORS STOP"));
	}

	@ParameterizedTest
	@CsvSource({ "OUTPUT QUIET,QUIET", "output quiet,QUIET", "Output Normal,NORMAL" })
	void outputForms(String statement, OutputMode expected) throws Exception {
		Assertions.assertEquals(expected, ScriptDirectives.parseOutput(statement));
	}

	@ParameterizedTest
	@ValueSource(strings = { "OUTPUT", "OUTPUT SILENT", "OUTPUT QUIET NOW" })
	void invalidOutput(String statement) {
		Assertions.assertThrows(BroadSQLException.class, () -> ScriptDirectives.parseOutput(statement));
	}

	// ---- multi-word keywords accept any whitespace (spec 6.2) ----

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "SHOW SCRIPT VARIABLES|SHOW SCRIPT VARIABLES|21", "show   script\tvariables|SHOW SCRIPT VARIABLES|23",
			"ON ERROR STOP|ON ERROR|8", "LIB  RUN x|LIB RUN|8", "@x.bsql|@|1", "SHOW TABLES|SHOW TABLE|-1", "LETX = 1|LET|-1", "LET x|LET|3" })
	void keywordMatching(String query, String keyword, int end) {
		Assertions.assertEquals(end, CommandUtils.keywordMatchEnd(query, keyword));
	}

	@Test
	void statusesMapToTheReservedBatchExitCodes() {
		Assertions.assertEquals(0, ScriptStatus.SUCCESS.exitCode());
		Assertions.assertEquals(1, ScriptStatus.FAILED.exitCode());
		Assertions.assertEquals(3, ScriptStatus.COMPLETED_WITH_ERRORS.exitCode());
		Assertions.assertEquals(130, ScriptStatus.CANCELLED.exitCode());
	}

	@Test
	void runIdsAreEightCharactersFromDigitsAndUpperCaseLetters() {
		for (int i = 0; i < 100; i++) {
			Assertions.assertTrue(RunIds.next().matches("[0-9A-Z]{8}"));
		}
	}

	@Test
	void theFinalStatusLine() {
		Assertions.assertEquals("load.bsql: COMPLETED_WITH_ERRORS (4 statements, 1 failed) [run 4M8T0QW2]",
				new ScriptRunResult("load.bsql", ScriptStatus.COMPLETED_WITH_ERRORS, 4, 1, "4M8T0QW2", 0, 0, true).statusLine());
		Assertions.assertEquals("cleanup.bsql: FAILED (4 statements, 1 failed) [run 7Q3K2F9A], stopped at statement 4 (line 4)",
				new ScriptRunResult("cleanup.bsql", ScriptStatus.FAILED, 4, 1, "7Q3K2F9A", 4, 4, true).statusLine());
		Assertions.assertEquals("x: SUCCESS (1 statement, 0 failed) [run R]", new ScriptRunResult("x", ScriptStatus.SUCCESS, 1, 0, "R", 0, 0, true).statusLine());
	}

	@Test
	void howARunFailsItsCallingStatement() {
		for (ScriptStatus status : ScriptStatus.values()) {
			Assertions.assertEquals(status != ScriptStatus.SUCCESS, new ScriptRunResult("x", status, 0, 0, "R", 0, 0, true).failsCallingStatement(), "top level " + status);
			Assertions.assertEquals(status == ScriptStatus.FAILED, new ScriptRunResult("x", status, 0, 0, "R", 0, 0, false).failsCallingStatement(), "nested " + status);
		}
	}
}
