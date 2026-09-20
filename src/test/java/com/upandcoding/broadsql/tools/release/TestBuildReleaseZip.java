package com.upandcoding.broadsql.tools.release;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for the DOWNLOADS.md placement bug (docs/TECHNICAL_CHANGE.md, 2026-09-05):
 * {@link BuildReleaseZip#regenerateDownloadsManifest} used to be called with a single directory
 * reused both as where DOWNLOADS.md is written and as where each version's {@code <version>.md} is
 * read from - two genuinely different locations in the real release layout
 * ({@code releases/documentation} vs. {@code releases/documentation/release_notes}). Whichever one
 * pom.xml passed, the other usage broke silently. The method now takes the notes directory
 * separately from the DOWNLOADS.md target file, so this test exercises both from distinct temp
 * directories, mirroring the real layout.
 */
class TestBuildReleaseZip {

	@Test
	void writesDownloadsMdToItsOwnFileAndLinksReleaseNotesFromADifferentDirectory(@TempDir File tempDir) throws Exception {
		File packagesDir = new File(tempDir, "packages");
		File releaseNotesDir = new File(tempDir, "release_notes");
		File releaseDocsDir = new File(tempDir, "documentation");
		packagesDir.mkdirs();
		releaseNotesDir.mkdirs();
		releaseDocsDir.mkdirs();

		Files.write(new File(packagesDir, "BroadSQL-5.1.3.zip").toPath(), "zip-content".getBytes(StandardCharsets.UTF_8));
		Files.write(new File(packagesDir, "BroadSQL-5.1.3.zip.sha1").toPath(), "deadbeef  BroadSQL-5.1.3.zip\n".getBytes(StandardCharsets.UTF_8));
		Files.writeString(new File(releaseNotesDir, "5.1.3.md").toPath(), "# What's new with BroadSQL 5.1.3 (2026-09-05)\n");

		File downloadsMd = new File(releaseDocsDir, "DOWNLOADS.md");
		BuildReleaseZip.regenerateDownloadsManifest(packagesDir, releaseNotesDir, downloadsMd, "");

		Assertions.assertTrue(downloadsMd.isFile(), "DOWNLOADS.md must be written to releaseDocsDir, not releaseNotesDir");
		Assertions.assertFalse(new File(releaseNotesDir, "DOWNLOADS.md").isFile(),
				"DOWNLOADS.md must not also end up next to the per-version notes files");

		String content = Files.readString(downloadsMd.toPath(), StandardCharsets.UTF_8);
		Assertions.assertTrue(content.contains("| 5.1.3 | 2026-09-05 |"), "expected the 5.1.3 row with its notes-derived date, got:\n" + content);
		Assertions.assertTrue(content.contains("deadbeef"), "expected the sha1 read back from the .sha1 file, got:\n" + content);
		Assertions.assertTrue(content.contains("docs/release-notes/5.1.3.html"),
				"expected a direct link to 5.1.3's own notes page (its <version>.md exists in releaseNotesDir), got:\n" + content);
	}

	@Test
	void fallsBackToTheReleaseNotesIndexWhenAVersionHasNoOwnNotesFile(@TempDir File tempDir) throws Exception {
		File packagesDir = new File(tempDir, "packages");
		File releaseNotesDir = new File(tempDir, "release_notes");
		File releaseDocsDir = new File(tempDir, "documentation");
		packagesDir.mkdirs();
		releaseNotesDir.mkdirs();
		releaseDocsDir.mkdirs();

		Files.write(new File(packagesDir, "BroadSQL-1.0.0.zip").toPath(), "zip-content".getBytes(StandardCharsets.UTF_8));

		File downloadsMd = new File(releaseDocsDir, "DOWNLOADS.md");
		BuildReleaseZip.regenerateDownloadsManifest(packagesDir, releaseNotesDir, downloadsMd, "");

		String content = Files.readString(downloadsMd.toPath(), StandardCharsets.UTF_8);
		Assertions.assertTrue(content.contains("docs/release-notes/index.html"),
				"a version with no <version>.md in releaseNotesDir must fall back to the release-notes index, got:\n" + content);
	}
}
