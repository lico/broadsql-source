package com.upandcoding.broadsql.controller.shell.repeat;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** GitHub #196: the monitoring output's timestamp column takes the first free name, never renaming a column of the result. */
class TestRepeatTimestampColumnName {

	@Test
	void noCollision() {
		Assertions.assertEquals("TIMESTAMP", RepeatMonitorOutput.timestampColumnName(List.of("STATUS", "CNT")));
		Assertions.assertEquals("TIMESTAMP", RepeatMonitorOutput.timestampColumnName(List.of()));
	}

	@Test
	void timestampCollision() {
		Assertions.assertEquals("REPEAT_TIMESTAMP", RepeatMonitorOutput.timestampColumnName(List.of("STATUS", "TIMESTAMP")));
		Assertions.assertEquals("REPEAT_TIMESTAMP", RepeatMonitorOutput.timestampColumnName(List.of("timestamp")), "compared without regard to case");
	}

	@Test
	void timestampAndRepeatTimestampCollision() {
		Assertions.assertEquals("REPEAT_TIMESTAMP_2", RepeatMonitorOutput.timestampColumnName(List.of("TIMESTAMP", "REPEAT_TIMESTAMP")));
	}

	@Test
	void multipleCollisionsTakeTheFirstFreeNumber() {
		Assertions.assertEquals("REPEAT_TIMESTAMP_4",
				RepeatMonitorOutput.timestampColumnName(List.of("TIMESTAMP", "repeat_timestamp", "REPEAT_TIMESTAMP_2", "REPEAT_TIMESTAMP_3")));
		Assertions.assertEquals("REPEAT_TIMESTAMP_2", RepeatMonitorOutput.timestampColumnName(List.of("TIMESTAMP", "REPEAT_TIMESTAMP", "REPEAT_TIMESTAMP_3")),
				"the first free name, even with a gap after it");
		Assertions.assertEquals("REPEAT_TIMESTAMP", RepeatMonitorOutput.timestampColumnName(List.of("TIMESTAMP", "REPEAT_TIMESTAMP_2")));
	}
}
