package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptResolver;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptTextIO;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;

/**
 * The BroadSQL Editor's service layer (SPRINT 0917-01, reshaped by SPRINT 1909S): the one place that turns
 * Scripts Library operations ({@link ScriptsLibrary}, path resolution by {@link ScriptResolver}, text
 * reading and writing by {@link ScriptTextIO}) and revision history ({@link RevisionVault}) into the
 * {@link ScriptAsset} domain model the Swing UI renders. Every operation on a Script goes through the
 * same services {@code LIB} and {@code @} use: this class has no path or deletion rules of its own.
 *
 * <p>Plain Java, no Swing dependency - fully headlessly testable.
 */
public final class ScriptLibraryService {

	private static final Logger log = LoggerFactory.getLogger(ScriptLibraryService.class);

	/** The extension given to a new Script whose name has none. */
	public static final String DEFAULT_EXTENSION = ".bsql";

	private final ConsoleSettings consoleSettings;
	private final RevisionVault revisionVault;
	/** How Scripts and folders are moved on disk: {@link Files#move}; a test substitutes a failing mover to check that a failed move changes nothing. */
	private PathMover pathMover = (source, target) -> Files.move(source, target);

	/** Moves one file or folder on disk (see {@link #pathMover}). */
	@FunctionalInterface
	interface PathMover {
		void move(Path source, Path target) throws IOException;
	}

	/** Test seam: replaces the on-disk mover. */
	void setPathMoverForTesting(PathMover pathMover) {
		this.pathMover = pathMover;
	}

	public ScriptLibraryService(ConsoleSettings consoleSettings) {
		this.consoleSettings = consoleSettings;
		this.revisionVault = new RevisionVault(consoleSettings.resolveScriptHistoryVaultPath());
		ScriptsLibrary.setHistoryLinkage(revisionVault);
	}

	/** Direct access for callers (History/Compare/Restore UI) that need vault operations this service doesn't itself wrap. */
	public RevisionVault revisionVault() {
		return revisionVault;
	}

	private ScriptsLibrary library() throws BroadSQLException {
		return new ScriptsLibrary(consoleSettings.getScriptsLibraryPath());
	}

	private ScriptResolver resolver() {
		return new ScriptResolver(consoleSettings.getScriptsLibraryPath());
	}

	/** The content every brand-new Script starts with: a header declaring the default status ({@link EntryMetadata#DEFAULT_NEW_STATUS}). */
	public static String newAssetSeedContent() {
		return "-- @status: " + EntryMetadata.DEFAULT_NEW_STATUS + "\n";
	}

	/**
	 * Creating the first Script in a Scripts Library whose configured folder does not exist yet (a fresh
	 * or upgraded installation) creates that configured folder, resolved with exactly the rule
	 * {@link ScriptsLibrary#locateRoot} uses to find it (as given if it exists, otherwise relative to the
	 * install folder). A blank setting, or a setting that names an existing non-folder, stays an error.
	 */
	private void ensureLibraryRootExists() throws BroadSQLException {
		String configured = consoleSettings.getScriptsLibraryPath();
		if (StringUtils.isBlank(configured)) {
			throw new BroadSQLException("No Scripts Library folder is configured (setting ScriptsLibrary in the INI file)");
		}
		try {
			Path root = Path.of(configured.trim());
			if (!Files.exists(root)) {
				root = Path.of(com.upandcoding.broadsql.controller.config.SpringPropertiesConfig.getCurrentPath()).resolve(root);
			}
			if (Files.exists(root)) {
				return; // ScriptsLibrary itself reports it if it is not usable
			}
			Files.createDirectories(root);
			log.info("Created the missing Scripts Library folder '{}'", root);
		} catch (IOException | java.nio.file.InvalidPathException e) {
			throw new BroadSQLException("The Scripts Library folder could not be created: " + e.getLocalizedMessage(), e);
		}
	}

