package com.upandcoding.broadsql.controller.shell.commands;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.controller.shell.scripts.PreparedSql;
import com.upandcoding.broadsql.controller.shell.scripts.SqlReferences;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * SPRINT 0110A: {@code ${name}} on the SQL path, end to end on H2 (spec section 9): typed binds for every statement
 * kind and value type, values that can never change the statement, references inside quotes/comments untouched
 * without diagnostic, undefined/malformed references never executed, raw {@code ?}, statements without references
 * unchanged, and binding a value obtained on one database on another engine (HSQLDB, Derby, SQLite).
 */
class TestSqlVariableBinding extends ScriptingTestBase {

	private Object nameOf(int id) throws Exception {
		return sql("SELECT NAME FROM CUSTOMER WHERE ID = " + id);
	}

	@Test
	void selectInsertUpdateDeleteWithAndValuesBindTheirReferences() throws Exception {
		line("LET id = 1; LET name = 'Alice'; LET country = 'FR';");
		line("INSERT INTO customer VALUES (${id}, ${name}, ${country});");
		Assertions.assertEquals("Alice", nameOf(1), output());
		line("LET new_name = 'Alicia'; UPDATE customer SET name = ${new_name} WHERE id = ${id};");
		Assertions.assertEquals("Alicia", nameOf(1), output());
		clearOutput();
		line("SELECT name FROM customer WHERE id = ${id};");
		Assertions.assertTrue(output().contains("Alicia"), output());
		clearOutput();
		line("WITH c AS (SELECT * FROM customer WHERE country = ${country}) SELECT name FROM c;");
		Assertions.assertTrue(output().contains("Alicia"), output());
		clearOutput();
		line("CALL ${id} + 41;");
		Assertions.assertTrue(output().contains("42"), output());
		line("DELETE FROM customer WHERE id = ${id};");
		Assertions.assertEquals(0L, count("CUSTOMER"), output());
	}

	@ParameterizedTest
	@ValueSource(strings = { "O'Brien", "x'); DROP TABLE customer; --", "<@ids.txt>", "${another}", "%1", "Zoë 東京 € ✓", "line1\nline2", "tab\there", "'", "''",
			"; SELECT 1;", "\\'", "NULL" })
	void aValueIsAlwaysAValueNeverSqlStructure(String text) throws Exception {
		interpreter.getScriptVariables().assign("v", com.upandcoding.broadsql.controller.shell.scripts.ScriptValue.ofString(text));
		line("INSERT INTO customer VALUES (1, ${v}, 'X');");
		Assertions.assertEquals(text, nameOf(1), output());
		Assertions.assertEquals(1L, count("CUSTOMER"), "the table still exists and holds one row");
		line("LET found = SELECT COUNT(*) FROM customer WHERE name = ${v};");
		Assertions.assertEquals(1L, value("found"), output());
	}

	@Test
	void anApostropheNeedsNoEscapingAtUse() throws Exception {
		line("INSERT INTO customer VALUES (1, 'O''Brien', 'IE');");
		line("LET last_name = 'O''Brien'; LET n = SELECT COUNT(*) FROM customer WHERE name = ${last_name};");
		Assertions.assertEquals(1L, value("n"), output());
	}

	@Test
	void numbersBooleansAndTemporalsAreBoundTyped() throws Exception {
		line("CREATE TABLE B (I BIGINT, D DECIMAL(30,10), F DOUBLE PRECISION, BO BOOLEAN, DA DATE, TM TIME(3), TS TIMESTAMP(3), TZ TIMESTAMP(3) WITH TIME ZONE);");
		line("LET i = 9223372036854775807; LET d = 12345678901234567890.0123456789; LET bo = TRUE;");
		line("LET f = SELECT CAST(2.5 AS DOUBLE PRECISION); LET da = SELECT DATE '2026-01-31'; LET tm = SELECT TIME '10:20:30.125';");
		line("LET ts = SELECT TIMESTAMP '2026-01-31 10:20:30.125'; LET tz = SELECT TIMESTAMP WITH TIME ZONE '2026-01-31 10:20:30.125+05:00';");
		line("INSERT INTO B VALUES (${i}, ${d}, ${f}, ${bo}, ${da}, ${tm}, ${ts}, ${tz});");
		Assertions.assertEquals(Long.MAX_VALUE, ((Number) sql("SELECT I FROM B")).longValue(), output());
		Assertions.assertEquals(new java.math.BigDecimal("12345678901234567890.0123456789"), sql("SELECT D FROM B"));
		Assertions.assertEquals(2.5d, ((Number) sql("SELECT F FROM B")).doubleValue());
		Assertions.assertEquals(Boolean.TRUE, sql("SELECT BO FROM B"));
		Assertions.assertEquals(1L, ((Number) sql("SELECT COUNT(*) FROM B WHERE DA = DATE '2026-01-31' AND TM = TIME '10:20:30.125' "
				+ "AND TS = TIMESTAMP '2026-01-31 10:20:30.125' AND TZ = TIMESTAMP WITH TIME ZONE '2026-01-31 10:20:30.125+05:00'")).longValue(), output());
	}

