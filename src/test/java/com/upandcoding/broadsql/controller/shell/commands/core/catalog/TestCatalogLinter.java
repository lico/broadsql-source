package com.upandcoding.broadsql.controller.shell.commands.core.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

class TestCatalogLinter {

	@Test
	void reportsNonContiguousParams(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("q.sql"), "select * from t where a = %1 and b = %3;");
		FileCatalog catalog = new FileCatalog(root.toString());

		List<String> findings = new CatalogLinter().lint(catalog, Set.of(), Set.of(), true);

		Assertions.assertTrue(findings.stream().anyMatch(f -> f.contains("non-contiguous")), findings.toString());
	}

	@Test
	void paramGapsAreSkippedWhenCheckParamGapsIsFalse(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("q.sql"), "select * from t where a = %1 and b = %3;");
		FileCatalog catalog = new FileCatalog(root.toString());

		List<String> findings = new CatalogLinter().lint(catalog, Set.of(), Set.of(), false);

		Assertions.assertTrue(findings.isEmpty(), findings.toString());
	}

	@Test
	void reportsUnknownEnvironmentId(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("q.sql"), "-- @environment: NOPE\nselect 1;");
		FileCatalog catalog = new FileCatalog(root.toString());

		List<String> findings = new CatalogLinter().lint(catalog, Set.of(), Set.of("PROD", "TEST"), false);

		Assertions.assertTrue(findings.stream().anyMatch(f -> f.contains("NOPE")), findings.toString());
	}

	@Test
	void knownEnvironmentIdIsNotReported(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("q.sql"), "-- @environment: PROD\nselect 1;");
		FileCatalog catalog = new FileCatalog(root.toString());

		List<String> findings = new CatalogLinter().lint(catalog, Set.of(), Set.of("PROD", "TEST"), false);

		Assertions.assertTrue(findings.isEmpty(), findings.toString());
	}

	@Test
	void reportsUnknownInstanceId(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("q.sql"), "-- @instance: NOPE\nselect 1;");
		FileCatalog catalog = new FileCatalog(root.toString());

		List<String> findings = new CatalogLinter().lint(catalog, Set.of("Wiki1", "JIRA"), Set.of(), false);

		Assertions.assertTrue(findings.stream().anyMatch(f -> f.contains("NOPE")), findings.toString());
	}

	@Test
	void knownInstanceIdIsNotReported(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("q.sql"), "-- @instance: Wiki1\nselect 1;");
		FileCatalog catalog = new FileCatalog(root.toString());

		List<String> findings = new CatalogLinter().lint(catalog, Set.of("Wiki1", "JIRA"), Set.of(), false);

		Assertions.assertTrue(findings.isEmpty(), findings.toString());
	}

	@Test
	void reportsDuplicateAlias(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("a.sql"), "-- @alias: rev\nselect 1;");
		Files.writeString(root.resolve("b.sql"), "-- @alias: rev\nselect 2;");
		FileCatalog catalog = new FileCatalog(root.toString());

		List<String> findings = new CatalogLinter().lint(catalog, Set.of(), Set.of(), false);

		Assertions.assertTrue(findings.stream().anyMatch(f -> f.contains("rev")), findings.toString());
	}

	@Test
	void cleanCatalogHasNoFindings(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("q.sql"), "-- @instance: MYSAP\n-- @environment: PROD\n-- @alias: rev\nselect * from t where a = %1 and b = %2;");
		FileCatalog catalog = new FileCatalog(root.toString());

		List<String> findings = new CatalogLinter().lint(catalog, Set.of("MYSAP"), Set.of("PROD"), true);

		Assertions.assertTrue(findings.isEmpty(), findings.toString());
	}
}
