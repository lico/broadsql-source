package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/** Locks in {@code <@file>}'s original, pre-refactor behavior - see class Javadoc on {@link FileListSource}. */
class TestFileListSource {

	@Test
	void readsNonBlankLinesTrimmed(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("ids.txt");
		Files.writeString(file, "  A  \nB\n\nC\n", StandardCharsets.UTF_8);

		List<String> values = new FileListSource(file.toString()).values();

		Assertions.assertEquals(List.of("A", "B", "C"), values, "blank lines dropped, each line trimmed");
	}

	@Test
	void keepsDuplicatesAndOrder(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("ids.txt");
		Files.writeString(file, "B\nA\nA\n", StandardCharsets.UTF_8);

		Assertions.assertEquals(List.of("B", "A", "A"), new FileListSource(file.toString()).values());
	}

	@Test
	void handlesCrlfAndTrailingNewline(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("ids.txt");
		Files.write(file, "A\r\nB\r\n".getBytes(StandardCharsets.UTF_8));

		Assertions.assertEquals(List.of("A", "B"), new FileListSource(file.toString()).values());
	}

	@Test
	void emptyFileProducesAnEmptyList(@TempDir Path tempDir) throws IOException, BroadSQLException {
		Path file = tempDir.resolve("empty.txt");
		Files.writeString(file, "", StandardCharsets.UTF_8);

		Assertions.assertTrue(new FileListSource(file.toString()).values().isEmpty());
	}

	@Test
	void missingFileFailsClearly() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> new FileListSource("does-not-exist-xyz.txt").values());
		Assertions.assertTrue(ex.getMessage().contains("not found"), "got: " + ex.getMessage());
	}
}
