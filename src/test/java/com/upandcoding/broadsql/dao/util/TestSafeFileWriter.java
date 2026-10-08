package com.upandcoding.broadsql.dao.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestSafeFileWriter {

	@Test
	void writesANewFile(@TempDir Path root) throws IOException {
		Path target = root.resolve("new.sql");
		SafeFileWriter.writeString(target, "select 1;", StandardCharsets.UTF_8);

		Assertions.assertEquals("select 1;", Files.readString(target, StandardCharsets.UTF_8));
	}

	@Test
	void overwritesAnExistingFile(@TempDir Path root) throws IOException {
		Path target = root.resolve("existing.sql");
		Files.writeString(target, "old content");

		SafeFileWriter.writeString(target, "new content", StandardCharsets.UTF_8);

		Assertions.assertEquals("new content", Files.readString(target, StandardCharsets.UTF_8));
	}

	@Test
	void createsMissingParentDirectories(@TempDir Path root) throws IOException {
		Path target = root.resolve("sub/dir/file.sql");

		SafeFileWriter.writeString(target, "content", StandardCharsets.UTF_8);

		Assertions.assertTrue(Files.exists(target));
	}

	@Test
	void leavesNoTemporaryFileBehindOnSuccess(@TempDir Path root) throws IOException {
		SafeFileWriter.writeString(root.resolve("file.sql"), "content", StandardCharsets.UTF_8);

		try (var stream = Files.list(root)) {
			long fileCount = stream.count();
			Assertions.assertEquals(1, fileCount, "only the target file should remain, no leftover .tmp file");
		}
	}
}
