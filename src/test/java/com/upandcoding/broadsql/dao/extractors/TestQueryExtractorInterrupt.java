package com.upandcoding.broadsql.dao.extractors;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.CommandInterruptedException;
import com.upandcoding.broadsql.controller.shell.commands.CommandCancellation;

/**
 * Verifies the CTRL+C cancellation mechanism at the level that does not require an interactive
 * console/TTY: requesting cancellation (via CommandCancellation, NOT Thread.interrupt() - see
 * docs/TECHNICAL_CHANGE.md for why) while an export is running must stop it with a
 * CommandInterruptedException and release the output file handle (so the caller can delete the
 * partial file). See docs/TODO.md ("Ameliorations de la GUI").
 */
public class TestQueryExtractorInterrupt {

	private File tempFile;

	@AfterEach
	public void cleanup() {
		CommandCancellation.reset();
		if (tempFile != null && tempFile.exists()) {
			tempFile.delete();
		}
	}

	@Test
	public void testInterruptStopsExportAndReleasesFile() throws Exception {
		tempFile = File.createTempFile("broadsql-interrupt-test", ".txt");

		Class.forName("org.h2.Driver");
		try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:interrupttest;DB_CLOSE_DELAY=-1")) {
			Statement stmt = conn.createStatement();
			// A virtual, effectively unbounded result set: whatever the interrupt timing, the loop
			// cannot legitimately finish before it is cancelled.
			ResultSet rs = stmt.executeQuery("SELECT X FROM SYSTEM_RANGE(1, 100000000)");

			AtomicReference<Throwable> caught = new AtomicReference<>();
			Thread worker = new Thread(() -> {
				try {
					QueryExtractorToFile.extractToTextFile(rs, tempFile.getAbsolutePath(), '\t', false);
				} catch (Throwable t) {
					caught.set(t);
				}
			}, "test-export-worker");
			CommandCancellation.reset();
			worker.start();
			Thread.sleep(50);
			CommandCancellation.request();
			worker.join(10000);

			Assertions.assertFalse(worker.isAlive(), "worker thread should have terminated after cancellation");
			Assertions.assertTrue(caught.get() instanceof CommandInterruptedException,
					"expected a CommandInterruptedException, got: " + caught.get());

			// The file handle must be released even though the export was aborted mid-way,
			// otherwise the caller could not delete the partial file (especially on Windows).
			Assertions.assertTrue(tempFile.delete(), "partial file should be deletable (file handle released)");
			tempFile = null;
		}
	}
}
