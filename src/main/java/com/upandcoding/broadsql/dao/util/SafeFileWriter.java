package com.upandcoding.broadsql.dao.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes text content to a target file safely: temporary file next to the target, written and
 * closed fully, then moved over the target only once that succeeds - so a failure partway through
 * a write never leaves a half-written file in the target's place. Extracted (SPRINT 0917-01, Script
 * Library, Editor and Version History) from the temp-file-then-{@code Files.move(..., REPLACE_EXISTING)}
 * idiom duplicated across {@code dao/pull/*}'s exporters (see {@code PullToTextExporter} for the
 * original, independently-written instance of this same pattern) - a single reusable utility rather
 * than a seventh copy, used by the new revision vault and by a hardened {@code ScriptsLibrary.write()}.
 * The pre-existing exporter call sites are deliberately left as-is this sprint; only the utility is
 * extracted and pointed at by new/hardened code, per the sprint's "don't touch what isn't broken"
 * scope.
 *
 * <p>Deliberately throws plain {@link IOException} rather than {@code BroadSQLException} - this is a
 * low-level filesystem utility with callers across more than one exception-handling convention
 * ({@code ScriptsLibrary.write()} wraps into {@code BroadSQLException}; {@code RevisionVault} has its
 * own error handling for history-write failures per the Save/history partial-failure semantics) -
 * each caller decides its own wrapping rather than this class picking one for all of them.
 */
public final class SafeFileWriter {

	private SafeFileWriter() {
	}

	/**
	 * Writes {@code content} to {@code target} atomically (relative to any reader of the target path):
	 * creates parent directories if needed, writes to a sibling temporary file, then moves it over
	 * {@code target} with {@link StandardCopyOption#REPLACE_EXISTING}. The temporary file is removed on
	 * any failure - callers never observe a partially-written target or a leaked temp file on the
	 * failure path.
	 */
	public static void writeString(Path target, String content, Charset charset) throws IOException {
		writeBytes(target, content.getBytes(charset));
	}

	/** Same atomic temp-file-then-move idiom as {@link #writeString}, for already-encoded bytes (SPRINT 1909S: lets {@code ScriptTextIO} reproduce a file's BOM). */
	public static void writeBytes(Path target, byte[] content) throws IOException {
		Path parent = target.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		File targetFile = target.toFile();
		File tempFile = File.createTempFile(targetFile.getName() + ".", ".tmp", parent != null ? parent.toFile() : null);
		try {
			Files.write(tempFile.toPath(), content);
			Files.move(tempFile.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			tempFile.delete();
			throw e;
		}
	}
}
