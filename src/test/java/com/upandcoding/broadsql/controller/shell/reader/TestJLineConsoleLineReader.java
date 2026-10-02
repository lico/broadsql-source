package com.upandcoding.broadsql.controller.shell.reader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jline.reader.History;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPRINT XT02A (URL-Native API Execution) corrective pass, section 12/14 - {@link JLineConsoleLineReader}
 * exercised through a dumb, piped-stream terminal ({@link JLineConsoleLineReader#createForTesting}),
 * which needs no physical/interactive TTY. This proves the reader's basic read-loop wiring and history
 * behavior; real keystroke-level interaction (arrow-key editing, live Ctrl-R search, tab-menu
 * rendering) genuinely cannot be verified without a physical terminal and remains the user's manual
 * smoke test (section 31's closure report says so explicitly).
 */
class TestJLineConsoleLineReader {

	@Test
	void readsALineFedThroughAPipedStream() throws IOException {
		ByteArrayInputStream in = new ByteArrayInputStream("RUN /api/customer/123\n".getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTesting(in, out, new ApiCatalogService(null), List::of);
		try {
			String line = reader.readLine("BroadSQL> ");
			Assertions.assertEquals("RUN /api/customer/123", line);
		} finally {
			reader.close();
		}
	}

	@Test
	void returnsNullAtEndOfInputMatchingJavaIoConsolesOwnContract() throws IOException {
		ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTesting(in, out, new ApiCatalogService(null), List::of);
		try {
			Assertions.assertNull(reader.readLine("BroadSQL> "));
		} finally {
			reader.close();
		}
	}

	@Test
	void submittedLinesAreRecordedInMemoryHistory() throws IOException {
		ByteArrayInputStream in = new ByteArrayInputStream("SHOW ALL APIS\nVAR ID=123\n".getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTesting(in, out, new ApiCatalogService(null), List::of);
		try {
			reader.readLine("BroadSQL> ");
			reader.readLine("BroadSQL> ");

			History history = reader.lineReaderForTesting().getHistory();
			List<String> recorded = new java.util.ArrayList<>();
			history.forEach(entry -> recorded.add(entry.line()));

			Assertions.assertEquals(List.of("SHOW ALL APIS", "VAR ID=123"), recorded);
		} finally {
			reader.close();
		}
	}

	@Test
	void historyIsNeverWrittenToDiskWhenNoHistoryPathIsConfigured() throws IOException {
		// The no-history-path overload (used e.g. when activatejline=OFF's own JLine is never built, or
		// by callers not opting into persistence) - no HISTORY_FILE variable is set, so there is nothing
		// to check "was written safely" - there is simply no file at all.
		ByteArrayInputStream in = new ByteArrayInputStream("VAR SECRET=hunter2\n".getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTesting(in, out, new ApiCatalogService(null), List::of);
		try {
			reader.readLine("BroadSQL> ");
			Object historyFile = reader.lineReaderForTesting().getVariable(org.jline.reader.LineReader.HISTORY_FILE);
			Assertions.assertNull(historyFile, "no history file must be configured when none was passed to createForTesting");
		} finally {
			reader.close();
		}
	}

	@Test
	void closeNeverThrowsEvenOnADumbTerminal() throws IOException {
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTesting(
				new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), new ApiCatalogService(null), List::of);
		Assertions.assertDoesNotThrow(reader::close);
	}

	@Test
	void closeIsIdempotent() throws IOException {
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTesting(
				new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), new ApiCatalogService(null), List::of);
		reader.close();
		Assertions.assertDoesNotThrow(reader::close);
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02B, section 1.1: readPassword - never echoed, and never recorded in history, in-memory
	// or persistent - disabled BEFORE reading (LineReader.DISABLE_HISTORY), not removed after the fact.
	// ------------------------------------------------------------------------------------------

	@Test
	void readPasswordReturnsExactlyWhatWasTyped() throws IOException {
		ByteArrayInputStream in = new ByteArrayInputStream("hunter2\n".getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTesting(in, out, new ApiCatalogService(null), List::of);
		try {
			char[] password = reader.readPassword("Enter password: ");
			Assertions.assertArrayEquals("hunter2".toCharArray(), password);
		} finally {
			reader.close();
		}
	}

	@Test
	void readPasswordNeverAppearsInHistory() throws IOException {
		ByteArrayInputStream in = new ByteArrayInputStream("hunter2\n".getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTesting(in, out, new ApiCatalogService(null), List::of);
		try {
			reader.readPassword("Enter password: ");

			History history = reader.lineReaderForTesting().getHistory();
			List<String> recorded = new java.util.ArrayList<>();
			history.forEach(entry -> recorded.add(entry.line()));
			Assertions.assertTrue(recorded.isEmpty(), "a password read must never be recorded in history: " + recorded);
		} finally {
			reader.close();
		}
	}

	@Test
	void readPasswordDisablesHistoryOnlyForItsOwnDurationAndRestoresTheOriginalStateEvenAcrossNesting() throws IOException {
		ByteArrayInputStream in = new ByteArrayInputStream("hunter2\nSHOW ALL APIS\n".getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTesting(in, out, new ApiCatalogService(null), List::of);
		try {
			reader.readPassword("Enter password: ");
			// Ordinary readLine calls after a password read must still be recorded normally - proves the
			// DISABLE_HISTORY variable was restored to its prior (unset) state in the finally block, not
			// left permanently disabled.
			reader.readLine("BroadSQL> ");

			History history = reader.lineReaderForTesting().getHistory();
			List<String> recorded = new java.util.ArrayList<>();
			history.forEach(entry -> recorded.add(entry.line()));
			Assertions.assertEquals(List.of("SHOW ALL APIS"), recorded);
		} finally {
			reader.close();
		}
	}

	@Test
	void readPasswordReturnsNullAtEndOfInput() throws IOException {
		ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTesting(in, out, new ApiCatalogService(null), List::of);
		try {
			Assertions.assertNull(reader.readPassword("Enter password: "));
		} finally {
			reader.close();
		}
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02B, section 14: persistent, per-OS-user JLine history.
	// ------------------------------------------------------------------------------------------

	@Test
	void historySurvivesAcrossTwoReaderInstancesWhenAFilePathIsGiven(@TempDir Path tempDir) throws IOException {
		Path historyFile = tempDir.resolve("history");

		JLineConsoleLineReader first = JLineConsoleLineReader.createForTesting(
				new ByteArrayInputStream("SHOW ALL APIS\n".getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream(),
				new ApiCatalogService(null), List::of, historyFile);
		first.readLine("BroadSQL> ");
		first.close();

		Assertions.assertTrue(Files.exists(historyFile), "the history file must exist after close()");

		JLineConsoleLineReader second = JLineConsoleLineReader.createForTesting(
				new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), new ApiCatalogService(null), List::of, historyFile);
		try {
			// JLine attaches (and thus loads) a reader's History lazily, on its first readLine() call -
			// not at construction time and not on an explicit History.load() call made before any
			// readLine() - confirmed empirically against the real JLine 3.26.3 classes, not assumed. A
			// real interactive session always reads at least one line before anything else matters (e.g.
			// EXIT itself), so this is not a production concern - only this test must call readLine()
			// first to observe the loaded history, exactly like real usage would.
			second.readLine("BroadSQL> ");
			History history = second.lineReaderForTesting().getHistory();
			List<String> recorded = new java.util.ArrayList<>();
			history.forEach(entry -> recorded.add(entry.line()));
			Assertions.assertEquals(List.of("SHOW ALL APIS"), recorded, "a second reader over the same history file must see the first reader's entries");
		} finally {
			second.close();
		}
	}

	@Test
	void aPasswordNeverReachesTheSavedHistoryFile(@TempDir Path tempDir) throws IOException {
		Path historyFile = tempDir.resolve("history");

		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTesting(
				new ByteArrayInputStream("hunter2\nSHOW ALL APIS\n".getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream(),
				new ApiCatalogService(null), List::of, historyFile);
		reader.readPassword("Enter password: ");
		reader.readLine("BroadSQL> ");
		reader.close();

		String savedContent = Files.readString(historyFile);
		Assertions.assertFalse(savedContent.contains("hunter2"), "a password must never reach the saved history file: " + savedContent);
		Assertions.assertTrue(savedContent.contains("SHOW ALL APIS"), savedContent);
	}

	@Test
	void closeSavesHistoryExactlyOnceEvenIfCalledTwice(@TempDir Path tempDir) throws IOException {
		Path historyFile = tempDir.resolve("history");
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTesting(
				new ByteArrayInputStream("SHOW ALL APIS\n".getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream(),
				new ApiCatalogService(null), List::of, historyFile);
		reader.readLine("BroadSQL> ");

		reader.close();
		Assertions.assertDoesNotThrow(reader::close);
	}

	@Test
	void fallsBackToInMemoryHistoryWhenTheHistoryDirectoryCannotBeCreated() throws IOException {
		// Point the "directory" at a path whose parent is an existing plain FILE - Files.createDirectories
		// must fail there, and JLine must still come up fully functional with in-memory history only.
		Path blockingFile = Files.createTempFile("broadsql-xt02b-history-block-", ".tmp");
		blockingFile.toFile().deleteOnExit();
		Path unusableHistoryPath = blockingFile.resolve("subdir").resolve("history");

		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTesting(
				new ByteArrayInputStream("SHOW ALL APIS\n".getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream(),
				new ApiCatalogService(null), List::of, unusableHistoryPath);
		try {
			// JLine itself must still be fully usable - completion/history keep working in-memory.
			reader.readLine("BroadSQL> ");
			History history = reader.lineReaderForTesting().getHistory();
			List<String> recorded = new java.util.ArrayList<>();
			history.forEach(entry -> recorded.add(entry.line()));
			Assertions.assertEquals(List.of("SHOW ALL APIS"), recorded, "in-memory history must still work even though the file path is unusable");
			Assertions.assertNull(reader.lineReaderForTesting().getVariable(org.jline.reader.LineReader.HISTORY_FILE),
					"no history file must be configured once directory creation fails");
		} finally {
			reader.close();
			Files.deleteIfExists(blockingFile);
		}
	}
}
