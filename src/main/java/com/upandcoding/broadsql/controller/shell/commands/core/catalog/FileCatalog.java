package com.upandcoding.broadsql.controller.shell.commands.core.catalog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * A metadata-aware, alias-resolving, archive-capable folder of named text files - the shared engine
 * behind both the SQL library ({@code LIB *} commands, rooted at {@code SqlLib} in
 * {@code BroadSQL.ini}) and the scripts catalog ({@code SCRIPT *} commands, rooted at {@code Scripts}).
 * See {@code docs/SQL_LIBRARY_AND_SCRIPTS.md} for the full design this implements.
 *
 * <p>Entries are indexed by their path <b>relative to the root, not their bare file name</b> - two
 * files with the same name in different subfolders no longer collide (a latent bug in the previous
 * {@code Library} class this replaces). A file's leading {@code -- @key: value} comment lines are
 * parsed eagerly, once, at construction time - safe because every caller constructs a fresh instance
 * per command execution rather than sharing/caching one, so there is no concurrent mutation or
 * staleness to guard against.
 *
 * <p>Soft-deleted entries live under the reserved {@code archives/} top-level subfolder of the root -
 * excluded from every normal listing/lookup, restorable via {@link #restore(String)}/{@link #undo()}.
 */
public class FileCatalog {

	private static final String ARCHIVES_FOLDER = "archives";
	private static final DateTimeFormatter ARCHIVE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final Pattern ARCHIVE_SUFFIX = Pattern.compile("^(.*)\\.(\\d{8}-\\d{6})$");
	private static final Pattern PARAM_PATTERN = Pattern.compile("%([1-9])");

	private String path = "";
	private final Map<String, String> names = new LinkedHashMap<>(); // relative path (forward slashes) -> absolute path
	private final Map<String, EntryMetadata> metadata = new LinkedHashMap<>(); // relative path -> parsed metadata
	private final Map<String, String> aliasIndex = new LinkedHashMap<>(); // lower-cased alias -> relative path, first wins
	private final List<ArchivedEntry> archived = new ArrayList<>();

	public FileCatalog(String path) throws BroadSQLException {
		this(path, BroadSQLErrorMessages.ERR_LIB_01);
	}

	/** @param invalidPathMessage message used if {@code path} is blank or doesn't exist - lets the scripts catalog report its own wording (see {@link BroadSQLErrorMessages#ERR_SCRIPTS_01}) instead of the library's. */
	public FileCatalog(String path, String invalidPathMessage) throws BroadSQLException {
		assignPath(path, invalidPathMessage);
	}

	private void assignPath(String path, String invalidPathMessage) throws BroadSQLException {
		if (StringUtils.isBlank(path)) {
			throw new BroadSQLException(invalidPathMessage);
		}
		File filePath = new File(path);
		if (!filePath.exists()) {
			// relative path
			path = SpringPropertiesConfig.getCurrentPath() + SpringPropertiesConfig.getFileSep() + path;
			filePath = new File(path);
			if (!filePath.exists()) {
				throw new BroadSQLException(invalidPathMessage);
			}
		}
		this.path = path;
		refresh();
	}

	private void refresh() {
		names.clear();
		metadata.clear();
		aliasIndex.clear();
		archived.clear();

		File dir = new File(path);
		if (!dir.exists()) {
			return;
		}
		Path root = dir.toPath();
		List<File> files = (List<File>) FileUtils.listFiles(dir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
		for (File file : files) {
			String relative = root.relativize(file.toPath()).toString().replace('\\', '/');
			if (isUnderArchives(relative)) {
				registerArchived(relative, file);
			} else {
				names.put(relative, file.getAbsolutePath());
				EntryMetadata entryMetadata = EntryMetadata.parse(readSafely(file));
				metadata.put(relative, entryMetadata);
				for (String alias : entryMetadata.getAliases()) {
					aliasIndex.putIfAbsent(alias.toLowerCase(), relative);
				}
			}
		}
	}

	private boolean isUnderArchives(String relativePath) {
		int slash = relativePath.indexOf('/');
		String first = slash >= 0 ? relativePath.substring(0, slash) : relativePath;
		return first.equalsIgnoreCase(ARCHIVES_FOLDER);
	}

	private void registerArchived(String relativePath, File file) {
		String underArchives = relativePath.substring(relativePath.indexOf('/') + 1); // strip "archives/"
		Matcher matcher = ARCHIVE_SUFFIX.matcher(underArchives);
		if (matcher.matches()) {
			archived.add(new ArchivedEntry(matcher.group(1), file.getAbsolutePath(), matcher.group(2)));
		}
		// A file under archives/ that doesn't match the expected "<original>.<timestamp>" shape (e.g.
		// something dropped in there by hand) simply isn't listed as a restorable entry - it stays
		// physically excluded from the working catalog either way.
	}

	private static String readSafely(File file) {
		try {
			return Files.readString(file.toPath(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "";
		}
	}

	public String getPath() {
		return path;
	}

	/** Relative-path keys of every active (non-archived) entry. */
	public Set<String> getList() {
		return names.keySet();
	}

	public Map<String, String> getNames() {
		return names;
	}

	public EntryMetadata getEntryMetadata(String relativeKey) {
		EntryMetadata found = metadata.get(relativeKey);
		return found != null ? found : EntryMetadata.parse(null);
	}

	/** 0 if the entry doesn't exist. */
	public long getLastModifiedMillis(String relativeKey) {
		String absolute = names.get(relativeKey);
		return absolute != null ? new File(absolute).lastModified() : 0L;
	}

	/**
	 * Resolves {@code nameOrAlias} against this catalog: an exact relative-path match first
	 * (case-insensitive), then a declared {@code @alias}, then a unique file-name match regardless of
	 * subfolder (ambiguous or absent basename matches resolve to nothing, same as no match at all). If
	 * none of those match and {@code nameOrAlias} has no {@code .sql} extension, retries the same three
	 * steps with {@code .sql} appended - the pre-existing "extension optional" convenience. Returns the
	 * canonical relative-path key, or {@code null} if nothing matches.
	 */
	public String resolve(String nameOrAlias) {
		if (StringUtils.isBlank(nameOrAlias)) {
			return null;
		}
		String direct = resolveExact(nameOrAlias);
		if (direct != null) {
			return direct;
		}
		if (!nameOrAlias.toLowerCase().endsWith(".sql")) {
			return resolveExact(nameOrAlias + ".sql");
		}
		return null;
	}

	private String resolveExact(String nameOrAlias) {
		for (String key : names.keySet()) {
			if (key.equalsIgnoreCase(nameOrAlias)) {
				return key;
			}
		}
		String aliasHit = aliasIndex.get(nameOrAlias.toLowerCase());
		if (aliasHit != null) {
			return aliasHit;
		}
		String matched = null;
		int matches = 0;
		for (String key : names.keySet()) {
			if (basename(key).equalsIgnoreCase(nameOrAlias)) {
				matches++;
				matched = key;
			}
		}
		return matches == 1 ? matched : null;
	}

	private static String basename(String relativePath) {
		int slash = relativePath.lastIndexOf('/');
		return slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
	}

	public boolean contains(String nameOrAlias) {
		return resolve(nameOrAlias) != null;
	}

	/** Full, unmodified file content (including the metadata header, if any). */
	public String getRawContent(String relativeKey) throws BroadSQLException {
		String absolute = names.get(relativeKey);
		if (absolute == null) {
			throw new BroadSQLException("Catalog does not contain this item");
		}
		try {
			return Files.readString(Paths.get(absolute), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new BroadSQLException(e);
		}
	}

	/**
	 * The entry's content with the metadata header stripped and every remaining line joined by a
	 * single space - the executable query body {@code LIB RUN} sends to the database, same shape as
	 * the pre-existing behavior this replaces, just with the metadata header no longer corrupting it
	 * (see {@link EntryMetadata#stripHeader(String)}).
	 */
	public String getQueryBody(String relativeKey) throws BroadSQLException {
		String body = EntryMetadata.stripHeader(getRawContent(relativeKey));
		if (body == null) {
			return null;
		}
		StringBuilder joined = new StringBuilder();
		for (String line : body.split("\n", -1)) {
			joined.append(line).append(" ");
		}
		return joined.toString();
	}

	/** Distinct {@code %1}..{@code %9} placeholders found in the entry's query body, sorted ascending. */
	public Set<Integer> getParamNumbers(String relativeKey) throws BroadSQLException {
		Set<Integer> result = new TreeSet<>();
		String body = getQueryBody(relativeKey);
		if (body != null) {
			Matcher matcher = PARAM_PATTERN.matcher(body);
			while (matcher.find()) {
				result.add(Integer.valueOf(matcher.group(1)));
			}
		}
		return result;
	}

	/** Creates the entry if absent, overwrites it otherwise. Used by {@code LIB EDIT}/{@code SCRIPT EDIT}. */
	public void write(String relativeKey, String content) throws BroadSQLException {
		try {
			Path target = Paths.get(path, relativeKey);
			if (target.getParent() != null) {
				Files.createDirectories(target.getParent());
			}
			Files.writeString(target, content, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new BroadSQLException(e);
		}
	}

	/**
	 * Moves the entry to {@code archives/<relativeKey>.<timestamp>} instead of deleting it. No console
	 * interaction - the interactive confirmation belongs entirely to the calling command, so this
	 * method is directly unit-testable.
	 */
	public void archive(String relativeKey) throws BroadSQLException {
		String absolute = names.get(relativeKey);
		if (absolute == null) {
			throw new BroadSQLException("Catalog does not contain this item");
		}
		String timestamp = LocalDateTime.now().format(ARCHIVE_TIMESTAMP);
		Path target = Paths.get(path, ARCHIVES_FOLDER, relativeKey + "." + timestamp);
		try {
			Files.createDirectories(target.getParent());
			Files.move(Paths.get(absolute), target);
		} catch (IOException e) {
			throw new BroadSQLException(e);
		}
	}

	/** Archived entries, newest first. */
	public List<ArchivedEntry> getArchivedEntries() {
		List<ArchivedEntry> sorted = new ArrayList<>(archived);
		sorted.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));
		return sorted;
	}

	/**
	 * Restores the most recently archived version of {@code nameOrAlias} (matched against the
	 * <em>original</em> relative path or its basename, same resolution style as {@link #resolve}) back
	 * to its original location. Refuses - rather than overwriting - if a live entry already occupies
	 * that path.
	 */
	public void restore(String nameOrAlias) throws BroadSQLException {
		ArchivedEntry match = null;
		for (ArchivedEntry entry : getArchivedEntries()) {
			String original = entry.getOriginalRelativePath();
			if (original.equalsIgnoreCase(nameOrAlias) || basename(original).equalsIgnoreCase(nameOrAlias)) {
				match = entry;
				break; // getArchivedEntries() is newest-first, so the first hit is the most recent
			}
		}
		if (match == null) {
			throw new BroadSQLException("Nothing archived matches '" + nameOrAlias + "'");
		}
		restoreEntry(match);
	}

	/** Restores whichever archived entry (across the whole catalog) was archived most recently. */
	public void undo() throws BroadSQLException {
		List<ArchivedEntry> entries = getArchivedEntries();
		if (entries.isEmpty()) {
			throw new BroadSQLException("Nothing to undo - the archive is empty");
		}
		restoreEntry(entries.get(0));
	}

	private void restoreEntry(ArchivedEntry entry) throws BroadSQLException {
		Path original = Paths.get(path, entry.getOriginalRelativePath());
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
	}

	/** One soft-deleted file: where it used to live, where it lives now, and when it was archived. */
	public static class ArchivedEntry {
		private final String originalRelativePath;
		private final String absolutePath;
		private final String timestamp;

		ArchivedEntry(String originalRelativePath, String absolutePath, String timestamp) {
			this.originalRelativePath = originalRelativePath;
			this.absolutePath = absolutePath;
			this.timestamp = timestamp;
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
	}
}
