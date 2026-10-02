package com.upandcoding.broadsql.controller.shell.commands;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.shell.scripts.ScriptValue;
import com.upandcoding.broadsql.dao.LastCopyableResultHolder;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.export.TabularResultCapture;
import com.upandcoding.broadsql.dao.export.TabularResultSpool;

/**
 * SPRINT 0110A: {@code LET} at the prompt, end to end on H2 (spec section 8): every valid and invalid form, literal
 * typing, exact copy, the strict scalar query rule, isolation from every result feature, and atomicity (a failed
 * assignment never changes the variable).
 */
class TestLetAssignment extends ScriptingTestBase {

	// ---- valid forms ----

	@ParameterizedTest
	@ValueSource(strings = { "LET x=1", "LET x = 1", "LET x= 1", "LET x =1", "let x = 1", "Let x = 1", "LET   x   =   1  ", "LET\tx\t=\t1" })
	void whitespaceAndKeywordCaseVariants(String statement) {
		line(statement + ";");
		Assertions.assertEquals(1L, value("x"), output());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = { "-1|-1|BIGINT", "1.5|1.5|DECIMAL", "-1.5|-1.5|DECIMAL", "'abc'|abc|VARCHAR",
			"'O''Brien'|O'Brien|VARCHAR", "''||VARCHAR", "TRUE|true|BOOLEAN", "true|true|BOOLEAN", "False|false|BOOLEAN" })
	void literalForms(String literal, String expected, String type) {
		line("LET x = " + literal + ";");
		ScriptValue x = var("x");
		Assertions.assertNotNull(x, output());
		Assertions.assertEquals(expected == null ? "" : expected, String.valueOf(x.getValue()));
		Assertions.assertEquals(type, x.getTypeName());
	}

	@Test
	void theNullLiteralDefinesAnUntypedNull() {
		line("LET nothing = NULL;");
		Assertions.assertTrue(var("nothing").isUntypedNull());
		Assertions.assertTrue(output().contains("nothing = NULL (NULL)"), output());
	}

	@Test
	void aConfirmationLineIsPrinted() {
		line("LET customer_id = 42;");
		Assertions.assertTrue(output().contains("customer_id = 42 (BIGINT)"), output());
		line("LET country = 'FR';");
		Assertions.assertTrue(output().contains("country = 'FR' (VARCHAR)"), output());
	}

	@Test
	void stringLiteralsAreNeverInterpolated() {
		line("LET y = 5; LET x = '${y}';");
		Assertions.assertEquals("${y}", value("x"));
	}

	@Test
	void aStringLiteralCanContainSemicolonsAndCommentMarkers() {
		line("LET x = 'a;b -- c /* d */';");
		Assertions.assertEquals("a;b -- c /* d */", value("x"));
	}

	@Test
	void reassignmentReplacesValueAndType() {
		line("LET batch = 100; LET batch = 'B-2026-10';");
		Assertions.assertEquals("B-2026-10", value("batch"));
		Assertions.assertEquals("VARCHAR", var("batch").getTypeName());
	}

	// ---- copy ----

	@Test
	void aCopyIsTheSameValueObjectForEveryKind() throws Exception {
		line("LET s = 'abc'; LET n = 42; LET d = 1.25; LET b = TRUE; LET u = NULL;");
		line("LET dt = SELECT DATE '2026-01-31'; LET t = SELECT TIME '10:20:30'; LET ts = SELECT TIMESTAMP '2026-01-31 10:20:30.5';");
		line("LET tz = SELECT TIMESTAMP WITH TIME ZONE '2026-01-31 10:20:30+02:00'; LET ttz = SELECT TIME WITH TIME ZONE '10:20:30+02:00';");
		line("LET tn = SELECT CAST(NULL AS INTEGER);");
		for (String name : new String[] { "s", "n", "d", "b", "u", "dt", "t", "ts", "tz", "ttz", "tn" }) {
			line("LET copy_" + name + " = ${" + name + "};");
			Assertions.assertSame(var(name), var("copy_" + name), name + ": " + output());
		}
		Assertions.assertTrue(var("copy_tn").isNull() && !var("copy_tn").isUntypedNull(), "typed NULL stays typed");
		Assertions.assertEquals(Types.INTEGER, var("copy_tn").getJdbcType());
		Assertions.assertTrue(var("copy_u").isUntypedNull());
	}

	@Test
	void selfCopyChangesNothing() {
		line("LET x = 7; LET x = ${x};");
		Assertions.assertEquals(7L, value("x"));
	}

