package com.upandcoding.broadsql.controller.shell.scripts;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;

/**
 * SPRINT 1909S: the Scripts Library, BroadSQL's managed folder of reusable Scripts (default
 * {@code scripts/}, setting {@code ScriptsLibrary}). It is a location and a catalog, not a kind of
 * Script: a Script in the library is exactly the same thing as one stored anywhere else.
 *
 * <p>This class only <em>manages</em> Scripts whose paths are already known: listing (recursive), reading
 * metadata, writing, and the single archive/restore model behind {@code LIB DEL}, {@code LIB RESTORE},
 * {@code LIB UNDO} and the editor's Delete and Recently Deleted. It has deliberately <b>no name lookup</b>:
 * turning a reference into a path is {@link ScriptResolver}'s job alone (no alias, basename or extension
 * guessing exists anywhere in the library).
 *
 * <p>Scripts are indexed by their path relative to the root, with forward slashes ("key"). Only text
 * files are listed ({@link ScriptTextIO#isTextFile}); no extension is consulted. A file's leading
 * {@code -- @key: value} comment lines are parsed once, at construction time - every caller constructs a
 * fresh instance per operation. Soft-deleted Scripts live under the reserved top-level {@code archives/}
 * folder as {@code archives/<key>.<yyyyMMdd-HHmmss>[-n]}.
 */
public class ScriptsLibrary {

	private static final DateTimeFormatter ARCHIVE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final Pattern ARCHIVE_SUFFIX = Pattern.compile("^(.*)\\.(\\d{8}-\\d{6}(?:-\\d+)?)$");

	/**
	 * Notified after a Script is archived or restored so a revision history can keep its lineage with the
	 * file (SPRINT 1909S: history follows the archive; it never defines deletion). Failures must not
	 * propagate: history never blocks archive/restore.
	 */
	public interface HistoryLinkage {
		void archived(Path libraryRoot, String relativeKey, String archiveRelativeKey);

		void restored(Path libraryRoot, String archiveRelativeKey, String relativeKey);
	}

	private static volatile HistoryLinkage historyLinkage;

	/** Registers (or clears, with {@code null}) the history hook. */
	public static void setHistoryLinkage(HistoryLinkage linkage) {
		historyLinkage = linkage;
	}

	private static final java.util.concurrent.atomic.AtomicLong LAST_ARCHIVE_STAMP = new java.util.concurrent.atomic.AtomicLong();

	/** Strictly increasing millisecond stamp, so two archives made in the same millisecond still order correctly for LIB UNDO. */
	private static long nextArchiveStamp() {
		return LAST_ARCHIVE_STAMP.updateAndGet(last -> Math.max(System.currentTimeMillis(), last + 1));
	}

	private final Path root;
	private final Map<String, Path> entries = new LinkedHashMap<>(); // key -> absolute path
	private final Map<String, EntryMetadata> metadata = new LinkedHashMap<>();
	private final List<ArchivedEntry> archived = new ArrayList<>();

	/**
	 * @param configuredRoot the value of the {@code ScriptsLibrary} setting
	 * @throws BroadSQLException if the folder is not configured, does not exist, or is a file
	 */
	public ScriptsLibrary(String configuredRoot) throws BroadSQLException {
		Path located = locateRoot(configuredRoot);
		try {
			this.root = located.toRealPath();
		} catch (IOException e) {
			throw new BroadSQLException("Cannot access the Scripts Library folder " + located + ": " + e.getMessage());
		}
		refresh();
	}

	/**
	 * Locates the Scripts Library folder: the configured value as given, else relative to BroadSQL's
	 * install folder. Errors name the path(s) tried.
	 */
	public static Path locateRoot(String configuredRoot) throws BroadSQLException {
		if (StringUtils.isBlank(configuredRoot)) {
			throw new BroadSQLException("No Scripts Library folder is configured (setting ScriptsLibrary in the INI file)");
		}
		Path direct;
		try {
			direct = Paths.get(configuredRoot.trim());
		} catch (java.nio.file.InvalidPathException e) {
			throw new BroadSQLException("The ScriptsLibrary setting is not a valid path: '" + configuredRoot.trim() + "' (" + e.getReason() + ")");
		}
		Path candidate = direct;
		if (!Files.exists(candidate)) {
			if (direct.isAbsolute()) {
				throw new BroadSQLException("The Scripts Library folder does not exist: " + direct);
			}
			// a relative setting is also looked up under BroadSQL's install folder
			candidate = Paths.get(SpringPropertiesConfig.getCurrentPath()).resolve(direct);
			if (!Files.exists(candidate)) {
				throw new BroadSQLException("The Scripts Library folder does not exist: " + direct.toAbsolutePath() + " (also tried " + candidate + ")");
			}
		}
		if (!Files.isDirectory(candidate)) {
			throw new BroadSQLException("The Scripts Library setting points to a file, not a folder: " + candidate.toAbsolutePath());
		}
		return candidate;
	}

