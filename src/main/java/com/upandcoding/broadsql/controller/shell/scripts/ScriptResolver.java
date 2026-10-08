package com.upandcoding.broadsql.controller.shell.scripts;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * SPRINT 1909S: the ONLY component that turns a Script reference into a concrete path. {@code @},
 * {@code LIB RUN}, every other {@code LIB} command, {@code EDIT}, and every BroadSQL Editor operation
 * (open, create, rename, duplicate, delete, run) obtain their {@link Path} here, so one reference can
 * never mean two things. {@link ScriptsLibrary} deliberately has no name lookup of its own.
 *
 * <p>Resolution is deterministic and uses native {@code java.nio.file} semantics only: there is no
 * separator rewriting (on Unix a backslash is an ordinary filename character), no extension guessing
 * ({@code foo} is the file named {@code foo}), no alias and no basename search. A reference is one of:
 * <ol>
 * <li><b>explicit relative</b>: starts with {@code ./} (or {@code .\} where the native separator is a
 * backslash). Relative to the directory of the currently executing Script when executed from inside a
 * Script, otherwise to the process working directory ({@code user.dir}). Not confined.
 * <li><b>absolute</b> (native drive/UNC/root form): used as is. Not confined.
 * <li><b>library relative</b> (anything else): relative to the Scripts Library root, confined to it
 * ({@code ..} and symbolic-link escapes are rejected), and never under the reserved {@code archives}
 * folder.
 * </ol>
 * {@code LIB} commands accept only the third form.
 */
public final class ScriptResolver {

	/** Reserved top-level folder of the Scripts Library holding soft-deleted Scripts. */
	public static final String ARCHIVES_FOLDER = "archives";

	private final String configuredRoot;

	/** @param configuredRoot the {@code ScriptsLibrary} setting value (relative values are located as {@link ScriptsLibrary#locateRoot} explains) */
	public ScriptResolver(String configuredRoot) {
		this.configuredRoot = configuredRoot;
	}

	/** A resolved, existing, readable Script file. */
	public static final class ResolvedScript {
		private final Path path;
		private final boolean inLibrary;

		ResolvedScript(Path path, boolean inLibrary) {
			this.path = path;
			this.inLibrary = inLibrary;
		}

		public Path getPath() {
			return path;
		}

		/** {@code true} when the reference addressed the Scripts Library (as opposed to an explicit relative or absolute path). */
		public boolean isInLibrary() {
			return inLibrary;
		}
	}

	/**
	 * Resolves the reference of an {@code @} statement.
	 *
	 * @param context the execution context, used for an explicit relative reference (may be {@code null})
	 */
	public ResolvedScript resolveForExecution(String reference, ScriptContextStack context) throws BroadSQLException {
		String ref = requireReference(reference);
		Path path;
		boolean inLibrary = false;
		if (isExplicitRelative(ref)) {
			Path base = baseForExplicitRelative(context);
			path = base.resolve(parse(ref)).normalize();
		} else {
			Path parsed = parse(ref);
			if (parsed.isAbsolute()) {
				path = parsed.normalize();
			} else if (parsed.getRoot() != null) {
				throw new BroadSQLException("'" + ref + "' has a root but is not an absolute path (a drive-relative or root-relative form); use a full path such as "
						+ "C:\\dir\\file, /dir/file, or ./file");
			} else {
				path = resolveInLibrary(ref);
				inLibrary = true;
			}
		}
		requireReadableScript(path);
		return new ResolvedScript(path, inLibrary);
	}

	/** {@code true} when the configured Scripts Library folder exists (the editor creates a missing one lazily, on the first new Script). */
	public boolean libraryRootExists() {
		try {
			ScriptsLibrary.locateRoot(configuredRoot);
			return true;
		} catch (BroadSQLException e) {
			return false;
		}
	}

	/** {@code LIB RUN}: a Scripts Library reference that must name an existing, readable Script file. */
	public ResolvedScript resolveLibraryScript(String reference) throws BroadSQLException {
		Path path = resolveInLibrary(reference);
		requireReadableScript(path);
		return new ResolvedScript(path, true);
	}