	@Test
	void nullBindsAreSqlNullWithSqlSemantics() throws Exception {
		line("INSERT INTO customer VALUES (1, 'a', NULL);");
		line("LET region = SELECT country FROM customer WHERE id = 1;");
		Assertions.assertTrue(var("region").isNull() && !var("region").isUntypedNull(), "typed NULL");
		line("LET hits = SELECT COUNT(*) FROM customer WHERE country = ${region};");
		Assertions.assertEquals(0L, value("hits"), "= NULL matches no row");
		line("LET u = NULL; LET hits_u = SELECT COUNT(*) FROM customer WHERE country = ${u};");
		Assertions.assertEquals(0L, value("hits_u"), output());
		line("INSERT INTO customer VALUES (2, ${u}, ${region});");
		Assertions.assertEquals(1L, ((Number) sql("SELECT COUNT(*) FROM CUSTOMER WHERE ID = 2 AND NAME IS NULL AND COUNTRY IS NULL")).longValue(), output());
	}

	@Test
	void referencesInsideQuotesAndCommentsAreUntouchedAndNeverDiagnosedAtRuntime() throws Exception {
		line("LET n = 'x';");
		clearOutput();
		line("INSERT INTO customer VALUES (1, '${n}', 'A');");
		Assertions.assertEquals("${n}", nameOf(1));
		line("SELECT \"NAME\" AS \"${n}\" FROM customer -- ${n}\n;");
		Assertions.assertTrue(output().contains("${n}"), output());
		Assertions.assertFalse(output().contains("ERROR"), output());
		Assertions.assertFalse(output().contains("WARNING"), output());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = { "SELECT ${nope}|Variable nope is not defined", "SELECT ${ x }|Malformed variable reference",
			"SELECT ${1}|Malformed variable reference", "SELECT ${ENV:HOME}|${ENV:...} is not supported" })
	void undefinedAndMalformedReferencesAreNeverExecutedAndNotRolledBack(String statement, String expected) throws Exception {
		autocommit(false);
		line("INSERT INTO customer VALUES (1, 'pending', 'FR');");
		clearOutput();
		line("INSERT INTO customer VALUES (2, 'second', 'FR'); " + statement + "; INSERT INTO customer VALUES (3, 'never', 'FR');");
		Assertions.assertTrue(output().contains(expected), output());
		Assertions.assertEquals(2L, count("CUSTOMER"), "pending work kept (no rollback) and the line stopped at the failure");
		Assertions.assertFalse(output().contains("were rolled back"), output());
	}

	@Test
	void aParameterWhereTheDatabaseRefusesOneIsTheDatabasesOwnError() {
		line("LET t = 'CUSTOMER'; SELECT * FROM ${t};");
		Assertions.assertTrue(output().contains("ERROR"), output());
	}

	@Test
	void rawQuestionMarksWithReferencesAreRefusedOnH2BeforeExecution() throws Exception {
		line("LET x = 1; INSERT INTO customer VALUES (${x}, '?', 'A');");
		Assertions.assertEquals(1L, count("CUSTOMER"), "a ? inside a string is not raw: " + output());
		clearOutput();
		line("SELECT * FROM customer WHERE id = ? AND id = ${x};");
		Assertions.assertTrue(output().contains("Raw ? placeholders cannot be combined with ${...} variable references"), output());
	}

	@Test
	void aStatementWithoutReferencesIsUnchangedIncludingARawQuestionMark() {
		line("SELECT * FROM customer WHERE id = ?;");
		Assertions.assertFalse(output().contains("Raw ? placeholders"), output());
		Assertions.assertTrue(output().contains("ERROR"), "H2 itself answers as it does today: " + output());
	}

	@Test
	void aBoundStatementIsDisplayedExactlyLikeTheSameStatementWithAValue() {
		line("INSERT INTO customer VALUES (1, 'a', 'FR'), (2, 'b', 'DE');");
		clearOutput();
		line("SELECT id, name FROM customer WHERE country = 'FR';");
		String literal = output().replaceAll("in [0-9]+ ms", "in N ms");
		line("LET c = 'FR';");
		clearOutput();
		line("SELECT id, name FROM customer WHERE country = ${c};");
		String bound = output().replaceAll("in [0-9]+ ms", "in N ms");
		Assertions.assertEquals(literal, bound);
	}

	@Test
	void theLastQueryKeepsItsReferencesAndARerunUsesCurrentValues() throws Exception {
		line("INSERT INTO customer VALUES (1, 'a', 'FR'), (2, 'b', 'DE');");
		line("LET c = 'FR'; SELECT name FROM customer WHERE country = ${c};");
		Assertions.assertEquals("SELECT name FROM customer WHERE country = ${c}", interpreter.lastSQLQuery);
		line("LET c = 'DE';");
		clearOutput();
		interpreter.handleSlashRerun("/");
		Assertions.assertTrue(output().contains("b") && !output().contains("|a"), output());
	}