	@Test
	void anUndefinedCopySourceLeavesTheTargetUnchanged() {
		line("LET x = 1;");
		line("LET x = ${nope};");
		Assertions.assertTrue(output().contains("Variable nope is not defined."), output());
		Assertions.assertEquals(1L, value("x"));
		line("LET fresh = ${nope};");
		Assertions.assertNull(var("fresh"), "an undefined name stays undefined");
	}

	// ---- invalid forms ----

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = { "LET|Missing variable name", "LET 1x = 1|Invalid variable name '1x'",
			"LET a-b = 1|Invalid variable name 'a-b'", "LET ENV = 1|reserved", "LET env = 'QA'|reserved", "LET NULL = 1|reserved", "LET TRUE = 1|reserved",
			"LET false = 1|reserved", "LET x|Missing = after LET x", "LET x 5|Missing = after LET x", "LET x =|Missing value", "LET x = abc|Invalid value 'abc'",
			"LET x = +1|Invalid value", "LET x = .5|Invalid value", "LET x = 1.|Invalid value", "LET x = 1e5|Invalid value", "LET x = 1 2|Invalid value",
			"LET x = 'a' 'b'|Invalid value", "LET x = (SELECT 1)|Invalid value", "LET x = ${y} + 1|Invalid value",
			"LET x = ${y}${z}|Invalid value", "LET x = ${y} ${z}|Invalid value", "LET x = ${bad name}|Malformed variable reference",
			"LET x = ${1}|Malformed variable reference", "LET x = ${undefined_var}|Variable undefined_var is not defined" })
	void invalidFormsFailAndLeaveTheVariableUnchanged(String statement, String expected) {
		line("LET x = 'before'; LET y = 1; LET z = 2;");
		clearOutput();
		line(statement + ";");
		Assertions.assertTrue(output().contains(expected), statement + " -> " + output());
		Assertions.assertTrue(output().contains("ERROR"), output());
		Assertions.assertEquals("before", value("x"), statement);
		Assertions.assertEquals("VARCHAR", var("x").getTypeName());
	}

	@Test
	void concatenationIsNotAnExpressionLanguage() {
		line("LET x = 'before';");
		line("LET x = 'a' || 'b';");
		Assertions.assertTrue(output().contains("Invalid value"), output());
		Assertions.assertEquals("before", value("x"));
	}

	@Test
	void letWithoutANameDoesNotList() {
		line("LET a = 1;");
		clearOutput();
		line("LET;");
		Assertions.assertTrue(output().contains("Missing variable name") && output().contains("SHOW SCRIPT VARIABLES"), output());
		Assertions.assertFalse(output().contains("a = 1"), output());
	}

	// ---- scalar query ----

	@Test
	void exactlyOneRowAndOneColumnIsAssignedWithTheDriverTypeName() throws Exception {
		line("INSERT INTO CUSTOMER VALUES (1, 'a', 'FR'), (2, 'b', 'DE');");
		line("LET max_id = SELECT MAX(id) FROM customer;");
		Assertions.assertEquals(2L, value("max_id"), output());
		Assertions.assertEquals("INTEGER", var("max_id").getTypeName());
		Assertions.assertTrue(output().contains("max_id = 2 (INTEGER)"), output());
	}

	@ParameterizedTest
	@ValueSource(strings = { "SELECT 1", "select 1", "WITH c AS (SELECT 1 AS v) SELECT v FROM c", "VALUES (1)", "values 1" })
	void queryStarters(String query) {
		line("LET q = " + query + ";");
		Assertions.assertEquals(1L, value("q"), output());
	}

	@Test
	void sqlNullInOneRowIsATypedNullAndAnAggregateOnAnEmptyTableIsNotAZeroRowError() {
		line("LET last = SELECT MAX(id) FROM customer WHERE 1 = 0;");
		ScriptValue last = var("last");
		Assertions.assertNotNull(last, output());
		Assertions.assertTrue(last.isNull());
		Assertions.assertFalse(last.isUntypedNull());
		Assertions.assertEquals(Types.INTEGER, last.getJdbcType());
		Assertions.assertTrue(output().contains("last = NULL (INTEGER)"), output());
	}

	@Test
	void everySupportedTypeIsReadTyped() {
		line("CREATE TABLE TYPES_T (C CHAR(3), VC VARCHAR(10), TI TINYINT, SI SMALLINT, I INT, BI BIGINT, DE DECIMAL(10,3), NU NUMERIC(5,1), RE REAL, "
				+ "FL FLOAT, DO DOUBLE PRECISION, BO BOOLEAN, DA DATE, TM TIME(6), TS TIMESTAMP(6), TTZ TIME(0) WITH TIME ZONE, TSTZ TIMESTAMP(3) WITH TIME ZONE);");
		line("INSERT INTO TYPES_T VALUES ('ab', 'xyz', 1, 2, 3, 4, 5.125, 6.5, 1.5, 2.5, 3.5, TRUE, DATE '2026-01-31', TIME '10:20:30.25', "
				+ "TIMESTAMP '2026-01-31 10:20:30.125', TIME WITH TIME ZONE '10:20:30+02:00', TIMESTAMP WITH TIME ZONE '2026-01-31 10:20:30.5+02:00');");
		String[][] expected = { { "C", "ab ", "CHARACTER" }, { "VC", "xyz", "CHARACTER VARYING" }, { "TI", "1", "TINYINT" }, { "SI", "2", "SMALLINT" },
				{ "I", "3", "INTEGER" }, { "BI", "4", "BIGINT" }, { "DE", "5.125", "DECIMAL" }, { "NU", "6.5", "NUMERIC" }, { "BO", "true", "BOOLEAN" } };
		for (String[] e : expected) {
			line("LET v_" + e[0] + " = SELECT " + e[0] + " FROM TYPES_T;");
			Assertions.assertEquals(e[1], String.valueOf(value("v_" + e[0])), e[0] + ": " + output());
			Assertions.assertEquals(e[2], var("v_" + e[0]).getTypeName(), e[0]);
		}
		Assertions.assertInstanceOf(Long.class, value("v_TI"));
		Assertions.assertInstanceOf(Long.class, value("v_I"));
		Assertions.assertInstanceOf(BigDecimal.class, value("v_DE"));
		line("LET v_RE = SELECT RE FROM TYPES_T; LET v_FL = SELECT FL FROM TYPES_T; LET v_DO = SELECT DO FROM TYPES_T;");
		Assertions.assertEquals(1.5d, ((Number) value("v_RE")).doubleValue());
		Assertions.assertEquals(2.5d, ((Number) value("v_FL")).doubleValue());
		Assertions.assertEquals(3.5d, ((Number) value("v_DO")).doubleValue());
		line("LET v_DA = SELECT DA FROM TYPES_T; LET v_TM = SELECT TM FROM TYPES_T; LET v_TS = SELECT TS FROM TYPES_T;");
		line("LET v_TTZ = SELECT TTZ FROM TYPES_T; LET v_TSTZ = SELECT TSTZ FROM TYPES_T;");
		Assertions.assertEquals(LocalDate.of(2026, 1, 31), value("v_DA"));
		Assertions.assertEquals(LocalTime.of(10, 20, 30, 250_000_000), value("v_TM"));
		Assertions.assertEquals(LocalDateTime.of(2026, 1, 31, 10, 20, 30, 125_000_000), value("v_TS"));
		Assertions.assertEquals(OffsetTime.of(10, 20, 30, 0, ZoneOffset.ofHours(2)), value("v_TTZ"));
		Assertions.assertEquals(OffsetDateTime.of(2026, 1, 31, 10, 20, 30, 500_000_000, ZoneOffset.ofHours(2)), value("v_TSTZ"));
		Assertions.assertTrue(output().contains("v_TSTZ = 2026-01-31 10:20:30.5+02:00"), output());
	}

	@Test
	void everyUnsupportedH2TypeIsRefusedAndNamed() {
		line("CREATE TABLE ODD_T (CL CLOB, BL BLOB, BIN BINARY(2), VB VARBINARY(4), AR INTEGER ARRAY, JS JSON, UU UUID, IV INTERVAL DAY, "
				+ "EN ENUM('A','B'), GE GEOMETRY, RO ROW(A INT), JO JAVA_OBJECT);");
		line("INSERT INTO ODD_T VALUES ('c', X'01', X'0102', X'03', ARRAY[1], JSON '{}', RANDOM_UUID(), INTERVAL '1' DAY, 'A', NULL, ROW(1), NULL);");
		for (String column : new String[] { "CL", "BL", "BIN", "VB", "AR", "JS", "UU", "IV", "EN", "GE", "RO", "JO" }) {
			line("LET v = 'before';");
			clearOutput();
			line("LET v = SELECT " + column + " FROM ODD_T;");
			Assertions.assertTrue(output().contains("which a variable cannot hold"), column + ": " + output());
			Assertions.assertEquals("before", value("v"), column);
		}
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = { "SELECT id FROM customer WHERE 1 = 0|The query returned no row",
			"SELECT id FROM customer|The query returned more than one row", "SELECT id, name FROM customer WHERE id = 1|The query returned 2 columns",
			"SELECT * FROM customer WHERE id = 1|The query returned 3 columns", "SELECT * FROM nope|NOPE", "SELECT 1 +|Syntax error",
			"SELECT ${undefined_var}|Variable undefined_var is not defined", "SELECT ${ x}|Malformed variable reference" })
	void scalarQueryErrorsLeaveThePreviousValueUnchanged(String query, String expected) {
		line("INSERT INTO CUSTOMER VALUES (1, 'a', 'FR'), (2, 'b', 'DE'), (3, 'c', 'FR');");
		line("LET id = 'before';");
		clearOutput();
		line("LET id = " + query + ";");
		Assertions.assertTrue(output().contains(expected), output());
		Assertions.assertEquals("before", value("id"));
	}

	@Test
	void aMultiRowErrorReadsAtMostTwoRowsOfAHugeResult() {
		long start = System.nanoTime();
		line("LET id = SELECT X FROM SYSTEM_RANGE(1, 50000000);");
		long millis = (System.nanoTime() - start) / 1_000_000;
		Assertions.assertTrue(output().contains("more than one row"), output());
		Assertions.assertTrue(millis < 10_000, "a 50 million row result must not be fetched: " + millis + " ms");
	}

	@Test
	void theQueryMayReferenceOtherVariablesAsBinds() {
		line("INSERT INTO CUSTOMER VALUES (1, 'a', 'FR'), (2, 'b', 'DE');");
		line("LET c = 'DE'; LET name_of = SELECT name FROM customer WHERE country = ${c};");
		Assertions.assertEquals("b", value("name_of"), output());
	}

	@Test
	void theQueryRunsInTheCurrentTransactionAndSeesUncommittedWork() throws Exception {
		autocommit(false);
		line("INSERT INTO CUSTOMER VALUES (9, 'pending', 'FR');");
		line("LET n = SELECT COUNT(*) FROM customer;");
		Assertions.assertEquals(1L, value("n"));
		Assertions.assertEquals(0L, ((Number) committed("SELECT COUNT(*) FROM CUSTOMER")).longValue(), "not committed by LET");
		Assertions.assertTrue(db.isHasUncommitted(), "still pending");
		line("ROLLBACK; LET n = SELECT COUNT(*) FROM customer;");
		Assertions.assertEquals(0L, value("n"), "after ROLLBACK");
		line("INSERT INTO CUSTOMER VALUES (9, 'x', 'FR'); COMMIT; LET n = SELECT COUNT(*) FROM customer;");
		Assertions.assertEquals(1L, value("n"), "after COMMIT");
	}

	@Test
	void aQueryAssignmentNeverSetsThePendingChangeFlag() throws Exception {
		autocommit(false);
		line("LET n = SELECT COUNT(*) FROM customer;");
		Assertions.assertFalse(db.isHasUncommitted());
	}

	@Test
	void theResultIsIsolatedFromEveryResultFeature() throws Exception {
		line("INSERT INTO CUSTOMER VALUES (1, 'a', 'FR');");
		line("SELECT name FROM customer;");
		LastQueryResult lastResult = LastQueryResultHolder.get();
		Object lastCopyable = LastCopyableResultHolder.get();
		String lastSql = interpreter.lastSQLQuery;
		clearOutput();
		line("LET n = SELECT COUNT(*) FROM customer;");
		Assertions.assertSame(lastResult, LastQueryResultHolder.get(), "<@last:...> and DUMP / unchanged");
		Assertions.assertSame(lastCopyable, LastCopyableResultHolder.get(), "COPY RESULT unchanged");
		Assertions.assertEquals(lastSql, interpreter.lastSQLQuery, "/ rerun unchanged");
		Assertions.assertFalse(output().contains("|"), "no result table: " + output());
		Assertions.assertFalse(output().contains("rows fetched"), output());

		TabularResultSpool spool = TabularResultSpool.open();
		try {
			TabularResultCapture.begin(spool);
			line("LET n = SELECT COUNT(*) FROM customer;");
		} finally {
			TabularResultCapture.end(spool);
			Assertions.assertFalse(spool.hasResult(), "never becomes a DUMP LIB captured result");
			spool.close();
		}
	}

	@Test
	void withoutAConnectionTheQueryFails() throws Exception {
		line("LET x = 1;");
		db.close(false);
		db.setDirectConnection(null);
		com.upandcoding.broadsql.controller.shell.commands.core.script.CommandLet let = CommandTestSupport
				.create(com.upandcoding.broadsql.controller.shell.commands.core.script.CommandLet.class, db, console, settings);
		let.setConsoleCommandInterpreter(interpreter);
		Exception e = Assertions.assertThrows(Exception.class, () -> let.execute("LET x = SELECT 1"));
		Assertions.assertTrue(e.getMessage().contains("No active SQL connection"), e.getMessage());
		Assertions.assertEquals(1L, value("x"));
	}
}