	/**
	 * Resolves a Scripts Library reference (the only form {@code LIB} commands and the editor use):
	 * the confined path inside the library, which need not exist yet.
	 */
	public Path resolveInLibrary(String reference) throws BroadSQLException {
		String ref = requireReference(reference);
		if (isExplicitRelative(ref) || parseQuietly(ref) == null || parseQuietly(ref).isAbsolute() || parseQuietly(ref).getRoot() != null) {
			throw new BroadSQLException("'" + ref + "' is not a Scripts Library reference: LIB commands and the editor address the Scripts Library "
					+ "(e.g. maintenance/cleanup.bsql); use @<path> to run a script located elsewhere");
		}
		Path root = ScriptsLibrary.locateRoot(configuredRoot);
		Path realRoot = realOf(root);
		Path candidate = realRoot.resolve(parse(ref)).normalize();
		if (!candidate.startsWith(realRoot) || candidate.equals(realRoot)) {
			throw new BroadSQLException("'" + ref + "' resolves outside the Scripts Library (" + realRoot + "); use @./" + ref
					+ " (or a full path) to run a script located elsewhere");
		}
		Path relative = realRoot.relativize(candidate);
		if (relative.getName(0).toString().equalsIgnoreCase(ARCHIVES_FOLDER)) {
			throw new BroadSQLException("'" + ARCHIVES_FOLDER + "' is reserved for the Scripts Library's archive of deleted scripts (see LIB RESTORE)");
		}
		if (Files.exists(candidate)) {
			Path real = realOf(candidate);
			if (!real.startsWith(realRoot)) {
				throw new BroadSQLException("'" + ref + "' resolves outside the Scripts Library through a symbolic link (" + real + ")");
			}
			return real; // the filesystem's own spelling, so it matches the library's listing keys
		}
		return candidate;
	}

	/** The command that runs a Scripts Library Script at the prompt: {@code @} glued to the reference. */
	public static final String RUN_KEYWORD = "@";

	/**
	 * The command line that runs the Scripts Library Script {@code libraryKey} ({@link #relativeKey} form, forward
	 * slashes), as a user would type it at the prompt: {@code @reports/QR13.sql;}, the reference quoted when it
	 * contains whitespace ({@code @"my reports/QR 13.sql";}, {@link CommandUtils#quoteArgumentIfNeeded}), ended with
	 * the {@code ;} that makes the prompt execute it. A plain library reference is what {@link #resolveForExecution}
	 * resolves in the library, so the line runs this very Script whatever the working directory.
	 *
	 * @throws BroadSQLException if the key cannot be written as one argument (it contains {@code "}, which the
	 *                           argument parser has no escape for)
	 */
	public static String libraryInvocation(String libraryKey) throws BroadSQLException {
		String key = requireReference(libraryKey);
		if (key.indexOf('"') >= 0) {
			throw new BroadSQLException("'" + key + "' contains a double quote and cannot be written as a command argument");
		}
		return RUN_KEYWORD + CommandUtils.quoteArgumentIfNeeded(key) + ";";
	}

	/** The key {@link ScriptsLibrary} lists a Script under: its path relative to the library root, always with forward slashes. */
	public static String relativeKey(Path root, Path file) {
		String relative = root.relativize(file).toString();
		if (File.separatorChar == '\\') {
			relative = relative.replace('\\', '/');
		}
		return relative;
	}

	private static String requireReference(String reference) throws BroadSQLException {
		if (StringUtils.isBlank(reference)) {
			throw new BroadSQLException("You must provide a script name or path");
		}
		return reference.trim();
	}

	public static boolean isExplicitRelative(String ref) {
		return ref.startsWith("./") || (File.separatorChar == '\\' && ref.startsWith(".\\"));
	}

	private static Path baseForExplicitRelative(ScriptContextStack context) {
		if (context != null && context.isInsideScript()) {
			return context.current().getParentDir();
		}
		return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
	}

	private static Path parse(String ref) throws BroadSQLException {
		try {
			return Paths.get(ref);
		} catch (InvalidPathException e) {
			throw new BroadSQLException("'" + ref + "' is not a valid path: " + e.getReason());
		}
	}

	private static Path parseQuietly(String ref) {
		try {
			return Paths.get(ref);
		} catch (InvalidPathException e) {
			return null;
		}
	}

	private static Path realOf(Path path) throws BroadSQLException {
		try {
			return path.toRealPath();
		} catch (IOException e) {
			throw new BroadSQLException("Cannot resolve '" + path + "': " + e.getMessage());
		}
	}

	private static void requireReadableScript(Path path) throws BroadSQLException {
		if (!Files.exists(path)) {
			throw new BroadSQLException("Script not found: " + path);
		}
		if (Files.isDirectory(path)) {
			throw new BroadSQLException("'" + path + "' is a directory, not a script");
		}
		if (!Files.isReadable(path)) {
			throw new BroadSQLException("Script is not readable: " + path);
		}
	}
}
