package com.upandcoding.broadsql.controller.shell.commands.core.scripting;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;

/**
 * The catalog of the experimental {@code JS} command family ({@code JS RUN/LIST/FIND}), rooted at
 * {@code JsScripts} in {@code BroadSQL.ini}. SPRINT 1909S: this is the legacy {@code FileCatalog}
 * behaviour, kept exclusively for the isolated JavaScript subsystem so that {@code JS} commands behave
 * exactly as before (alias, unique-basename and {@code .sql}-appending lookup, metadata header
 * stripping). It is NOT the Scripts Library: BroadSQL Scripts are resolved only by
 * {@code ScriptResolver}, and nothing outside the {@code JS} commands may use this class.
 *
 * <p>Entries are indexed by their path relative to the root. A file's leading
 * {@code -- @key: value} comment lines are parsed once, at construction time.
 */
public class JsScriptCatalog {

	private static final String ARCHIVES_FOLDER = "archives";

	private String path = "";
	private final Map<String, String> names = new LinkedHashMap<>(); // relative path (forward slashes) -> absolute path
	private final Map<String, EntryMetadata> metadata = new LinkedHashMap<>(); // relative path -> parsed metadata
	private final Map<String, String> aliasIndex = new LinkedHashMap<>(); // lower-cased alias -> relative path, first wins

	/** @param invalidPathMessage message used if {@code path} is blank or doesn't exist - lets the scripts catalog report its own wording. */
	public JsScriptCatalog(String path, String invalidPathMessage) throws BroadSQLException {
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

		File dir = new File(path);
		if (!dir.exists()) {
			return;
		}
		Path root = dir.toPath();
		List<File> files = (List<File>) FileUtils.listFiles(dir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
		for (File file : files) {
			String relative = root.relativize(file.toPath()).toString().replace('\\', '/');
			if (isUnderArchives(relative)) {
				// archives/ is reserved: never listed
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
}
