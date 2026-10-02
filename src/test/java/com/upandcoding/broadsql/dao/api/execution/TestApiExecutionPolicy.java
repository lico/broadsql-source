package com.upandcoding.broadsql.dao.api.execution;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/** SPRINT XT02-8: the allowlist widened from GET/HEAD-only to include POST/PUT/PATCH/DELETE. */
class TestApiExecutionPolicy {

	@Test
	void getIsExecutable() throws BroadSQLException {
		Assertions.assertDoesNotThrow(() -> ApiExecutionPolicy.checkExecutable("GET"));
	}

	@Test
	void headIsExecutable() throws BroadSQLException {
		Assertions.assertDoesNotThrow(() -> ApiExecutionPolicy.checkExecutable("HEAD"));
	}

	@Test
	void methodCaseIsIgnored() throws BroadSQLException {
		Assertions.assertDoesNotThrow(() -> ApiExecutionPolicy.checkExecutable("get"));
		Assertions.assertDoesNotThrow(() -> ApiExecutionPolicy.checkExecutable("post"));
	}

	// This test used to assert POST was refused (pre-XT02-8). Flipped deliberately, not weakened: XT02-8's
	// entire purpose is making POST/PUT/PATCH/DELETE executable.
	@Test
	void postPutPatchDeleteAreAllExecutable() {
		for (String method : new String[] { "POST", "PUT", "PATCH", "DELETE" }) {
			Assertions.assertDoesNotThrow(() -> ApiExecutionPolicy.checkExecutable(method), method + " must be executable after XT02-8");
			Assertions.assertTrue(ApiExecutionPolicy.isExecutable(method), method + " must report executable after XT02-8");
		}
	}

	@Test
	void optionsIsRefusedWithTheRequiredMessage() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> ApiExecutionPolicy.checkExecutable("OPTIONS"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("Execution refused"));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("OPTIONS"));
		Assertions.assertFalse(ApiExecutionPolicy.isExecutable("OPTIONS"));
	}

	@Test
	void unknownAndNullMethodsAreRefused() {
		Assertions.assertThrows(BroadSQLException.class, () -> ApiExecutionPolicy.checkExecutable("BOGUS"));
		Assertions.assertThrows(BroadSQLException.class, () -> ApiExecutionPolicy.checkExecutable(null));
		Assertions.assertFalse(ApiExecutionPolicy.isExecutable("BOGUS"));
		Assertions.assertFalse(ApiExecutionPolicy.isExecutable(null));
	}
}