	/**
	 * The keys {@link #getList()} would hold for {@code configuredRoot}, without reading any Script's
	 * content or metadata: same folder lookup, same archive and text-file filtering, path order. For TAB
	 * completion, which runs on every key press and only needs names.
	 */
	public static List<String> listScriptKeys(String configuredRoot) throws BroadSQLException {
		Path root;
		try {
			root = locateRoot(configuredRoot).toRealPath();
		} catch (IOException e) {
			throw new BroadSQLException("Cannot access the Scripts Library folder: " + e.getMessage());
		}
		List<String> keys = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isRegularFile).sorted().forEach(file -> {
				String key = ScriptResolver.relativeKey(root, file);
				if (!isUnderArchives(key) && ScriptTextIO.isTextFile(file)) {
					keys.add(key);
				}
			});
		} catch (IOException e) {
			// An unreadable subtree simply yields fewer names, as in refresh().
		}
		return keys;
	}

	private void refresh() {
		entries.clear();
		metadata.clear();
		archived.clear();
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isRegularFile).sorted().forEach(this::register);
		} catch (IOException e) {
			// An unreadable subtree simply yields fewer entries; each operation reports its own errors.
		}
	}

	private void register(Path file) {
		String key = ScriptResolver.relativeKey(root, file);
		if (isUnderArchives(key)) {
			String underArchives = key.substring(key.indexOf('/') + 1);
			Matcher matcher = ARCHIVE_SUFFIX.matcher(underArchives);
			if (matcher.matches()) {
				archived.add(new ArchivedEntry(matcher.group(1), file.toString(), matcher.group(2), key, file.toFile().lastModified()));
			}
			return; // anything else under archives/ (dropped there by hand) is simply not listed
		}
		if (!ScriptTextIO.isTextFile(file)) {
			return;
		}
		entries.put(key, file);
		metadata.put(key, EntryMetadata.parse(readSafely(file)));
	}

	private static boolean isUnderArchives(String key) {
		int slash = key.indexOf('/');
		String first = slash >= 0 ? key.substring(0, slash) : key;
		return first.equalsIgnoreCase(ScriptResolver.ARCHIVES_FOLDER);
	}

	private static String readSafely(Path file) {
		try {
			return ScriptTextIO.read(file).getText();
		} catch (IOException e) {
			return "";
		}
	}

	/** The real path of the library root. */
	public Path getRoot() {
		return root;
	}

	/** The relative-path key of every active (non-archived) Script, in path order. */
	public Set<String> getList() {
		return entries.keySet();
	}

	/** key -> absolute path. */
	public Map<String, String> getNames() {
		Map<String, String> names = new LinkedHashMap<>();
		entries.forEach((key, path) -> names.put(key, path.toString()));
		return names;
	}

	/** The key of an already-resolved path inside this library. */
	public String keyOf(Path file) {
		return ScriptResolver.relativeKey(root, file);
	}

	/** {@code true} when {@code key} (exactly, as listed) is an active, listed Script. */
	public boolean hasKey(String key) {
		return entries.containsKey(key);
	}

	public EntryMetadata getEntryMetadata(String key) {
		EntryMetadata found = metadata.get(key);
		return found != null ? found : EntryMetadata.parse(null);
	}

	/** 0 if the entry doesn't exist. */
	public long getLastModifiedMillis(String key) {
		Path path = entries.get(key);
		return path != null ? new File(path.toString()).lastModified() : 0L;
	}

	/** Full, unmodified content (including the metadata header, if any). */
	public String getRawContent(String key) throws BroadSQLException {
		return read(key).getText();
	}

	/** Content plus the encoding state it was read in. */
	public ScriptTextIO.TextContent read(String key) throws BroadSQLException {
		Path path = entries.get(key);
		if (path == null) {
			throw new BroadSQLException("The Scripts Library does not contain '" + key + "'");
		}
		try {
			return ScriptTextIO.read(path);
		} catch (IOException e) {
			throw new BroadSQLException(e.getMessage());
		}
	}

	/** The Script's text without its metadata header. */
	public String getBody(String key) throws BroadSQLException {
		return EntryMetadata.stripHeader(getRawContent(key));
	}

	/**
	 * Writes {@code content} to {@code target}, a path previously obtained from
	 * {@link ScriptResolver#resolveInLibrary(String)} (creates parent folders). Refuses, writing nothing,
	 * when the text cannot be encoded in the content's charset.
	 */
	public void write(Path target, ScriptTextIO.TextContent content) throws BroadSQLException {
		requireInside(target);
		try {
			ScriptTextIO.write(target, content);
		} catch (IOException e) {
			throw new BroadSQLException(e.getMessage());
		}
	}

	private void requireInside(Path path) throws BroadSQLException {
		if (!path.toAbsolutePath().normalize().startsWith(root)) {
			throw new BroadSQLException("Refusing to write outside the Scripts Library: " + path);
		}
	}

	/**
	 * The single deletion model: moves the Script to {@code archives/<key>.<timestamp>} instead of
	 * deleting it. No console interaction - confirmation belongs to the caller.
	 *
	 * @return the archive file's key (relative to the root)
	 */
	public String archive(Path file) throws BroadSQLException {
		requireInside(file);
		String key = keyOf(file.toAbsolutePath().normalize());
		if (!Files.isRegularFile(file)) {
			throw new BroadSQLException("The Scripts Library does not contain '" + key + "'");
		}
		String timestamp = LocalDateTime.now().format(ARCHIVE_TIMESTAMP);
		Path target = root.resolve(ScriptResolver.ARCHIVES_FOLDER).resolve(key + "." + timestamp);
		int counter = 1;
		while (Files.exists(target)) { // two archives of one file in the same second
			target = root.resolve(ScriptResolver.ARCHIVES_FOLDER).resolve(key + "." + timestamp + "-" + counter++);
		}
		try {
			Files.createDirectories(target.getParent());
			Files.move(file, target);
			// The timestamp in the name has one-second resolution; stamping the archived file's modification
			// time gives LIB UNDO a millisecond tie-breaker between archives made within the same second.
			Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(nextArchiveStamp()));
		} catch (IOException e) {
			throw new BroadSQLException(e);
		}
		String archiveKey = ScriptResolver.relativeKey(root, target);
		notifyHistory(() -> {
			HistoryLinkage linkage = historyLinkage;
			if (linkage != null) {
				linkage.archived(root, key, archiveKey);
			}
		});
		return archiveKey;
	}

	/** Archived Scripts, newest first. */
	public List<ArchivedEntry> getArchivedEntries() {
		List<ArchivedEntry> sorted = new ArrayList<>(archived);
		sorted.sort((a, b) -> {
			int byTimestamp = b.getTimestamp().compareTo(a.getTimestamp());
			return byTimestamp != 0 ? byTimestamp : Long.compare(b.archivedAtMillis, a.archivedAtMillis);
		});
		return sorted;
	}

	/**
	 * Restores the most recently archived version of the Script whose ORIGINAL relative path is exactly
	 * {@code originalRelativePath} (no basename matching). Refuses, rather than overwriting, if a live
	 * Script already occupies that path.
	 */
	public void restore(String originalRelativePath) throws BroadSQLException {
		for (ArchivedEntry entry : getArchivedEntries()) {
			if (entry.getOriginalRelativePath().equals(originalRelativePath)) {
				restoreEntry(entry);
				return;
			}
		}
		throw new BroadSQLException("Nothing archived at '" + originalRelativePath + "'");
	}

	/** Restores whichever archived Script (across the whole library) was archived most recently. */
	public void undo() throws BroadSQLException {
		List<ArchivedEntry> entries = getArchivedEntries();
		if (entries.isEmpty()) {
			throw new BroadSQLException("Nothing to undo - the archive is empty");
		}
		restoreEntry(entries.get(0));
	}

	public void restoreEntry(ArchivedEntry entry) throws BroadSQLException {
		Path original = root.resolve(entry.getOriginalRelativePath()).normalize();
		requireInside(original);
		if (Files.exists(original)) {
			throw new BroadSQLException("'" + entry.getOriginalRelativePath() + "' already exists again - restore refused to avoid overwriting it");
		}
		try {
			if (original.getParent() != null) {
				Files.createDirectories(original.getParent());
			}
			Files.move(Paths.get(entry.getAbsolutePath()), original);
		} catch (IOException e) {
			throw new BroadSQLException(e);
		}
		notifyHistory(() -> {
			HistoryLinkage linkage = historyLinkage;
			if (linkage != null) {
				linkage.restored(root, entry.getArchiveRelativePath(), entry.getOriginalRelativePath());
			}
		});
	}

	private static void notifyHistory(Runnable action) {
		try {
			action.run();
		} catch (RuntimeException e) {
			// history never blocks archive/restore
		}
	}

	/** One soft-deleted Script: where it used to live, where it lives now, and when it was archived. */
	public static class ArchivedEntry {
		private final String originalRelativePath;
		private final String absolutePath;
		private final String timestamp;
		private final String archiveRelativePath;
		private final long archivedAtMillis;

		ArchivedEntry(String originalRelativePath, String absolutePath, String timestamp, String archiveRelativePath, long archivedAtMillis) {
			this.originalRelativePath = originalRelativePath;
			this.absolutePath = absolutePath;
			this.timestamp = timestamp;
			this.archiveRelativePath = archiveRelativePath;
			this.archivedAtMillis = archivedAtMillis;
		}

		public String getOriginalRelativePath() {
			return originalRelativePath;
		}

		public String getAbsolutePath() {
			return absolutePath;
		}

		public String getTimestamp() {
			return timestamp;
		}

		/** The archive file's key relative to the library root ({@code archives/...}). */
		public String getArchiveRelativePath() {
			return archiveRelativePath;
		}
	}
}