	/**
	 * A new Script's name: kept exactly as typed, except that a final path segment with no extension gets
	 * {@value #DEFAULT_EXTENSION} (the default for a BroadSQL Script, which may hold SQL, BroadSQL
	 * commands, or both). Any other extension is accepted as typed: extensions carry no meaning.
	 */
	static String normalizeNewName(String rawName) throws BroadSQLException {
		String trimmed = rawName == null ? "" : rawName.trim();
		if (trimmed.isEmpty()) {
			throw new BroadSQLException("Name must not be blank");
		}
		Path fileName;
		try {
			fileName = Paths.get(trimmed).getFileName();
		} catch (java.nio.file.InvalidPathException e) {
			throw new BroadSQLException("'" + trimmed + "' is not a valid name: " + e.getReason());
		}
		if (fileName == null) {
			throw new BroadSQLException("Name must not be blank");
		}
		return fileName.toString().indexOf('.') < 0 ? trimmed + DEFAULT_EXTENSION : trimmed;
	}

	/** Every Script currently in the library (subfolders included), sorted by path. Does not record revisions merely from being listed. */
	public List<ScriptAsset> listAssets() throws BroadSQLException {
		ScriptsLibrary library = library();
		String rootIdentity = RevisionVault.rootIdentityOf(library.getRoot().toString());
		List<ScriptAsset> assets = new ArrayList<>();
		for (String relativePath : library.getList()) {
			String content = library.getRawContent(relativePath);
			String assetId = revisionVault.resolveOrRegisterAssetId(rootIdentity, relativePath);
			assets.add(new ScriptAsset(assetId, relativePath, ScriptMetadataHeader.parse(content), content, library.getLastModifiedMillis(relativePath)));
		}
		assets.sort(Comparator.comparing(ScriptAsset::relativePath, String.CASE_INSENSITIVE_ORDER));
		return assets;
	}

