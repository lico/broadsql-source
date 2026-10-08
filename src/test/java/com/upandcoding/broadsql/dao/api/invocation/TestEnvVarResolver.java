package com.upandcoding.broadsql.dao.api.invocation;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * SPRINT XT02A (URL-Native API Execution), section 3.3 - {@code ${ENV:NAME}} resolution. Uses the
 * injected-lookup overload throughout (never depends on the real process environment), and confirms
 * the deliberate deviation from the spec's original {@code ${NAME}} wording: an ordinary {@code ${var}}
 * (no {@code ENV:} prefix) is left completely untouched, so it never collides with the pre-existing,
 * unrelated {@code ${var}} API-variable templating.
 */
class TestEnvVarResolver {

	@Test
	void resolvesADefinedVariable() throws BroadSQLException {
		String result = EnvVarResolver.resolve("/api/customer/${ENV:CUSTOMER_ID}", name -> "CUSTOMER_ID".equals(name) ? "321" : null);
		Assertions.assertEquals("/api/customer/321", result);
	}

	@Test
	void resolvesMultipleReferences() throws BroadSQLException {
		Map<String, String> env = Map.of("A", "1", "B", "2");
		String result = EnvVarResolver.resolve("${ENV:A}-${ENV:B}", env::get);
		Assertions.assertEquals("1-2", result);
	}

	@Test
	void undefinedVariableFailsExplicitlyRatherThanSubstitutingEmptyString() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> EnvVarResolver.resolve("${ENV:MISSING}", name -> null));
		Assertions.assertTrue(ex.getLocalizedMessage().contains("'MISSING' is not defined"), ex.getLocalizedMessage());
	}

	@Test
	void ordinaryApiVariableSyntaxIsLeftUntouched() throws BroadSQLException {
		// ${customerId} (no ENV: prefix) is the pre-existing, unrelated API-variable templating syntax -
		// this resolver must never touch it, even though the lookup function below would otherwise "resolve" it.
		String result = EnvVarResolver.resolve("/api/customer/${customerId}", name -> "should-not-be-called");
		Assertions.assertEquals("/api/customer/${customerId}", result);
	}

	@Test
	void nullInputIsReturnedAsIs() throws BroadSQLException {
		Assertions.assertNull(EnvVarResolver.resolve(null, name -> "x"));
	}
}
