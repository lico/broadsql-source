package com.upandcoding.broadsql.controller.shell.commands.core.scripting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * SPRINT 1909S: {@link JsScriptCatalog} keeps the legacy catalog behaviour exclusively for the isolated
 * experimental {@code JS} command family (alias, unique-basename and {@code .sql}-appending lookup,
 * metadata header stripping, {@code archives/} reserved). None of it applies to BroadSQL Scripts, which
 * only {@code ScriptResolver} resolves. These tests pin that the JS behaviour did not change.
 */
class TestJsScriptCatalog {

	private static JsScriptCatalog catalog(Path root) throws BroadSQLException {
		return new JsScriptCatalog(root.toString(), BroadSQLErrorMessages.ERR_JSSCRIPTS_01);
	}

	@Test
	void sameNameInDifferentSubfoldersDoesNotCollide(@TempDir Path root) throws IOException, BroadSQLException {
		Files.createDirectories(root.resolve("finance"));
		Files.createDirectories(root.resolve("hr"));
		Files.writeString(root.resolve("finance/country.js"), "print(1);");
		Files.writeString(root.resolve("hr/country.js"), "print(2);");

		JsScriptCatalog catalog = catalog(root);

		Assertions.assertEquals(2, catalog.getList().size());
		Assertions.assertTrue(catalog.contains("finance/country.js"));
		Assertions.assertTrue(catalog.contains("hr/country.js"));
	}

	@Test
	void archivesFolderIsExcludedFromTheCatalog(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("country.js"), "print(1);");
		Files.createDirectories(root.resolve("archives"));
		Files.writeString(root.resolve("archives/old.js.20260101-000000"), "print(0);");

		JsScriptCatalog catalog = catalog(root);

		Assertions.assertEquals(1, catalog.getList().size());
		Assertions.assertFalse(catalog.contains("archives/old.js.20260101-000000"));
	}

	@Test
	void resolvesByExactNameByAliasAndByUniqueBasenameAsBefore(@TempDir Path root) throws IOException, BroadSQLException {
		Files.createDirectories(root.resolve("finance"));
		Files.writeString(root.resolve("finance/revenue.js"), "-- @alias: rev\nprint(1);");
		Files.writeString(root.resolve("unique.sql"), "select 1;");

		JsScriptCatalog catalog = catalog(root);

		Assertions.assertEquals("finance/revenue.js", catalog.resolve("finance/revenue.js"));
		Assertions.assertEquals("finance/revenue.js", catalog.resolve("REV"), "alias resolution is preserved for JS and is case-insensitive");
		Assertions.assertEquals("finance/revenue.js", catalog.resolve("revenue.js"), "unique basename resolution is preserved for JS");
		Assertions.assertEquals("unique.sql", catalog.resolve("unique"), "the legacy .sql-appending retry is preserved for JS (a known quirk, not changed)");
		Assertions.assertNull(catalog.resolve("doesnotexist"));
	}

	@Test
	void ambiguousBasenameDoesNotResolve(@TempDir Path root) throws IOException, BroadSQLException {
		Files.createDirectories(root.resolve("finance"));
		Files.createDirectories(root.resolve("hr"));
		Files.writeString(root.resolve("finance/country.js"), "print(1);");
		Files.writeString(root.resolve("hr/country.js"), "print(2);");

		Assertions.assertNull(catalog(root).resolve("country.js"));
	}

	@Test
	void queryBodyStripsTheMetadataHeaderBeforeJoiningLines(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("q.js"), "-- @description: d\n-- @environment: PROD\nprint('a');\nprint('b');");

		String body = catalog(root).getQueryBody("q.js");

		Assertions.assertFalse(body.contains("@description"));
		Assertions.assertTrue(body.contains("print('a');"));
		Assertions.assertTrue(body.contains("print('b');"));
	}

	@Test
	void entryMetadataIsParsedForJsScriptsToo(@TempDir Path root) throws IOException, BroadSQLException {
		Files.writeString(root.resolve("q.js"), "-- @environment: PROD\n-- @description: hello\nprint(1);");

		JsScriptCatalog catalog = catalog(root);

		Assertions.assertEquals("hello", catalog.getEntryMetadata("q.js").getDescription());
		Assertions.assertTrue(catalog.getEntryMetadata("q.js").getEnvironments().contains("PROD"));
	}

	@Test
	void blankOrMissingPathReportsTheJsCatalogMessage(@TempDir Path root) {
		BroadSQLException blank = Assertions.assertThrows(BroadSQLException.class, () -> new JsScriptCatalog("", BroadSQLErrorMessages.ERR_JSSCRIPTS_01));
		Assertions.assertEquals(BroadSQLErrorMessages.ERR_JSSCRIPTS_01, blank.getMessage());
		Assertions.assertThrows(BroadSQLException.class, () -> catalog(root.resolve("nope")));
	}
}
