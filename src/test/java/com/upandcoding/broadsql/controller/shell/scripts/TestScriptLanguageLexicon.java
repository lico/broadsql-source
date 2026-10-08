package com.upandcoding.broadsql.controller.shell.scripts;

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

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * SPRINT 0110A: names (spec 7.1, 6.8), the literal grammar and typing (8.1, 7.2, 22), display rendering (7.8),
 * listing quoting (7.9) and control-character sanitization (10.6). Pure unit tests, no database.
 */
class TestScriptLanguageLexicon {

	// ---- names ----

	@ParameterizedTest
	@ValueSource(strings = { "x", "_", "_x", "n1", "MAX_ID", "max_id", "Mixed_Case_9", "a234567890123456789012345678901234567890123456789012345678901234" })
	void validNames(String name) {
		Assertions.assertTrue(VariableNames.isValid(name), name);
		Assertions.assertNull(VariableNames.problem(name, "variable name"));
	}

	@Test
	void theMaximumLengthIs64AndOneMoreIsRefused() {
		String max = "a".repeat(64);
		Assertions.assertTrue(VariableNames.isValid(max));
		Assertions.assertFalse(VariableNames.isValid(max + "b"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "1", "1x", "a-b", "a.b", "a b", "a$", "é", "naïve", "", "x!", "{x}" })
	void invalidNames(String name) {
		Assertions.assertFalse(VariableNames.isValid(name), name);
		Assertions.assertNotNull(VariableNames.problem(name, "variable name"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "ENV", "env", "Env", "NULL", "null", "Null", "TRUE", "true", "True", "tRuE", "FALSE", "false", "False" })
	void reservedNamesInEveryCase(String name) {
		Assertions.assertFalse(VariableNames.isValid(name));
		Assertions.assertTrue(VariableNames.problem(name, "variable name").contains("reserved"));
	}

	@Test
	void namesAreCaseInsensitiveAndTheLatestSpellingIsDisplayed() {
		ScriptVariables variables = new ScriptVariables();
		variables.assign("Max_Id", ScriptValue.ofLong(1));
		Assertions.assertTrue(variables.isDefined("MAX_ID"));
		Assertions.assertSame(variables.get("max_id"), variables.get("MAX_ID"));
		variables.assign("MAX_ID", ScriptValue.ofLong(2));
		Assertions.assertEquals(1, variables.size());
		Assertions.assertEquals("MAX_ID", variables.displayName("max_id"));
		Assertions.assertEquals(2L, variables.get("Max_Id").getValue());
	}

	@Test
	void listingIsSortedCaseInsensitively() {
		ScriptVariables variables = new ScriptVariables();
		for (String name : new String[] { "zeta", "Alpha", "beta", "_under", "BETA2" }) {
			variables.assign(name, ScriptValue.ofLong(1));
		}
		Assertions.assertEquals(java.util.List.of("Alpha", "beta", "BETA2", "zeta", "_under"),
				variables.sorted().stream().map(ScriptVariables.Entry::name).toList());
	}

	// ---- literals ----

	@Test
	void integerLiteralsAreLongWithinRangeAndDecimalOutside() throws Exception {
		Assertions.assertEquals(Long.MAX_VALUE, ScriptLiterals.parse("9223372036854775807").getValue());
		Assertions.assertEquals(Long.MIN_VALUE, ScriptLiterals.parse("-9223372036854775808").getValue());
		ScriptValue above = ScriptLiterals.parse("9223372036854775808");
		Assertions.assertEquals(new BigDecimal("9223372036854775808"), above.getValue());
		Assertions.assertEquals("DECIMAL", above.getTypeName());
		ScriptValue below = ScriptLiterals.parse("-9223372036854775809");
		Assertions.assertEquals(new BigDecimal("-9223372036854775809"), below.getValue());
		ScriptValue zero = ScriptLiterals.parse("0");
		Assertions.assertEquals(0L, zero.getValue());
		Assertions.assertEquals("BIGINT", zero.getTypeName());
		Assertions.assertEquals(Types.BIGINT, zero.getJdbcType());
		Assertions.assertEquals(0L, ScriptLiterals.parse("-0").getValue());
		Assertions.assertEquals(7L, ScriptLiterals.parse("007").getValue());
	}

	@Test
	void decimalLiteralsAreExactBigDecimals() throws Exception {
		ScriptValue value = ScriptLiterals.parse("0.1");
		Assertions.assertEquals(new BigDecimal("0.1"), value.getValue());
		Assertions.assertEquals("DECIMAL", value.getTypeName());
		Assertions.assertEquals(Types.DECIMAL, value.getJdbcType());
		Assertions.assertEquals(new BigDecimal("-1.50"), ScriptLiterals.parse("-1.50").getValue());
		Assertions.assertEquals("-1.50", ScriptValueText.render(ScriptLiterals.parse("-1.50")));
		Assertions.assertEquals(new BigDecimal("123456789012345678901234567890.000000000000000001"),
				ScriptLiterals.parse("123456789012345678901234567890.000000000000000001").getValue());
		Assertions.assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) ScriptLiterals.parse("-0.0").getValue()));
	}

	@Test
	void stringLiterals() throws Exception {
		ScriptValue empty = ScriptLiterals.parse("''");
		Assertions.assertEquals("", empty.getValue());
		Assertions.assertEquals("VARCHAR", empty.getTypeName());
		Assertions.assertEquals("O'Brien", ScriptLiterals.parse("'O''Brien'").getValue());
		Assertions.assertEquals("''", ScriptLiterals.parse("''''''").getValue());
		Assertions.assertEquals("${y}", ScriptLiterals.parse("'${y}'").getValue(), "never interpolated");
		Assertions.assertEquals("a;b -- c /* d */", ScriptLiterals.parse("'a;b -- c /* d */'").getValue());
		Assertions.assertEquals("Zoë 東京 €", ScriptLiterals.parse("'Zoë 東京 €'").getValue());
		Assertions.assertEquals("line1\nline2", ScriptLiterals.parse("'line1\nline2'").getValue());
		String huge = "x".repeat(1_000_000);
		Assertions.assertEquals(huge, ScriptLiterals.parse("'" + huge + "'").getValue(), "no size limit");
	}

	@ParameterizedTest
	@CsvSource({ "TRUE,true", "true,true", "True,true", "FALSE,false", "false,false", "False,false" })
	void booleanLiteralsInEveryCase(String text, boolean expected) throws Exception {
		ScriptValue value = ScriptLiterals.parse(text);
		Assertions.assertEquals(expected, value.getValue());
		Assertions.assertEquals("BOOLEAN", value.getTypeName());
		Assertions.assertEquals(Types.BOOLEAN, value.getJdbcType());
	}

	@ParameterizedTest
	@ValueSource(strings = { "NULL", "null", "Null" })
	void nullLiteralIsAnUntypedDefinedNull(String text) throws Exception {
		ScriptValue value = ScriptLiterals.parse(text);
		Assertions.assertTrue(value.isNull());
		Assertions.assertTrue(value.isUntypedNull());
		Assertions.assertEquals(Types.NULL, value.getJdbcType());
		Assertions.assertEquals("NULL", value.getTypeName());
	}

	@ParameterizedTest
	@ValueSource(strings = { "abc", "+1", ".5", "1.", "1e5", "1E5", "1 2", "'a' 'b'", "'abc", "'a'b", "--1", "1.2.3", "0x10", "(1)", "'a' || 'b'", "1,5",
			"${y} + 1", "TRUEX", "NUL", "", "  " })
	void invalidLiterals(String text) {
		Assertions.assertNull(ScriptLiterals.tryParse(text), text);
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> ScriptLiterals.parse(text));
		Assertions.assertTrue(e.getMessage().startsWith("Invalid value"), e.getMessage());
		Assertions.assertTrue(e.getMessage().contains(ScriptLiterals.VALUES_HINT), e.getMessage());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "SELECT 1|true", "select 1|true", "With x as (select 1) select * from x|true", "VALUES 1|true", "values(1)|true",
			"SELECT(1)|true", "(SELECT 1)|false", "SELECTED 1|false", "INSERT INTO t VALUES (1)|false", "  select 1|true", "SELECT1|true", "VALUES1|true" })
	void queryRecognitionUsesTheFirstRunOfLetters(String text, boolean query) {
		Assertions.assertEquals(query, ScriptLiterals.startsQuery(text), text);
	}

	// ---- display ----

	@Test
	void displayRendering() {
		Assertions.assertEquals("-42", ScriptValueText.render(ScriptValue.ofLong(-42)));
		Assertions.assertEquals("1000000", ScriptValueText.render(ScriptValue.ofDecimal(new BigDecimal("1E+6"))), "plain notation, no exponent");
		Assertions.assertEquals("0.00001", ScriptValueText.render(ScriptValue.ofDecimal(new BigDecimal("1E-5"))));
		Assertions.assertEquals("true", ScriptValueText.render(ScriptValue.ofBoolean(true)));
		Assertions.assertEquals("false", ScriptValueText.render(ScriptValue.ofBoolean(false)));
		Assertions.assertEquals("NULL", ScriptValueText.render(ScriptValue.untypedNull()));
		Assertions.assertEquals("NULL", ScriptValueText.render(ScriptValue.fromQuery(null, Types.INTEGER, "INTEGER", "X")));
		Assertions.assertEquals("1.5", ScriptValueText.render(ScriptValue.fromQuery(1.5d, Types.DOUBLE, "DOUBLE", "X")));
		Assertions.assertEquals("2.5", ScriptValueText.render(ScriptValue.fromQuery(2.5f, Types.REAL, "REAL", "X")));
		Assertions.assertEquals("2026-01-31", ScriptValueText.render(ScriptValue.fromQuery(LocalDate.of(2026, 1, 31), Types.DATE, "DATE", "X")));
		Assertions.assertEquals("10:20:30", ScriptValueText.render(ScriptValue.fromQuery(LocalTime.of(10, 20, 30), Types.TIME, "TIME", "X")));
		Assertions.assertEquals("10:20:30.5", ScriptValueText.render(ScriptValue.fromQuery(LocalTime.of(10, 20, 30, 500_000_000), Types.TIME, "TIME", "X")));
		Assertions.assertEquals("10:20:30.000001", ScriptValueText.render(ScriptValue.fromQuery(LocalTime.of(10, 20, 30, 1_000), Types.TIME, "TIME", "X")));
		Assertions.assertEquals("2026-01-31 10:20:30.123",
				ScriptValueText.render(ScriptValue.fromQuery(LocalDateTime.of(2026, 1, 31, 10, 20, 30, 123_000_000), Types.TIMESTAMP, "TIMESTAMP", "X")));
		Assertions.assertEquals("2026-01-31 10:20:30+02:00", ScriptValueText.render(ScriptValue
				.fromQuery(OffsetDateTime.of(2026, 1, 31, 10, 20, 30, 0, ZoneOffset.ofHours(2)), Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE", "X")));
		Assertions.assertEquals("2026-01-31 10:20:30Z", ScriptValueText.render(
				ScriptValue.fromQuery(OffsetDateTime.of(2026, 1, 31, 10, 20, 30, 0, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE", "X")));
		Assertions.assertEquals("10:20:30-05:30", ScriptValueText
				.render(ScriptValue.fromQuery(OffsetTime.of(10, 20, 30, 0, ZoneOffset.ofHoursMinutes(-5, -30)), Types.TIME_WITH_TIMEZONE, "TIME WITH TIME ZONE", "X")));
		Assertions.assertEquals("O'Brien", ScriptValueText.render(ScriptValue.ofString("O'Brien")));
	}

	@Test
	void listingQuotesStringsSoTheStringNullIsToldApartFromNull() {
		Assertions.assertEquals("'NULL'", ScriptValueText.listing(ScriptValue.ofString("NULL")));
		Assertions.assertEquals("NULL", ScriptValueText.listing(ScriptValue.untypedNull()));
		Assertions.assertEquals("'O''Brien'", ScriptValueText.listing(ScriptValue.ofString("O'Brien")));
		Assertions.assertEquals("''", ScriptValueText.listing(ScriptValue.ofString("")));
		Assertions.assertEquals("42", ScriptValueText.listing(ScriptValue.ofLong(42)));
	}

	@Test
	void sanitizationReplacesControlCharactersKeepsTabAndLineFeedAndRemovesCarriageReturn() {
		Assertions.assertEquals("a\tb\nc", ScriptValueText.sanitize("a\tb\r\nc"));
		Assertions.assertEquals("?[31mred", ScriptValueText.sanitize("\u001b[31mred"));
		Assertions.assertEquals("x?y?z?w", ScriptValueText.sanitize("x\u0000y\u007fz\u0085w"));
		Assertions.assertEquals("é€東", ScriptValueText.sanitize("é€東"));
		Assertions.assertEquals("?", ScriptValueText.sanitize("\u009f"));
	}

	@Test
	void displayTruncationAffectsOnlyConfirmationLines() {
		String longText = "y".repeat(500);
		ScriptValue value = ScriptValue.ofString(longText);
		String line = ScriptValueText.confirmation("v", value);
		Assertions.assertEquals("v = '" + "y".repeat(199) + "... (VARCHAR)", line, "200 characters of the listed value, then ...");
		Assertions.assertEquals(longText, value.getValue(), "the stored value is never truncated");
		Assertions.assertEquals("v = 'short' (VARCHAR)", ScriptValueText.confirmation("v", ScriptValue.ofString("short")));
	}
}
