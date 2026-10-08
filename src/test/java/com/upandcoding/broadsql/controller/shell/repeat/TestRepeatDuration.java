package com.upandcoding.broadsql.controller.shell.repeat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/** GitHub #196: the {@code REPEAT} duration rule ({@code EVERY}, {@code FOR}): a positive whole number glued to s, m or h. */
class TestRepeatDuration {

	@ParameterizedTest
	@CsvSource({ "1s,1,1s", "10s,10,10s", "1m,60,1m", "90m,5400,90m", "1h,3600,1h", "24h,86400,24h", "1S,1,1s", "1M,60,1m", "1H,3600,1h",
			"007s,7,7s" })
	void validDurations(String text, long seconds, String canonical) throws BroadSQLException {
		RepeatDuration duration = RepeatDuration.parse(text, "EVERY");
		Assertions.assertEquals(seconds, duration.seconds());
		Assertions.assertEquals(seconds * 1000, duration.millis());
		Assertions.assertEquals(canonical, duration.toString());
	}

	@ParameterizedTest
	@ValueSource(strings = { "0s", "-1s", "500ms", "0.5s", "10", "1h30m", "1d", "foo", "s", "+5s", "1.5m", "10 s", "1w", "0h", "", " " })
	void invalidDurationsAreRefusedWithTheRule(String text) {
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> RepeatDuration.parse(text, "EVERY"));
		Assertions.assertTrue(e.getMessage().contains(RepeatDuration.ACCEPTED), e.getMessage());
		Assertions.assertTrue(e.getMessage().contains("EVERY"), "the clause is named: " + e.getMessage());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "0s|greater than zero", "-1s|negative", "500ms|milliseconds", "0.5s|decimal", "1h30m|90m", "1d|24h",
			"10|without a space", "foo|not a duration" })
	void eachRefusedFormIsExplained(String text, String explanation) {
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> RepeatDuration.parse(text, "FOR"));
		Assertions.assertTrue(e.getMessage().contains(explanation), e.getMessage());
	}

	@Test
	void aHugeNumberIsRefusedRatherThanOverflowing() {
		Assertions.assertThrows(BroadSQLException.class, () -> RepeatDuration.parse("99999999999999999999h", "FOR"));
		Assertions.assertThrows(BroadSQLException.class, () -> RepeatDuration.parse("1000000001s", "FOR"));
	}
}
