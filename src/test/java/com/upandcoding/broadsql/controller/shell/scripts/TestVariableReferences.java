package com.upandcoding.broadsql.controller.shell.scripts;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.core.NativeQuery;
import org.postgresql.core.Parser;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.api.ApiSessionVariablesHolder;

/**
 * SPRINT 0110A: the reference scanner and the SQL preparation step (spec sections 6.4, 6.7, 9.1 to 9.6): where
 * {@code ${name}} is recognized, malformed and undefined references, generated markers, raw {@code ?} on pgjdbc
 * (verified with pgjdbc's own client-side parser) and on other drivers.
 */
class TestVariableReferences {

	private static final BooleanSupplier PGJDBC = () -> true;
	private static final BooleanSupplier OTHER_DRIVER = () -> false;

	@AfterEach
	void tearDown() {
		ApiSessionVariablesHolder.clearAll();
	}

	private static ScriptVariables vars(Object... namesAndValues) {
		ScriptVariables variables = new ScriptVariables();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			Object v = namesAndValues[i + 1];
			variables.assign((String) namesAndValues[i], v instanceof ScriptValue ? (ScriptValue) v : v instanceof Long ? ScriptValue.ofLong((Long) v) : ScriptValue.ofString((String) v));
		}
		return variables;
	}

	// ---- recognition ----

	@Test
	void aReferenceInNormalTextIsRecognized() {
		VariableReferenceScanner.Scan scan = VariableReferenceScanner.scan("SELECT * FROM t WHERE id = ${id}");
		Assertions.assertEquals(1, scan.getOccurrences().size());
		Assertions.assertEquals("id", scan.getOccurrences().get(0).name());
		Assertions.assertEquals("${id}", scan.getOccurrences().get(0).text());
	}

	@ParameterizedTest
	@ValueSource(strings = { "SELECT '${id}'", "SELECT 'it''s ${id}'", "SELECT \"${id}\"", "SELECT 1 -- ${id}", "SELECT 1 /* ${id} */",
			"SELECT $$ ${id} $$", "SELECT $tag$ ${id} $tag$", "SELECT $body$ a $$ ${id} $body$", "SELECT '$${id}'" })
	void referencesInsideQuotesCommentsAndDollarQuotesAreNotRecognized(String sql) {
		Assertions.assertFalse(VariableReferenceScanner.scan(sql).hasReferences(), sql);
	}

	@Test
	void textAfterAClosedDollarQuoteIsNormalAgain() {
		VariableReferenceScanner.Scan scan = VariableReferenceScanner.scan("SELECT $q$ ${a} $q$, ${b}");
		Assertions.assertEquals(List.of("b"), scan.getOccurrences().stream().map(VariableReferenceScanner.Occurrence::name).toList());
	}

	@ParameterizedTest
	@ValueSource(strings = { "SELECT V$SESSION", "SELECT $1", "SELECT $100", "SELECT a$b$c", "SELECT x$$y" })
	void dollarsThatAreNotReferencesOrQuotesAreOrdinaryText(String sql) {
		VariableReferenceScanner.Scan scan = VariableReferenceScanner.scan(sql + " WHERE id = ${id}");
		Assertions.assertEquals(1, scan.getOccurrences().size(), sql);
		Assertions.assertEquals("id", scan.getOccurrences().get(0).name());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "${}|${}", "${ x}|${ x}", "${x }|${x }", "${ x }|${ x }", "${1}|${1}", "${1a}|${1a}", "${a.b}|${a.b}",
			"${a|${a", "${ENV:HOME}|${ENV:HOME}", "${true}|${true}", "${ENV}|${ENV}", "${NULL}|${NULL}", "${a-b}|${a-b}" })
	void malformedReferences(String reference, String reported) {
		VariableReferenceScanner.Scan scan = VariableReferenceScanner.scan("SELECT " + reference);
		Assertions.assertEquals(1, scan.getOccurrences().size(), reference);
		Assertions.assertTrue(scan.getOccurrences().get(0).isMalformed(), reference);
		Assertions.assertEquals(reported, scan.getOccurrences().get(0).text());
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> SqlReferences.prepare("SELECT " + reference, vars("a", 1L, "x", 1L), OTHER_DRIVER));
		Assertions.assertTrue(e.getMessage().startsWith("Malformed variable reference '" + reported + "'"), e.getMessage());
	}

	@Test
	void anEnvReferenceIsCalledOutAsUnsupported() {
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> SqlReferences.prepare("SELECT ${ENV:HOME}", vars(), OTHER_DRIVER));
		Assertions.assertTrue(e.getMessage().contains("${ENV:...} is not supported in SQL scripting"), e.getMessage());
	}

	@Test
	void aTooLongNameIsMalformed() {
		String name = "a".repeat(65);
		Assertions.assertTrue(VariableReferenceScanner.scan("SELECT ${" + name + "}").getOccurrences().get(0).isMalformed());
		Assertions.assertFalse(VariableReferenceScanner.scan("SELECT ${" + name.substring(1) + "}").getOccurrences().get(0).isMalformed());
	}

	// ---- preparation ----

	@Test
	void aStatementWithoutReferencesIsNotPrepared() throws Exception {
		Assertions.assertNull(SqlReferences.prepare("SELECT * FROM t WHERE j ? 'k' AND n = '${x}'", vars(), OTHER_DRIVER));
	}

	@Test
	void eachReferenceBecomesOneGeneratedMarkerInOrderAndRepeatsBindTheSameValue() throws Exception {
		ScriptVariables variables = vars("a", 1L, "B", "two");
		PreparedSql prepared = SqlReferences.prepare("SELECT ${a}, ${b}, ${A} FROM t WHERE x = '${a}'", variables, OTHER_DRIVER);
		Assertions.assertEquals("SELECT ?, ?, ? FROM t WHERE x = '${a}'", prepared.getJdbcText());
		Assertions.assertEquals(3, prepared.getBinds().size());
		Assertions.assertSame(variables.get("a"), prepared.getBinds().get(0));
		Assertions.assertSame(variables.get("b"), prepared.getBinds().get(1));
		Assertions.assertSame(variables.get("a"), prepared.getBinds().get(2));
		Assertions.assertEquals(List.of("a", "B"), List.copyOf(prepared.getReferenced().keySet()), "distinct names, display spelling, first-reference order");
		Assertions.assertEquals("SELECT ${a}, ${b}, ${A} FROM t WHERE x = '${a}'", prepared.getOriginalText());
	}

	@Test
	void anUndefinedVariableIsReportedBeforeAnythingRuns() {
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> SqlReferences.prepare("SELECT ${ordr_id}", vars(), OTHER_DRIVER));
		Assertions.assertEquals("Variable ordr_id is not defined.", e.getMessage());
	}

	@Test
	void theUndefinedErrorMentionsAnApiVariableOfTheSameName() {
		ApiSessionVariablesHolder.set("TOKEN", "abc");
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> SqlReferences.prepare("SELECT ${token}", vars(), OTHER_DRIVER));
		Assertions.assertTrue(e.getMessage().contains("An API variable token exists; API variables (VAR) are not visible in SQL. Use LET to define a SQL scripting variable."),
				e.getMessage());
	}

	@Test
	void theFirstProblemLeftToRightIsReported() {
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> SqlReferences.prepare("SELECT ${nope}, ${1}", vars(), OTHER_DRIVER));
		Assertions.assertTrue(e.getMessage().startsWith("Variable nope"), e.getMessage());
		e = Assertions.assertThrows(BroadSQLException.class, () -> SqlReferences.prepare("SELECT ${1}, ${nope}", vars(), OTHER_DRIVER));
		Assertions.assertTrue(e.getMessage().startsWith("Malformed"), e.getMessage());
	}

	@Test
	void injectionLookingTextIsNeverSqlStructure() throws Exception {
		PreparedSql prepared = SqlReferences.prepare("SELECT * FROM t WHERE name = ${v}", vars("v", "x'); DROP TABLE t; --"), OTHER_DRIVER);
		Assertions.assertEquals("SELECT * FROM t WHERE name = ?", prepared.getJdbcText());
		Assertions.assertEquals("x'); DROP TABLE t; --", prepared.getBinds().get(0).getValue());
	}

	// ---- raw ? (spec 9.6) ----

	@Test
	void rawQuestionMarksWithReferencesAreRefusedOnOtherDrivers() {
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class,
				() -> SqlReferences.prepare("SELECT * FROM t WHERE j ? 'k' AND id = ${x}", vars("x", 1L), OTHER_DRIVER));
		Assertions.assertTrue(e.getMessage().startsWith("Raw ? placeholders cannot be combined with ${...} variable references in this version"), e.getMessage());
	}

	@Test
	void questionMarksInsideQuotesIdentifiersCommentsAndDollarQuotesAreNeverRaw() throws Exception {
		String sql = "SELECT '?', \"a?\", $$?$$ /* ? */ FROM t WHERE id = ${x} -- ?";
		PreparedSql prepared = SqlReferences.prepare(sql, vars("x", 1L), OTHER_DRIVER);
		Assertions.assertEquals("SELECT '?', \"a?\", $$?$$ /* ? */ FROM t WHERE id = ? -- ?", prepared.getJdbcText());
	}

	@ParameterizedTest
	@CsvSource(delimiterString = "=>", quoteCharacter = '"', value = { "tags ? ${k}=>tags ?? ?", "tags ?| array['a'] AND id > ${k}=>tags ??| array['a'] AND id > ?",
			"tags ?& array['a'] AND id > ${k}=>tags ??& array['a'] AND id > ?", "a ?? ${k}=>a ???? ?", "a ??| ${k}=>a ????| ?", "a ??& ${k}=>a ????& ?",
			"${k} ? x=>? ?? x" })
	void onPgjdbcRawQuestionMarksAreEscapedAndTheOperatorsSurvivePgjdbcParsing(String where, String expectedJdbc) throws Exception {
		String sql = "SELECT * FROM customer WHERE " + where;
		PreparedSql prepared = SqlReferences.prepare(sql, vars("k", "vip"), PGJDBC);
		Assertions.assertEquals("SELECT * FROM customer WHERE " + expectedJdbc, prepared.getJdbcText());

		// pgjdbc's own client-side parser: the generated marker is a parameter, the escaped raw ? reaches PostgreSQL unchanged
		List<NativeQuery> parsed = Parser.parseJdbcSql(prepared.getJdbcText(), true, true, false, false, false);
		NativeQuery query = parsed.get(0);
		Assertions.assertEquals(prepared.getBinds().size(), query.bindPositions.length, query.nativeSql);
		Assertions.assertEquals(where.replace("${k}", "$1"), query.nativeSql.substring("SELECT * FROM customer WHERE ".length()), query.nativeSql);
	}

	@Test
	void onPgjdbcDollarQuotedTextIsUntouchedBecausePgjdbcWouldNotUnescapeIt() throws Exception {
		PreparedSql prepared = SqlReferences.prepare("SELECT $$a?b$$, ${k}", vars("k", 1L), PGJDBC);
		Assertions.assertEquals("SELECT $$a?b$$, ?", prepared.getJdbcText());
		NativeQuery query = Parser.parseJdbcSql(prepared.getJdbcText(), true, true, false, false, false).get(0);
		Assertions.assertEquals("SELECT $$a?b$$, $1", query.nativeSql);
	}

	@Test
	void pgjdbcIsOnlyConsultedWhenReferencesAndRawQuestionMarksAreBothPresent() throws Exception {
		BooleanSupplier failing = () -> {
			throw new AssertionError("must not be consulted");
		};
		Assertions.assertNull(SqlReferences.prepare("SELECT j ? 'k'", vars(), failing));
		Assertions.assertNotNull(SqlReferences.prepare("SELECT ${k}", vars("k", 1L), failing));
	}

	@Test
	void theUnboundPreparationOfALetQueryEscapesRawQuestionMarksOnPgjdbcOnly() throws Exception {
		Assertions.assertEquals("SELECT j ?? 'k'", SqlReferences.prepareAlways("SELECT j ? 'k'", vars(), PGJDBC).getJdbcText());
		Assertions.assertEquals("SELECT j ? 'k'", SqlReferences.prepareAlways("SELECT j ? 'k'", vars(), OTHER_DRIVER).getJdbcText());
		Assertions.assertEquals("SELECT 1", SqlReferences.prepareAlways("SELECT 1", vars(), OTHER_DRIVER).getJdbcText());
	}

	@Test
	void pgjdbcIsRecognizedByItsDriverName() {
		Assertions.assertTrue(DatabaseConnection.isPgJdbcDriverName("PostgreSQL JDBC Driver"));
		Assertions.assertFalse(DatabaseConnection.isPgJdbcDriverName("H2 JDBC Driver"));
		Assertions.assertFalse(DatabaseConnection.isPgJdbcDriverName(null));
	}
}
