package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

class TestCsvColumnListSource {

	@Test
	void readsANamedColumnByHeader(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("customers.csv");
		Files.writeString(file, "CUSTOMER_ID,NAME,COUNTRY\nC123,John,FR\nC456,Maria,ES\nC789,Anna,DE\n", StandardCharsets.UTF_8);

		List<String> values = CsvColumnListSource.parse(file + ":CUSTOMER_ID").values();

		Assertions.assertEquals(List.of("C123", "C456", "C789"), values);
	}

	@Test
	void matchesHeaderCaseInsensitivelyWhenNoExactMatch(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("customers.csv");
		Files.writeString(file, "customer_id\nC1\nC2\n", StandardCharsets.UTF_8);

		Assertions.assertEquals(List.of("C1", "C2"), CsvColumnListSource.parse(file + ":CUSTOMER_ID").values());
	}

	@Test
	void handlesQuotedValuesWithEmbeddedCommasAndQuotes(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("customers.csv");
		Files.writeString(file, "ID,NAME\n1,\"Doe, John\"\n2,\"O\"\"BRIEN\"\n", StandardCharsets.UTF_8);

		Assertions.assertEquals(List.of("Doe, John", "O\"BRIEN"), CsvColumnListSource.parse(file + ":NAME").values());
	}

	@Test
	void skipsBlankCellsInTheTargetColumn(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("customers.csv");
		Files.writeString(file, "ID\nA\n\nB\n", StandardCharsets.UTF_8);

		Assertions.assertEquals(List.of("A", "B"), CsvColumnListSource.parse(file + ":ID").values());
	}

	@Test
	void handlesALeadingByteOrderMarkOnTheHeaderRow(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("customers.csv");
		Files.write(file, ("\uFEFFID\nA\nB\n").getBytes(StandardCharsets.UTF_8));

		Assertions.assertEquals(List.of("A", "B"), CsvColumnListSource.parse(file + ":ID").values());
	}

	@Test
	void missingColumnFailsClearlyAndListsAvailableColumns(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("customers.csv");
		Files.writeString(file, "ID,NAME\n1,Alice\n", StandardCharsets.UTF_8);

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> CsvColumnListSource.parse(file + ":MISSING").values());
		Assertions.assertTrue(ex.getMessage().contains("MISSING"), "got: " + ex.getMessage());
		Assertions.assertTrue(ex.getMessage().contains("ID"), "expected available columns listed, got: " + ex.getMessage());
	}

	@Test
	void duplicateColumnNamesFailClearly(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("customers.csv");
		Files.writeString(file, "ID,ID\n1,2\n", StandardCharsets.UTF_8);

		Assertions.assertThrows(BroadSQLException.class, () -> CsvColumnListSource.parse(file + ":ID").values());
	}

	@Test
	void missingFileFailsClearly() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> CsvColumnListSource.parse("does-not-exist-xyz.csv:ID").values());
		Assertions.assertTrue(ex.getMessage().contains("not found"), "got: " + ex.getMessage());
	}

	@Test
	void parsesAWindowsPathWithADriveLetterColonCorrectly() throws BroadSQLException {
		// The path's own drive-letter colon must not be mistaken for the path/column separator.
		CsvColumnListSource source = CsvColumnListSource.parse("c:\\temp\\customers.csv:CUSTOMER_ID");
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, source::values);
		Assertions.assertTrue(ex.getMessage().contains("c:\\temp\\customers.csv"),
				"expected the full path (with its drive letter) preserved, got: " + ex.getMessage());
	}
}
