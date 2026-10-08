package com.upandcoding.broadsql.dao.api.bruno;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestBrunoPlaceholderNormalizer {

	@Test
	void convertsMustachePlaceholdersToBroadSqlSyntax() {
		Assertions.assertEquals("${baseUrl}/users", BrunoPlaceholderNormalizer.normalize("{{baseUrl}}/users"));
	}

	@Test
	void convertsMultiplePlaceholdersInOneString() {
		Assertions.assertEquals("${baseUrl}/${resource}/${userId}", BrunoPlaceholderNormalizer.normalize("{{baseUrl}}/{{resource}}/{{userId}}"));
	}

	@Test
	void toleratesWhitespaceInsideBraces() {
		Assertions.assertEquals("${token}", BrunoPlaceholderNormalizer.normalize("{{ token }}"));
	}

	@Test
	void leavesPlainStringsUnchanged() {
		Assertions.assertEquals("application/json", BrunoPlaceholderNormalizer.normalize("application/json"));
	}

	@Test
	void returnsNullForNull() {
		Assertions.assertNull(BrunoPlaceholderNormalizer.normalize(null));
	}
}
