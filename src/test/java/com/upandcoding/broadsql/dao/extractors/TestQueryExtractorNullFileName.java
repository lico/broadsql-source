package com.upandcoding.broadsql.dao.extractors;

import java.sql.ResultSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;

/** #10: a null export file name fails with a clear BroadSQLException, never a raw NullPointerException. */
class TestQueryExtractorNullFileName {

	@Test
	void textExportRejectsNullFileName() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> QueryExtractorToFile.extractToTextFile((ResultSet) null, null, ',', false));
		Assertions.assertTrue(ex.getMessage().contains("no file name"), ex.getMessage());
	}

	@Test
	void excelExportRejectsNullFileName() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> new QueryExtractorToExcel2007().extractToExcel2007(null, new CapturingShellConsole(), "SELECT 1", null, null));
		Assertions.assertTrue(ex.getMessage().contains("no file name"), ex.getMessage());
	}
}
