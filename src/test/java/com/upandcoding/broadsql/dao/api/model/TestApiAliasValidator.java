package com.upandcoding.broadsql.dao.api.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ApiAliasValidator} - docs/Amendment - Endpoint Aliases and Future Scriptability.md,
 * section 5: the regex is verbatim {@code ^[A-Za-z_][A-Za-z0-9_]*$}, applied only when non-blank (a
 * blank/absent alias is always valid - the field is optional and never auto-generated).
 */
class TestApiAliasValidator {

	@Test
	void blankOrNullIsAlwaysValid() {
		Assertions.assertTrue(ApiAliasValidator.isValid(null));
		Assertions.assertTrue(ApiAliasValidator.isValid(""));
		Assertions.assertTrue(ApiAliasValidator.isValid("   "));
	}

	@Test
	void acceptsLegalIdentifiers() {
		Assertions.assertTrue(ApiAliasValidator.isValid("DO_ORDER"));
		Assertions.assertTrue(ApiAliasValidator.isValid("GET_CUSTOMER"));
		Assertions.assertTrue(ApiAliasValidator.isValid("getInvoice"));
		Assertions.assertTrue(ApiAliasValidator.isValid("_leadingUnderscore"));
		Assertions.assertTrue(ApiAliasValidator.isValid("a1"));
	}

	@Test
	void rejectsAnythingThatIsNotALegalIdentifier() {
		Assertions.assertFalse(ApiAliasValidator.isValid("1LEADING_DIGIT"));
		Assertions.assertFalse(ApiAliasValidator.isValid("HAS SPACE"));
		Assertions.assertFalse(ApiAliasValidator.isValid("HAS-DASH"));
		Assertions.assertFalse(ApiAliasValidator.isValid("HAS.DOT"));
		Assertions.assertFalse(ApiAliasValidator.isValid("ORDERS.DO_ORDER"), "namespace syntax is explicitly out of scope for this sprint");
		Assertions.assertFalse(ApiAliasValidator.isValid("CALL(...)"));
	}
}