	@Test
	void multiStatementLineSeesEachAssignmentAsItExecutes() {
		line("LET x = 5; INSERT INTO customer VALUES (${x}, 'five', 'FR'); LET x = 6; INSERT INTO customer VALUES (${x}, 'six', 'FR');");
		Assertions.assertDoesNotThrow(() -> Assertions.assertEquals(2L, count("CUSTOMER"), output()));
	}

	@Test
	void macrosAreExpandedBeforeReferencesAreBound() throws Exception {
		java.nio.file.Path ids = tmp.resolve("ids.txt");
		java.nio.file.Files.writeString(ids, "1\n2\n");
		line("INSERT INTO customer VALUES (1, 'a', 'FR'), (2, 'b', 'FR'), (3, 'c', 'DE');");
		line("LET c = 'FR'; LET n = SELECT COUNT(*) FROM customer WHERE CAST(id AS VARCHAR) IN <@" + ids + "> AND country = ${c};");
		Assertions.assertEquals(2L, value("n"), output());
	}

	@Test
	void aValueContainingMacroSyntaxIsInert() throws Exception {
		line("LET v = '<@nowhere.txt>'; INSERT INTO customer VALUES (1, ${v}, 'A');");
		Assertions.assertEquals("<@nowhere.txt>", nameOf(1), output());
	}

	@Test
	void theDatabaseRejectingABoundValueIsASqlError() {
		line("LET v = 'not a number'; INSERT INTO customer VALUES (${v}, 'a', 'A');");
		Assertions.assertTrue(output().contains("ERROR"), output());
	}

	// ---- binding a value from H2 on other engines (spec 9.2, 18.2) ----

	private static DatabaseDefinition engine(String type, String driver, String url, String user) {
		DatabaseDefinition def = new DatabaseDefinition(type.replace(' ', '_') + "_" + UUID.randomUUID().toString().substring(0, 6));
		def.setDbDriver(driver);
		def.setDbType(type);
		def.setDbName(type);
		def.setUrl(url);
		def.setUserName(user == null ? "" : user);
		def.setUserPassword("");
		return def;
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "HSQL|org.hsqldb.jdbc.JDBCDriver|jdbc:hsqldb:mem:bind_%s|SA",
			"DERBY Embedded|org.apache.derby.iapi.jdbc.AutoloadedDriver|jdbc:derby:memory:bind_%s;create=true|",
			"SQLite|org.sqlite.JDBC|jdbc:sqlite:file:bind_%s?mode=memory&cache=shared|" })
	void aValueObtainedOnH2IsBoundOnAnotherEngine(String type, String driver, String url, String user) throws Exception {
		line("LET name = 'O''Brien'; LET id = SELECT 41 + 1; LET flag = TRUE; LET day = SELECT DATE '2026-01-31';");
		DatabaseConnection other = TestDatabaseConnections.connect(engine(type, driver, String.format(url, UUID.randomUUID().toString().replace("-", "")), user));
		try {
			other.setCmdLineConsole(new CapturingShellConsole());
			other.executeUpdateQuery("CREATE TABLE T (ID INT, NAME VARCHAR(20), FLAG INT, DAY DATE)");
			PreparedSql prepared = SqlReferences.prepare("INSERT INTO T (ID, NAME, FLAG) VALUES (${id}, ${name}, ${flag})", interpreter.getScriptVariables(),
					other::isPgJdbc);
			other.executeUpdateQuery(prepared);
			Connection c = other.getDirectConnection();
			try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT ID, NAME, FLAG FROM T")) {
				Assertions.assertTrue(rs.next());
				Assertions.assertEquals(42, rs.getInt(1));
				Assertions.assertEquals("O'Brien", rs.getString(2));
				Assertions.assertEquals(1, rs.getInt(3));
			}
			Assertions.assertEquals(SpringPropertiesConfig.DBTYPE_H2, db.getPlatform().getDbType());

			// a temporal value is bound with setObject (JDBC 4.2); the target driver converts it or rejects it (spec 9.2):
			// Derby 10.17 rejects java.time values, reported as the SQL error of the statement
			PreparedSql temporal = SqlReferences.prepare("UPDATE T SET DAY = ${day}", interpreter.getScriptVariables(), other::isPgJdbc);
			if (type.startsWith("DERBY")) {
				com.upandcoding.broadsql.controller.errors.SqlExecutionException e = Assertions
						.assertThrows(com.upandcoding.broadsql.controller.errors.SqlExecutionException.class, () -> other.executeUpdateQuery(temporal));
				Assertions.assertTrue(e.getMessage().contains("LocalDate"), e.getMessage());
			} else {
				other.executeUpdateQuery(temporal);
			}
		} finally {
			// closed directly: BroadSQL's close() commits first with autocommit on, which the SQLite driver refuses
			other.getDirectConnection().close();
		}
	}
}
