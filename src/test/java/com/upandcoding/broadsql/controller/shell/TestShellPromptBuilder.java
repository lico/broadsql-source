package com.upandcoding.broadsql.controller.shell;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;

/**
 * SPRINT XT02-7B, section 7: the four prompt shapes (SQL+API, SQL only, API only, neither) - pure
 * unit tests, no console/Swing involved.
 */
class TestShellPromptBuilder {

	@AfterEach
	void tearDown() {
		ApiSessionContextHolder.clear();
	}

	@Test
	void sqlOnlyWhenNoApiContextIsActive() {
		Assertions.assertEquals("$CDF> ", ShellPromptBuilder.build("$CDF"));
	}

	@Test
	void sqlAndApiWhenBothAreActive() {
		ApiSessionContextHolder.set(new ApiSessionContext("DESK_DEFINITION", "PROD"));
		Assertions.assertEquals("$CDF [API DESK_DEFINITION:PROD]> ", ShellPromptBuilder.build("$CDF"));
	}

	@Test
	void apiOnlyWhenThePlatformIsBlank() {
		ApiSessionContextHolder.set(new ApiSessionContext("DESK_DEFINITION", "PROD"));
		Assertions.assertEquals("[API DESK_DEFINITION:PROD]> ", ShellPromptBuilder.build(null));
		Assertions.assertEquals("[API DESK_DEFINITION:PROD]> ", ShellPromptBuilder.build("  "));
	}

	@Test
	void neitherContextIsActive() {
		Assertions.assertEquals("> ", ShellPromptBuilder.build(null));
	}

	@Test
	void thePromptNeverSuggestsTheSqlConnectionDisappearedWhileItRemainsActive() {
		// The safety principle from section 7.5: the API segment must never hide/replace the SQL segment.
		ApiSessionContextHolder.set(new ApiSessionContext("DESK", "DEV"));
		String prompt = ShellPromptBuilder.build("$MYDB");
		Assertions.assertTrue(prompt.startsWith("$MYDB "), "the SQL platform must still be visible: " + prompt);
		Assertions.assertTrue(prompt.contains("[API DESK:DEV]"), "the API context must still be visible: " + prompt);
	}
}
