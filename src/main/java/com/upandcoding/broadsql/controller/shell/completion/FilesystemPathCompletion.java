package com.upandcoding.broadsql.controller.shell.completion;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidateType;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptResolver;

/**
 * SPRINT 2409K: filesystem path completion for the argument positions that really are filesystem paths
 * (see {@link CompletionEntityType#FILE_PATH} and {@link CompletionEntityType#EXPORT_FOLDER_FILE}, and the
 * {@code @} and {@code <@...>} forms handled by {@link EntityCompletionService}). JLine free, like the rest
 * of this package.
 *
 * <p>The typed text is split at its last {@code /} or {@code \}: the part before is kept exactly as typed
 * (a candidate never rewrites what the user already typed, and never doubles a backslash), the part after
 * is a case-insensitive name prefix. Appended separators follow the user's own: a backslash if the typed
 * text contains one, a slash if it contains one, the platform separator otherwise. Absolute paths
 * ({@code C:\...}, {@code /...}) are used as is; a relative directory part resolves against the JVM working
 * directory, which is what the commands themselves do with it. A bare drive ({@code C:}) lists that drive's
 * root.
 *
 * <p>A directory candidate ends with a separator and leaves the word open ({@link CompletionCandidateType#DIRECTORY});
 * a file candidate ends it ({@link CompletionCandidateType#FILE}). When {@code quote} is set, a value
 * containing whitespace is wrapped in {@code "} (BroadSQL's argument quoting, see
 * {@code CommandUtils.getArgumentsFromQuery}), with no closing quote for a directory so the next TAB can
 * continue it.
 */
public final class FilesystemPathCompletion {

	private final Supplier<Path> workingDirectory;
	private final Supplier<String> exportFolder;

	public FilesystemPathCompletion(Supplier<Path> workingDirectory, Supplier<String> exportFolder) {
		this.workingDirectory = workingDirectory;
		this.exportFolder = exportFolder;
	}

	/** Production wiring: the JVM working directory, and {@code DefaultFolder} as given. */
	public static FilesystemPathCompletion standard(Supplier<String> exportFolder) {
		return new FilesystemPathCompletion(() -> Paths.get(System.getProperty("user.dir")).toAbsolutePath(), exportFolder);
	}

	/**
	 * @param typed        the path typed so far (may be empty)
	 * @param valuePrefix  text re-inserted before every candidate (e.g. {@code @}, {@code <@csv:})
	 * @param quote        wrap values containing whitespace in {@code "}
	 * @param bareInExportFolder a name without any directory part is looked up among the files of the export
	 *                     folder ({@code DefaultFolder}), as {@code EXPORT}/{@code LOAD} resolve it; directories
	 *                     are not offered there, since a directory part would change how the name resolves
	 */
	public List<CompletionCandidate> complete(String typed, String valuePrefix, boolean quote, boolean bareInExportFolder) {
		List<CompletionCandidate> result = new ArrayList<>();
		String text = typed == null ? "" : typed;
		int cut = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
		String dirPart = cut >= 0 ? text.substring(0, cut + 1) : "";
		String namePrefix = text.substring(cut + 1);
		char separator = dirPart.indexOf('\\') >= 0 ? '\\' : dirPart.indexOf('/') >= 0 ? '/' : File.separatorChar;
		if (dirPart.isEmpty() && text.matches("[A-Za-z]:")) {
			dirPart = text + separator;
			namePrefix = "";
		}

		Path directory;
		boolean filesOnly = false;
		try {
			if (dirPart.isEmpty()) {
				String folder = bareInExportFolder ? StringUtils.trimToNull(exportFolder == null ? null : exportFolder.get()) : null;
				directory = folder != null ? Paths.get(folder) : workingDirectory.get();
				filesOnly = folder != null;
			} else {
				Path given = Paths.get(dirPart);
				directory = given.isAbsolute() ? given : workingDirectory.get().resolve(given);
			}
		} catch (InvalidPathException e) {
			return result;
		}
		if (!Files.isDirectory(directory)) {
			return result;
		}

		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
			for (Path entry : entries) {
				String name = entry.getFileName() == null ? null : entry.getFileName().toString();
				if (name == null || !EntityCompletionService.matches(name, namePrefix)) {
					continue;
				}
				boolean isDirectory = Files.isDirectory(entry);
				if (isDirectory && filesOnly) {
					continue;
				}
				String path = dirPart + name + (isDirectory ? String.valueOf(separator) : "");
				String value = quote && path.chars().anyMatch(Character::isWhitespace) ? "\"" + path + (isDirectory ? "" : "\"") : path;
				result.add(new CompletionCandidate(valuePrefix + value, name + (isDirectory ? String.valueOf(separator) : ""), isDirectory ? "directory" : "file",
						isDirectory ? CompletionCandidateType.DIRECTORY : CompletionCandidateType.FILE));
			}
		} catch (IOException | RuntimeException e) {
			return new ArrayList<>();
		}
		result.sort((a, b) -> a.getDisplay().compareToIgnoreCase(b.getDisplay()));
		return result;
	}

	/**
	 * {@code true} for the {@code @} forms that name a file rather than a Scripts Library entry, with the
	 * same rule as {@code ScriptResolver.resolveForExecution}: an explicit relative path
	 * ({@link ScriptResolver#isExplicitRelative}) or an absolute one. A bare drive being typed ({@code C:})
	 * also counts on a filesystem that has drives.
	 */
	public static boolean isExplicitFilesystemReference(String reference) {
		if (reference == null || reference.isEmpty()) {
			return false;
		}
		if (ScriptResolver.isExplicitRelative(reference) || (File.separatorChar == '\\' && reference.matches("[A-Za-z]:"))) {
			return true;
		}
		try {
			return Paths.get(reference).isAbsolute();
		} catch (InvalidPathException e) {
			return false;
		}
	}
}
