package com.upandcoding.broadsql.controller.shell.completion;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidateType;

/**
 * SPRINT 2409K: {@link FilesystemPathCompletion}. Unix-style ({@code /}) tests run everywhere (Java accepts
 * {@code /} on Windows too); Windows-style ({@code \}, drive letters) tests run where the filesystem uses
 * them. The typed part is always kept exactly: never a doubled backslash, never a converted separator.
 */
class TestFilesystemPathCompletion {

	@TempDir
	Path root;
	private FilesystemPathCompletion paths;
	private Path exportFolder;

	@BeforeEach
	void setUp() throws Exception {
		Files.createDirectories(root.resolve("scripts/sub"));
		Files.createDirectories(root.resolve("My Data"));
		Files.writeString(root.resolve("scripts/report.sql"), "SELECT 1;");
		Files.writeString(root.resolve("scripts/Readme.txt"), "x");
		Files.writeString(root.resolve("My Data/ids list.txt"), "1");
		exportFolder = Files.createDirectories(root.resolve("export"));
		Files.writeString(exportFolder.resolve("orders.csv"), "ID\n1\n");
		Files.createDirectories(exportFolder.resolve("olddir"));
		paths = new FilesystemPathCompletion(() -> root, () -> exportFolder.toString() + File.separator);
	}

	private static List<String> values(List<CompletionCandidate> candidates) {
		List<String> v = new ArrayList<>();
		candidates.forEach(c -> v.add(c.getValue()));
		return v;
	}

	@Test
	void unixStyleRelativePathsKeepTheirSlashes() {
		Assertions.assertEquals(List.of("scripts/Readme.txt", "scripts/report.sql", "scripts/sub/"), values(paths.complete("scripts/", "", true, false)));
		Assertions.assertEquals(List.of("scripts/Readme.txt", "scripts/report.sql"), values(paths.complete("scripts/re", "", true, false)), "case-insensitive prefix");
		List<CompletionCandidate> dir = paths.complete("scr", "", true, false);
		Assertions.assertEquals(List.of("scripts" + File.separator), values(dir), "a bare prefix resolves against the working directory; nothing typed, so the platform separator");
		Assertions.assertEquals(CompletionCandidateType.DIRECTORY, dir.get(0).getType());
		Assertions.assertEquals(CompletionCandidateType.FILE, paths.complete("scripts/report", "", true, false).get(0).getType());
	}

	@Test
	void unixStyleAbsolutePaths() {
		String absolute = root.toAbsolutePath().toString().replace('\\', '/') + "/scripts/su";
		Assertions.assertEquals(List.of(absolute.substring(0, absolute.length() - 2) + "sub/"), values(paths.complete(absolute, "", true, false)));
	}

	@Test
	void windowsStyleBackslashPathsAreNeverDoubled() {
		Assumptions.assumeTrue(File.separatorChar == '\\', "backslash is a separator only on Windows");
		String typed = root.toAbsolutePath() + "\\scripts\\re";
		List<String> result = values(paths.complete(typed, "", true, false));
		Assertions.assertEquals(List.of(root.toAbsolutePath() + "\\scripts\\Readme.txt", root.toAbsolutePath() + "\\scripts\\report.sql"), result);
		for (String value : result) {
			Assertions.assertFalse(value.contains("\\\\"), "doubled backslash in " + value);
		}
		Assertions.assertEquals(List.of("scripts\\sub\\"), values(paths.complete("scripts\\s", "", true, false)), "relative, backslash kept");
	}

	@Test
	void aBareDriveListsItsRoot() {
		Assumptions.assumeTrue(File.separatorChar == '\\', "drive letters exist only on Windows");
		String drive = root.toAbsolutePath().toString().substring(0, 2);
		List<CompletionCandidate> result = paths.complete(drive, "", true, false);
		Assertions.assertFalse(result.isEmpty());
		Assertions.assertTrue(result.get(0).getValue().startsWith(drive + "\\"), result.get(0).getValue());
	}

	@Test
	void namesWithSpacesAreQuotedWithBroadSqlQuotesAndDirectoriesStayOpen() {
		Assertions.assertEquals(List.of("\"My Data" + File.separator), values(paths.complete("My", "", true, false)));
		Assertions.assertEquals(List.of("\"My Data/ids list.txt\""), values(paths.complete("My Data/", "", true, false)));
		Assertions.assertEquals(List.of("My Data" + File.separator), values(paths.complete("My", "", false, false)), "unquoted where the grammar has no quoting (<@...>)");
	}

	@Test
	void aBareNameInAnExportFolderPositionListsThatFoldersFilesOnly() {
		Assertions.assertEquals(List.of("orders.csv"), values(paths.complete("o", "", true, true)));
		Assertions.assertEquals(List.of("scripts" + File.separator), values(paths.complete("scripts", "", true, false)));
		Assertions.assertEquals(List.of("scripts/sub/"), values(paths.complete("scripts/s", "", true, true)), "a directory part is a filesystem path again");
	}

	@Test
	void theValuePrefixIsKeptAndAMissingDirectoryYieldsNothing() {
		Assertions.assertEquals(List.of("<@csv:scripts/report.sql"), values(paths.complete("scripts/rep", "<@csv:", false, false)));
		Assertions.assertTrue(paths.complete("no/such/dir/", "", true, false).isEmpty());
	}

	@Test
	void theAtFormsFollowScriptResolverRules() {
		Assertions.assertTrue(FilesystemPathCompletion.isExplicitFilesystemReference("./x"));
		Assertions.assertTrue(FilesystemPathCompletion.isExplicitFilesystemReference(root.toAbsolutePath().toString()));
		Assertions.assertFalse(FilesystemPathCompletion.isExplicitFilesystemReference("maintenance/cleanup.bsql"), "a library reference");
		Assertions.assertFalse(FilesystemPathCompletion.isExplicitFilesystemReference(""));
		if (File.separatorChar == '\\') {
			Assertions.assertTrue(FilesystemPathCompletion.isExplicitFilesystemReference(".\\x"));
			Assertions.assertTrue(FilesystemPathCompletion.isExplicitFilesystemReference("C:"));
			Assertions.assertTrue(FilesystemPathCompletion.isExplicitFilesystemReference("C:\\temp"));
		}
	}
}
