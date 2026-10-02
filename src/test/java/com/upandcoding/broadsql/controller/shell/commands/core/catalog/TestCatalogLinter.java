package com.upandcoding.broadsql.controller.shell.commands.core.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;

/**
 * {@link CatalogLinter} on the Scripts Library (SPRINT 1909S: applies to every Script; no alias check). SPRINT 0110A
 * (spec section 24): the non-contiguous {@code %N} rule is gone; legacy {@code %1..%9}, {@code ${name}} inside a
 * string literal and {@code @params} validity are reported.
 */
class TestCatalogLinter {

	private static List<String> lint(String text) {
		return new CatalogLinter().lintEntry("q.bsql", text, Set.of(), Set.of());
	}

	private static boolean has(List<String> findings, String fragment) {
		return findings.stream().anyMatch(f -> f.contains(fragment));
	}

	@Test
	void lintEntryChecksOneEntryDirectlyWithoutTouchingDisk() {
		List<String> findings = new CatalogLinter().lintEntry("unsaved-buffer.bsql", "-- @environment: NOPE\nselect 1;", Set.of(), Set.of("PROD"));
		Assertions.assertTrue(has(findings, "NOPE"), findings.toString());
	}

	@Test
	void anAliasLineIsInertAndNeverReported() {
		Assertions.assertTrue(lint("-- @alias: rev\nselect 1;").isEmpty());
	}

	@Test
	void theNonContiguousParameterRuleIsRemoved() {
		List<String> findings = lint("select * from t where a = %1 and b = %3;");
		Assertions.assertFalse(has(findings, "non-contiguous"), findings.toString());
	}

	@Test
	void aLegacyPositionalParameterIsAMigrationFinding() {
		List<String> findings = lint("select * from t where a = %1 and b = '%2';");
		Assertions.assertTrue(has(findings, "statement 1: possible legacy positional parameter %1; positional parameters were removed"), findings.toString());
		Assertions.assertTrue(has(findings, "possible legacy positional parameter %2"), findings.toString());
	}

	@Test
	void aLikePatternIsReportedTooBecauseTheRuleIsIndicative() {
		Assertions.assertTrue(has(lint("select * from t where c like '%1%';"), "legacy positional parameter %1"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "select '%10';", "select 100 % 2;", "select '%';", "-- %1 in a comment\nselect 1;" })
	void notLegacyPositionalParameters(String text) {
		Assertions.assertFalse(has(lint(text), "legacy positional"), lint(text).toString());
	}

	@Test
	void aReferenceInsideAStringLiteralIsReportedButNotOutside() {
		List<String> findings = lint("select * from t where n = '${n}' and m = ${m};");
		Assertions.assertTrue(has(findings, "${n} is not substituted inside a string literal; write ${n} outside quotes to bind it"), findings.toString());
		Assertions.assertFalse(has(findings, "${m}"), findings.toString());
	}

	@Test
	void theStringLiteralRuleCoversLetAndArgumentLiteralsButNotEcho() {
		Assertions.assertTrue(has(lint("LET x = '${y}';"), "${y} is not substituted"));
		Assertions.assertTrue(has(lint("@child.bsql label='${y}';"), "${y} is not substituted"));
		Assertions.assertTrue(has(lint("DUMP (SELECT * FROM t WHERE n = '${y}') TO f AS CSV;"), "${y} is not substituted"));
		Assertions.assertTrue(has(lint("LIB RUN child.bsql label='${y}';"), "${y} is not substituted"));
		Assertions.assertTrue(has(lint("PULL (SELECT '${y}') TO f AS CSV;"), "${y} is not substituted"));
		Assertions.assertTrue(lint("ECHO 'Value ${y}';").isEmpty(), lint("ECHO 'Value ${y}';").toString());
		Assertions.assertTrue(lint("PRINT '${y}';").isEmpty(), "other BroadSQL commands are out of the rule's scope");
	}

	@Test
	void malformedOrQuotedIdentifierTextIsNotReportedAsAReference() {
		Assertions.assertTrue(lint("select '${ x}', \"${y}\";").isEmpty(), lint("select '${ x}', \"${y}\";").toString());
	}

	@Test
	void paramsValidity() {
		List<String> findings = lint("-- @params: id, 1st, env, ID, a-b\nselect ${id};");
		Assertions.assertTrue(has(findings, "@params declares an invalid parameter: Invalid name '1st'"), findings.toString());
		Assertions.assertTrue(has(findings, "Invalid name 'env': ENV, NULL, TRUE and FALSE are reserved"), findings.toString());
		Assertions.assertTrue(has(findings, "Invalid name 'a-b'"), findings.toString());
		Assertions.assertTrue(has(findings, "@params declares ID more than once"), findings.toString());
	}

	@Test
	void validParamsProduceNoFinding() {
		Assertions.assertTrue(lint("-- @params: customer_id, country\nselect ${customer_id}, ${country};").isEmpty());
	}

	@Test
	void lintIsStaticAndNeverDependsOnTheSession() {
		// an undefined variable is a runtime matter, never a lint finding
		Assertions.assertTrue(lint("select ${never_defined};").isEmpty());
	}

	private static ScriptsLibrary libraryWith(Path root, String name, String content) throws IOException, BroadSQLException {
		Path file = root.resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		return new ScriptsLibrary(root.toString());
	}

	@Test
	void reportsLegacyParametersInAnyScriptWhateverItsExtension(@TempDir Path root) throws IOException, BroadSQLException {
		ScriptsLibrary library = libraryWith(root, "sub/q.txt", "select * from t where a = %1;");

		List<String> findings = new CatalogLinter().lint(library, Set.of(), Set.of());

		Assertions.assertTrue(has(findings, "sub/q.txt: statement 1: possible legacy positional parameter %1"), findings.toString());
	}

	@Test
	void reportsUnknownEnvironmentAndInstanceIds(@TempDir Path root) throws IOException, BroadSQLException {
		ScriptsLibrary library = libraryWith(root, "q.bsql", "-- @environment: NOPE\n-- @instance: GHOST\nselect 1;");

		List<String> findings = new CatalogLinter().lint(library, Set.of("REAL"), Set.of("PROD"));

		Assertions.assertTrue(has(findings, "@environment 'NOPE'"), findings.toString());
		Assertions.assertTrue(has(findings, "@instance 'GHOST'"), findings.toString());
	}

	@Test
	void skipsTheIdChecksWhenTheKnownSetsAreNull(@TempDir Path root) throws IOException, BroadSQLException {
		ScriptsLibrary library = libraryWith(root, "q.bsql", "-- @environment: NOPE\nselect 1;");

		Assertions.assertTrue(new CatalogLinter().lint(library, null, null).isEmpty());
	}

	@Test
	void duplicateAliasesAreNoLongerAFinding(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("a.bsql"), "-- @alias: same\nselect 1;");
		ScriptsLibrary library = libraryWith(root, "b.bsql", "-- @alias: same\nselect 2;");

		Assertions.assertTrue(new CatalogLinter().lint(library, Set.of(), Set.of()).isEmpty());
	}
}
