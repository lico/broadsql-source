package com.upandcoding.broadsql.dao.api;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

class TestApiVariableSubstitutor {

	@Test
	void substitutesASingleVariable() throws BroadSQLException {
		String result = ApiVariableSubstitutor.substitute("Bearer ${token}", Map.of("token", "abc123"), "authentication variable");
		Assertions.assertEquals("Bearer abc123", result);
	}

	@Test
	void substitutesMultipleVariables() throws BroadSQLException {
		String result = ApiVariableSubstitutor.substitute("${baseUrl}/oauth/token", Map.of("baseUrl", "https://dev.example.com"), "authentication variable");
		Assertions.assertEquals("https://dev.example.com/oauth/token", result);
	}

	@Test
	void leavesAPlainStringWithNoPlaceholdersUnchanged() throws BroadSQLException {
		Assertions.assertEquals("literal-value", ApiVariableSubstitutor.substitute("literal-value", Map.of(), "authentication variable"));
	}

	@Test
	void returnsNullForANullTemplate() throws BroadSQLException {
		Assertions.assertNull(ApiVariableSubstitutor.substitute(null, Map.of(), "authentication variable"));
	}

	@Test
	void throwsWithTheExactRequiredMessageWhenAVariableIsMissing() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ApiVariableSubstitutor.substitute("Bearer ${token}", Map.of(), "authentication variable"));
		Assertions.assertEquals("Unable to resolve authentication variable: token", ex.getLocalizedMessage());
	}

	@Test
	void errorMessageNeverRevealsOtherVariableValues() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ApiVariableSubstitutor.substitute("${clientId}:${clientSecret}", Map.of("clientId", "abc"), "authentication variable"));
		Assertions.assertFalse(ex.getLocalizedMessage().contains("abc"), "the error must name only the missing variable, never echo an already-resolved one");
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02-7B: {{name}} accepted alongside ${name}
	// ------------------------------------------------------------------------------------------

	@Test
	void substitutesAMustacheStyleVariable() throws BroadSQLException {
		String result = ApiVariableSubstitutor.substitute("Bearer {{token}}", Map.of("token", "abc123"), "authentication variable");
		Assertions.assertEquals("Bearer abc123", result);
	}

	@Test
	void substitutesTheBaseUrlMustacheVariable() throws BroadSQLException {
		String result = ApiVariableSubstitutor.substitute("{{baseUrl}}/users", Map.of("baseUrl", "https://dev.example.com"), "URL");
		Assertions.assertEquals("https://dev.example.com/users", result);
	}

	@Test
	void aTemplateMayMixBothSyntaxesTogether() throws BroadSQLException {
		String result = ApiVariableSubstitutor.substitute("{{baseUrl}}/users?key=${key}",
				Map.of("baseUrl", "https://dev.example.com", "key", "DESK"), "URL");
		Assertions.assertEquals("https://dev.example.com/users?key=DESK", result);
	}

	@Test
	void anUnresolvedMustacheVariableStillThrowsBeforeAnyRequest() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ApiVariableSubstitutor.substitute("Bearer {{token}}", Map.of(), "authentication variable"));
		Assertions.assertEquals("Unable to resolve authentication variable: token", ex.getLocalizedMessage());
	}

	@Test
	void variableNameMatchingRemainsCaseSensitiveForTheMustacheSyntaxToo() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> ApiVariableSubstitutor.substitute("{{Token}}", Map.of("token", "abc123"), "authentication variable"),
				"a differently-cased variable name must not match - case sensitivity is unchanged by accepting {{name}}");
		Assertions.assertEquals("Unable to resolve authentication variable: Token", ex.getLocalizedMessage());
	}
}