	/** Every folder under the library root (relative, forward slashes), the reserved {@code archives} folder excluded - the tree needs empty ones too. */
	public List<String> listFolders() throws BroadSQLException {
		ScriptsLibrary library = library();
		Path root = library.getRoot();
		List<String> folders = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isDirectory).filter(p -> !p.equals(root)).forEach(p -> {
				String key = ScriptResolver.relativeKey(root, p);
				if (!key.equalsIgnoreCase(ScriptResolver.ARCHIVES_FOLDER) && !key.toLowerCase().startsWith(ScriptResolver.ARCHIVES_FOLDER + "/")) {
					folders.add(key);
				}
			});
		} catch (IOException e) {
			throw new BroadSQLException("Could not list the Scripts Library folders: " + e.getLocalizedMessage(), e);
		}
		folders.sort(String.CASE_INSENSITIVE_ORDER);
		return folders;
	}

	/**
	 * Loads {@code relativePath} for editing. Never modifies the file. Registers the Script's stable
	 * identity if this is the first time BroadSQL has seen it, and records a baseline/catch-up revision
	 * reflecting the currently-observed content (a no-op if it already matches the vault's latest known
	 * state) - spec sections 6.2, 29, 39.
	 */
	public ScriptAsset open(String relativePath) throws BroadSQLException {
		ScriptsLibrary library = library();
		String key = library.keyOf(resolver().resolveInLibrary(relativePath));
		if (!library.hasKey(key)) {
			throw new BroadSQLException("'" + key + "' does not exist in the Scripts Library");
		}
		String rootIdentity = RevisionVault.rootIdentityOf(library.getRoot().toString());
		String content = library.getRawContent(key);
		String assetId = revisionVault.resolveOrRegisterAssetId(rootIdentity, key);
		revisionVault.recordRevisionIfChanged(assetId, content, key, null);
		return new ScriptAsset(assetId, key, ScriptMetadataHeader.parse(content), content, library.getLastModifiedMillis(key));
	}

	/**
	 * Persists {@code newContent} to the working file, then records a revision if it actually changed
	 * anything. Implements the Save/history partial-failure semantics: if the working-file write itself
	 * fails, this throws (the caller's tab must stay dirty - no revision is ever recorded in that case);
	 * if the write succeeds but the history write fails, this returns normally with
	 * {@link SaveOutcome.Status#SAVED_HISTORY_FAILED} rather than throwing.
	 */
	public SaveOutcome save(String assetId, String relativePath, String newContent, String comment) throws BroadSQLException {
		return save(assetId, relativePath, newContent, comment, MetadataIntegrityContext.unchecked());
	}

	/**
	 * {@link #save(String, String, String, String)} with Save-time metadata integrity enforced first
	 * ({@link MetadataIntegrity}): a {@link MetadataIntegrityException} is thrown, and nothing is written
	 * and no revision recorded, when the Script's metadata is invalid. The body is never inspected, so
	 * unfinished drafts still save. The file is written back in the charset/BOM state it was read in
	 * ({@link ScriptTextIO}); if the text cannot be encoded in it, the save is refused and nothing is written.
	 */
	public SaveOutcome save(String assetId, String relativePath, String newContent, String comment, MetadataIntegrityContext integrityContext)
			throws BroadSQLException {
		ScriptsLibrary library = library();
		Path target = resolver().resolveInLibrary(relativePath);
		String key = library.keyOf(target);
		List<MetadataIssue> issues = MetadataIntegrity.check(newContent, integrityContext);
		if (!issues.isEmpty()) {
			throw new MetadataIntegrityException(issues);
		}
		library.write(target, encodingFor(target, newContent));
		try {
			RevisionOutcome outcome = revisionVault.recordRevisionIfChanged(assetId, newContent, key, comment);
			return SaveOutcome.saved(outcome.revisionCreated());
		} catch (BroadSQLException historyFailure) {
			log.warn("Working file '{}' saved but the script history vault could not be updated: {}", key, historyFailure.getLocalizedMessage());
			return SaveOutcome.savedHistoryFailed(historyFailure.getLocalizedMessage());
		}
	}

	/** The encoding state to write {@code text} in: the existing file's own, or UTF-8 without BOM for a new file. */
	private static ScriptTextIO.TextContent encodingFor(Path target, String text) {
		if (Files.isRegularFile(target)) {
			try {
				return ScriptTextIO.read(target).withText(text);
			} catch (IOException e) {
				// unreadable as text: fall through and write a fresh UTF-8 file
			}
		}
		return ScriptTextIO.TextContent.newFile(text);
	}

	/**
	 * Creates a brand-new Script with {@code initialContent} and opens it (establishing its v1 baseline).
	 * Fails if the path already exists. A final name segment without an extension gets
	 * {@value #DEFAULT_EXTENSION}. Subfolders are created as needed.
	 */
	public ScriptAsset create(String relativePath, String initialContent) throws BroadSQLException {
		ensureLibraryRootExists();
		ScriptsLibrary library = library();
		Path target = resolver().resolveInLibrary(normalizeNewName(relativePath));
		String key = library.keyOf(target);
		if (Files.exists(target)) {
			throw new BroadSQLException("'" + key + "' already exists");
		}
		library.write(target, ScriptTextIO.TextContent.newFile(initialContent));
		return open(key);
	}

	/**
	 * The first Save of a new, still unnamed Script in the Editor: {@link #create} with {@code content}, after the
	 * same metadata integrity check every Save performs ({@link MetadataIntegrity}). A {@link MetadataIntegrityException}
	 * means nothing was written.
	 */
	public ScriptAsset create(String relativePath, String content, MetadataIntegrityContext integrityContext) throws BroadSQLException {
		List<MetadataIssue> issues = MetadataIntegrity.check(content, integrityContext);
		if (!issues.isEmpty()) {
			throw new MetadataIntegrityException(issues);
		}
		return create(relativePath, content);
	}

	/** Creates an (empty) folder inside the library. */
	public void createFolder(String relativePath) throws BroadSQLException {
		ensureLibraryRootExists();
		ScriptsLibrary library = library();
		Path target = resolver().resolveInLibrary(relativePath);
		if (Files.exists(target)) {
			throw new BroadSQLException("'" + library.keyOf(target) + "' already exists");
		}
		try {
			Files.createDirectories(target);
		} catch (IOException e) {
			throw new BroadSQLException("Could not create the folder '" + relativePath + "': " + e.getLocalizedMessage(), e);
		}
	}

	/**
	 * Renames/moves the file and records the rename as a revision - the asset id and full history are
	 * preserved (see {@link RevisionVault#recordRenameRevision}); this never creates a new, unrelated asset.
	 */
	public ScriptAsset rename(String assetId, String currentRelativePath, String newRelativePath, String comment) throws BroadSQLException {
		ScriptsLibrary library = library();
		ScriptResolver resolver = resolver();
		Path source = resolver.resolveInLibrary(currentRelativePath);
		Path target = resolver.resolveInLibrary(normalizeNewName(newRelativePath));
		String currentKey = library.keyOf(source);
		String newKey = library.keyOf(target);
		if (newKey.equalsIgnoreCase(currentKey)) {
			throw new BroadSQLException("The new name is the same as the current name");
		}
		return relocateScript(library, assetId, source, target, comment, "rename");
	}

	/** One item of a move: a Script ({@code folder == false}) or a folder, by its library path. */
	public record MoveRequest(String path, boolean folder) {
	}

	/**
	 * The result of a move: the new library path of every moved item (in request order), every Script moved
	 * (directly or inside a moved folder) at its new path with its unchanged asset id, and {@code null} or a
	 * warning when some revision histories could not be updated (the files did move).
	 */
	public record MoveOutcome(List<String> newKeys, List<ScriptAsset> movedScripts, String historyWarning) {
	}

	/**
	 * Moves the Script {@code relativePath} into the folder {@code destinationFolderOrNull} ({@code null}: the
	 * library root), keeping its file name exactly (no extension is added) and, like {@link #rename}, its asset id
	 * and full revision history. Refused, with nothing changed, when the destination is not an existing folder,
	 * when the Script is already there, or when the destination already holds an item with that name: nothing is
	 * ever overwritten. A failed filesystem move leaves the Script where it was. See {@link #moveItems}.
	 */
	public ScriptAsset moveAsset(String relativePath, String destinationFolderOrNull) throws BroadSQLException {
		return moveItems(List.of(new MoveRequest(relativePath, false)), destinationFolderOrNull).movedScripts().get(0);
	}

	/**
	 * Moves the folder {@code folderPath}, with everything in it, into the folder {@code destinationFolderOrNull}
	 * ({@code null}: the library root), keeping its name. Refused, with nothing changed, when the destination is
	 * the folder itself or one of its subfolders, is not an existing folder, already holds an item with that name,
	 * or is where the folder already is. Every Script inside keeps its asset id and revision history.
	 */
	public FolderMoveOutcome moveFolder(String folderPath, String destinationFolderOrNull) throws BroadSQLException {
		MoveOutcome outcome = moveItems(List.of(new MoveRequest(folderPath, true)), destinationFolderOrNull);
		return new FolderMoveOutcome(outcome.newKeys().get(0), outcome.movedScripts(), outcome.historyWarning());
	}

	/**
	 * Renames/moves a folder, confined to the library. Every Script it contains keeps its asset id and revision
	 * history at its new path (see {@link #executeMoves}).
	 */
	public FolderMoveOutcome renameFolder(String currentRelativePath, String newRelativePath) throws BroadSQLException {
		ScriptsLibrary library = library();
		ScriptResolver resolver = resolver();
		Path source = resolver.resolveInLibrary(currentRelativePath);
		Path target = resolver.resolveInLibrary(newRelativePath);
		if (!Files.isDirectory(source)) {
			throw new BroadSQLException("'" + library.keyOf(source) + "' is not a folder");
		}
		String sourceKey = library.keyOf(source);
		String targetKey = library.keyOf(target);
		if (sameKey(targetKey, sourceKey) || isInside(targetKey, sourceKey)) {
			throw new BroadSQLException("A folder cannot be moved into itself or one of its own subfolders");
		}
		if (Files.exists(target)) {
			throw new BroadSQLException("'" + targetKey + "' already exists");
		}
		MoveOutcome outcome = executeMoves(library, List.of(plan(library, new MoveRequest(sourceKey, true), source, target, "rename the folder")));
		return new FolderMoveOutcome(outcome.newKeys().get(0), outcome.movedScripts(), outcome.historyWarning());
	}

	/**
	 * Moves several Scripts and folders into the folder {@code destinationFolderOrNull} ({@code null}: the library
	 * root) as one operation: the Editor's multiple-selection drag and drop.
	 * <ol>
	 * <li><b>Normalized</b>: an item inside a selected folder is dropped (it moves with that folder, never a second
	 * time), as are duplicates; an item already in the destination needs no move and is skipped.</li>
	 * <li><b>Validated entirely before anything moves</b>: the destination is an existing library folder; no folder
	 * goes into itself or one of its subfolders; no item lands on an existing name; no two items land on the same
	 * name; every path stays inside the Scripts Library. Any problem refuses the whole move, with nothing
	 * changed.</li>
	 * <li><b>Moved</b> on disk one item at a time. If a filesystem move fails, the items already moved are moved
	 * back, so a failed batch leaves the library as it was; if moving one back fails too, the exception says
	 * exactly which items are where.</li>
	 * <li>Only then <b>histories</b> are updated: every moved Script keeps its asset id, with a revision at its new
	 * path. A history failure never undoes the move: it is reported in {@link MoveOutcome#historyWarning()}.</li>
	 * </ol>
	 */
	public MoveOutcome moveItems(List<MoveRequest> items, String destinationFolderOrNull) throws BroadSQLException {
		ScriptsLibrary library = library();
		ScriptResolver resolver = resolver();
		String destinationKey = requireDestinationFolder(library, resolver, destinationFolderOrNull);

		// Resolve and normalize: canonical keys, no duplicates, nothing inside a selected folder.
		List<MoveRequest> resolved = new ArrayList<>();
		for (MoveRequest item : items) {
			Path source = resolver.resolveInLibrary(item.path());
			String key = library.keyOf(source);
			if (item.folder() ? !Files.isDirectory(source) : !library.hasKey(key)) {
				throw new BroadSQLException("'" + key + "' does not exist in the Scripts Library" + (item.folder() ? " as a folder" : ""));
			}
			if (resolved.stream().noneMatch(r -> sameKey(r.path(), key))) {
				resolved.add(new MoveRequest(key, item.folder()));
			}
		}
		List<MoveRequest> effective = new ArrayList<>();
		for (MoveRequest item : resolved) {
			boolean insideSelectedFolder = resolved.stream().anyMatch(other -> other.folder() && isInside(item.path(), other.path()));
			if (!insideSelectedFolder) {
				effective.add(item);
			}
		}

		// Validate everything before the first move.
		List<PlannedMove> plans = new ArrayList<>();
		List<String> alreadyThere = new ArrayList<>();
		for (MoveRequest item : effective) {
			String key = item.path();
			if (item.folder() && destinationKey != null && sameKey(destinationKey, key)) {
				throw new BroadSQLException("A folder cannot be moved into itself");
			}
			if (item.folder() && destinationKey != null && isInside(destinationKey, key)) {
				throw new BroadSQLException("The folder '" + key + "' cannot be moved into its own subfolder '" + destinationKey + "'");
			}
			if (sameKey(parentKeyOf(key), destinationKey)) {
				alreadyThere.add(key);
				continue;
			}
			Path source = resolver.resolveInLibrary(key);
			Path target = resolver.resolveInLibrary(childKey(destinationKey, source.getFileName().toString()));
			String targetKey = library.keyOf(target);
			if (Files.exists(target)) {
				throw new BroadSQLException("'" + targetKey + "' already exists");
			}
			for (PlannedMove other : plans) {
				if (sameKey(other.targetKey(), targetKey)) {
					throw new BroadSQLException("'" + other.sourceKey() + "' and '" + key + "' would both become '" + targetKey + "'");
				}
			}
			plans.add(plan(library, item, source, target, item.folder() ? "move the folder" : "move"));
		}
		if (plans.isEmpty()) {
			throw new BroadSQLException((alreadyThere.size() == 1 ? "'" + alreadyThere.get(0) + "' is" : "The selected items are") + " already in "
					+ folderLabel(destinationKey));
		}
		return executeMoves(library, plans);
	}

	/** One validated move: the item, its paths, and the asset id of every Script it carries (itself, or those inside the folder). */
	private record PlannedMove(MoveRequest item, Path source, Path target, String sourceKey, String targetKey, Map<String, String> assetIdsByOldKey,
			String verb) {
	}

	private PlannedMove plan(ScriptsLibrary library, MoveRequest item, Path source, Path target, String verb) throws BroadSQLException {
		String sourceKey = library.keyOf(source);
		String rootIdentity = RevisionVault.rootIdentityOf(library.getRoot().toString());
		Map<String, String> assetIds = new LinkedHashMap<>();
		if (item.folder()) {
			for (String scriptKey : library.getList()) {
				if (isInside(scriptKey, sourceKey)) {
					assetIds.put(scriptKey, revisionVault.resolveOrRegisterAssetId(rootIdentity, scriptKey));
				}
			}
		} else {
			assetIds.put(sourceKey, revisionVault.resolveOrRegisterAssetId(rootIdentity, sourceKey));
		}
		return new PlannedMove(item, source, target, sourceKey, library.keyOf(target), assetIds, verb);
	}

	/** Moves the planned items on disk (rolling back on a failure), then records every moved Script's new path in its history. */
	private MoveOutcome executeMoves(ScriptsLibrary library, List<PlannedMove> plans) throws BroadSQLException {
		List<PlannedMove> done = new ArrayList<>();
		for (PlannedMove plan : plans) {
			try {
				if (plan.target().getParent() != null) {
					Files.createDirectories(plan.target().getParent());
				}
				pathMover.move(plan.source(), plan.target());
				done.add(plan);
			} catch (IOException e) {
				throw new BroadSQLException(failureMessage(plan, e, rollBack(done)), e);
			}
		}
		ScriptsLibrary moved = library();
		List<ScriptAsset> movedScripts = new ArrayList<>();
		List<String> historyFailures = new ArrayList<>();
		for (PlannedMove plan : plans) {
			for (Map.Entry<String, String> entry : plan.assetIdsByOldKey().entrySet()) {
				String newKey = plan.targetKey() + entry.getKey().substring(plan.sourceKey().length());
				String content = moved.getRawContent(newKey);
				try {
					revisionVault.recordRevisionIfChanged(entry.getValue(), content, newKey, null);
				} catch (BroadSQLException historyFailure) {
					log.warn("'{}' moved to '{}' but its history could not be updated: {}", entry.getKey(), newKey, historyFailure.getLocalizedMessage());
					historyFailures.add(newKey);
				}
				movedScripts.add(new ScriptAsset(entry.getValue(), newKey, ScriptMetadataHeader.parse(content), content, moved.getLastModifiedMillis(newKey)));
			}
		}
		String warning = historyFailures.isEmpty() ? null
				: "Revision history could not be updated for " + String.join(", ", historyFailures) + "; it catches up the next time each is opened.";
		return new MoveOutcome(plans.stream().map(PlannedMove::targetKey).toList(), movedScripts, warning);
	}

	/** Moves the already-moved items back, last first. @return the items that could not be moved back (empty: the library is as it was). */
	private List<PlannedMove> rollBack(List<PlannedMove> done) {
		List<PlannedMove> stuck = new ArrayList<>();
		for (int i = done.size() - 1; i >= 0; i--) {
			PlannedMove plan = done.get(i);
			try {
				pathMover.move(plan.target(), plan.source());
			} catch (IOException e) {
				log.error("Could not move '{}' back to '{}' after a failed move: {}", plan.targetKey(), plan.sourceKey(), e.getLocalizedMessage());
				stuck.add(plan);
			}
		}
		return stuck;
	}

	private static String failureMessage(PlannedMove failed, IOException cause, List<PlannedMove> stuck) {
		String message = "Could not " + failed.verb() + " '" + failed.sourceKey() + "' to '" + failed.targetKey() + "': " + cause.getLocalizedMessage();
		if (stuck.isEmpty()) {
			return message;
		}
		return message + ". These items were moved but could not be moved back, check them in the Scripts Library: "
				+ String.join(", ", stuck.stream().map(p -> p.sourceKey() + " is now " + p.targetKey()).toList());
	}

	/** Rename and move of one Script: never over an existing item; the revision history follows the new path. */
	private ScriptAsset relocateScript(ScriptsLibrary library, String assetId, Path source, Path target, String comment, String verb)
			throws BroadSQLException {
		String currentKey = library.keyOf(source);
		String newKey = library.keyOf(target);
		if (Files.exists(target)) {
			throw new BroadSQLException("'" + newKey + "' already exists");
		}
		try {
			if (target.getParent() != null) {
				Files.createDirectories(target.getParent());
			}
			pathMover.move(source, target);
		} catch (IOException e) {
			throw new BroadSQLException("Could not " + verb + " '" + currentKey + "' to '" + newKey + "': " + e.getLocalizedMessage(), e);
		}
		revisionVault.recordRenameRevision(assetId, newKey, comment);
		return open(newKey);
	}

	/** {@code null} for the library root; otherwise the key of an existing folder, or refused. */
	private static String requireDestinationFolder(ScriptsLibrary library, ScriptResolver resolver, String destinationFolderOrNull) throws BroadSQLException {
		if (destinationFolderOrNull == null || destinationFolderOrNull.isBlank()) {
			return null;
		}
		Path destination = resolver.resolveInLibrary(destinationFolderOrNull);
		if (!Files.isDirectory(destination)) {
			throw new BroadSQLException("'" + library.keyOf(destination) + "' is not a folder of the Scripts Library");
		}
		return library.keyOf(destination);
	}

	/** The key of the folder holding {@code key}; {@code null} for the library root. */
	static String parentKeyOf(String key) {
		int slash = key.lastIndexOf('/');
		return slash >= 0 ? key.substring(0, slash) : null;
	}

	private static String childKey(String folderKeyOrNull, String name) {
		return folderKeyOrNull == null ? name : folderKeyOrNull + "/" + name;
	}

	/** Library keys compare without case: on Windows the library is on a case-insensitive filesystem, where two names differing only by case are the same place. */
	private static boolean sameKey(String a, String b) {
		return a == null ? b == null : b != null && a.equalsIgnoreCase(b);
	}

	/** Whether {@code key} lies strictly inside the folder {@code folderKey}. */
	static boolean isInside(String key, String folderKey) {
		return key.length() > folderKey.length() + 1 && key.regionMatches(true, 0, folderKey + "/", 0, folderKey.length() + 1);
	}

	private static String folderLabel(String folderKeyOrNull) {
		return folderKeyOrNull == null ? "the Scripts Library root" : "'" + folderKeyOrNull + "'";
	}

	/** The absolute filesystem path of the Scripts Library item {@code relativePath} (the Editor's Copy Full Path). */
	public Path absolutePathOf(String relativePath) throws BroadSQLException {
		return resolver().resolveInLibrary(relativePath);
	}

	/** Deletes an EMPTY folder; a folder that still holds anything is refused (delete or move its Scripts first). */
	public void deleteEmptyFolder(String relativePath) throws BroadSQLException {
		Path target = resolver().resolveInLibrary(relativePath);
		if (!Files.isDirectory(target)) {
			throw new BroadSQLException("'" + relativePath + "' is not a folder");
		}
		try (Stream<Path> children = Files.list(target)) {
			if (children.findAny().isPresent()) {
				throw new BroadSQLException("The folder '" + relativePath + "' is not empty; delete or move its scripts first");
			}
			Files.delete(target);
		} catch (IOException e) {
			throw new BroadSQLException("Could not delete the folder: " + e.getLocalizedMessage(), e);
		}
	}

	/**
	 * Creates a new, independent Script from {@code sourceRelativePath}'s current content, at
	 * {@code newRelativePath}. The duplicate gets its own new asset id and its own fresh v1 baseline - it
	 * never shares revision identity with the source (spec section 6.7). The source's encoding is kept.
	 */
	public ScriptAsset duplicate(String sourceRelativePath, String newRelativePath) throws BroadSQLException {
		ScriptsLibrary library = library();
		ScriptResolver resolver = resolver();
		String sourceKey = library.keyOf(resolver.resolveInLibrary(sourceRelativePath));
		Path target = resolver.resolveInLibrary(normalizeNewName(newRelativePath));
		String newKey = library.keyOf(target);
		if (Files.exists(target)) {
			throw new BroadSQLException("'" + newKey + "' already exists");
		}
		library.write(target, library.read(sourceKey));
		return open(newKey);
	}

	/**
	 * Deletes a Script the one way the Scripts Library deletes anything: {@link ScriptsLibrary#archive}
	 * (moves it to {@code archives/}), exactly what {@code LIB DEL} does. It is recoverable with
	 * {@code LIB RESTORE}/{@code LIB UNDO} or the editor's Recently Deleted; revision history follows the
	 * archive through {@link ScriptsLibrary.HistoryLinkage}.
	 */
	public void delete(String relativePath) throws BroadSQLException {
		ScriptsLibrary library = library();
		Path target = resolver().resolveInLibrary(relativePath);
		library.archive(target);
	}

	/** Archived (deleted) Scripts, newest first - the editor's Recently Deleted reads the library's own archive. */
	public List<ScriptsLibrary.ArchivedEntry> listArchived() throws BroadSQLException {
		return library().getArchivedEntries();
	}

	/** Restores an archived Script to its original path; refuses if that path is occupied. */
	public ScriptAsset restoreArchived(ScriptsLibrary.ArchivedEntry entry) throws BroadSQLException {
		library().restoreEntry(entry);
		return open(entry.getOriginalRelativePath());
	}

	/**
	 * Whether the working file changed outside BroadSQL since {@code knownLastModifiedMillis} (the value
	 * the caller's tab last observed). Spec section 29 - never overwrites anything itself.
	 */
	public ExternalChangeStatus detectExternalChange(String relativePath, long knownLastModifiedMillis) throws BroadSQLException {
		ScriptsLibrary library = library();
		String key = library.keyOf(resolver().resolveInLibrary(relativePath));
		if (!library.hasKey(key)) {
			return ExternalChangeStatus.DELETED_EXTERNALLY;
		}
		long current = library.getLastModifiedMillis(key);
		return current == knownLastModifiedMillis ? ExternalChangeStatus.UNCHANGED : ExternalChangeStatus.CHANGED;
	}

	/**
	 * Restores revision {@code revisionNumber}'s content/metadata into the Script at its current path
	 * (spec section 11 - restore never rewrites history; it always creates a new revision).
	 */
	public ScriptAsset restoreRevision(String assetId, int revisionNumber, String comment) throws BroadSQLException {
		RestoreOutcome outcome = revisionVault.restore(assetId, revisionNumber, comment);
		ScriptsLibrary library = library();
		String currentPath = outcome.newRevision().relativePathAtRevision();
		Path target = resolver().resolveInLibrary(currentPath);
		library.write(target, encodingFor(target, outcome.restoredContent()));
		return open(currentPath);
	}
}
