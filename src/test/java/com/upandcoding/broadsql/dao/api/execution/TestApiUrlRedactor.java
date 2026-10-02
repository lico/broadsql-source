package com.upandcoding.broadsql.dao.api.execution;

import java.net.URI;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestApiUrlRedactor {

	@Test
	void redactsOnlyTheNamedParameter() {
		URI uri = URI.create("https://example.com/data?api_key=SECRET&limit=10");
		String result = ApiUrlRedactor.redact(uri, Set.of("api_key"));
		Assertions.assertEquals("https://example.com/data?api_key=******&limit=10", result);
	}

	@Test
	void leavesTheUrlUnchangedWhenNoSecretParametersAreDeclared() {
		URI uri = URI.create("https://example.com/data?limit=10");
		Assertions.assertEquals(uri.toString(), ApiUrlRedactor.redact(uri, Set.of()));
	}

	@Test
	void leavesAUrlWithNoQueryUnchanged() {
		URI uri = URI.create("https://example.com/data");
		Assertions.assertEquals(uri.toString(), ApiUrlRedactor.redact(uri, Set.of("api_key")));
	}

	@Test
	void redactsMultipleSecretParameters() {
		URI uri = URI.create("https://example.com/data?api_key=SECRET1&token=SECRET2&limit=10");
		String result = ApiUrlRedactor.redact(uri, Set.of("api_key", "token"));
		Assertions.assertFalse(result.contains("SECRET1"));
		Assertions.assertFalse(result.contains("SECRET2"));
		Assertions.assertTrue(result.contains("limit=10"));
	}
}
