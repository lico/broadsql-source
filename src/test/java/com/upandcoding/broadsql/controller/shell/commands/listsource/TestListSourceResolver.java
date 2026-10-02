package com.upandcoding.broadsql.controller.shell.commands.listsource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

class TestListSourceResolver {

	@Test
	void routesClipboardKeywordCaseInsensitively() throws BroadSQLException {
		Assertions.assertInstanceOf(ClipboardListSource.class, ListSourceResolver.resolve("clipboard"));
		Assertions.assertInstanceOf(ClipboardListSource.class, ListSourceResolver.resolve("CLIPBOARD"));
		Assertions.assertInstanceOf(ClipboardListSource.class, ListSourceResolver.resolve("Clipboard"));
	}

	@Test
	void routesCsvPrefix() throws BroadSQLException {
		Assertions.assertInstanceOf(CsvColumnListSource.class, ListSourceResolver.resolve("csv:c:\\temp\\file.csv:ID"));
		Assertions.assertInstanceOf(CsvColumnListSource.class, ListSourceResolver.resolve("CSV:c:\\temp\\file.csv:ID"));
	}

	@Test
	void routesExcelPrefix() throws BroadSQLException {
		Assertions.assertInstanceOf(ExcelColumnListSource.class, ListSourceResolver.resolve("excel:c:\\temp\\file.xlsx:Sheet1:ID"));
		Assertions.assertInstanceOf(ExcelColumnListSource.class, ListSourceResolver.resolve("EXCEL:c:\\temp\\file.xlsx:Sheet1:ID"));
	}

	@Test
	void anythingElseIsTreatedAsAFilePathUnchangedFromTheOriginalBehavior() throws BroadSQLException {
		Assertions.assertInstanceOf(FileListSource.class, ListSourceResolver.resolve("c:\\temp\\ids.txt"));
		// A single-letter drive path must never be mistaken for the (4-5 char) csv:/excel: prefixes.
		Assertions.assertInstanceOf(FileListSource.class, ListSourceResolver.resolve("c:\\csv\\ids.txt"));
	}
}
