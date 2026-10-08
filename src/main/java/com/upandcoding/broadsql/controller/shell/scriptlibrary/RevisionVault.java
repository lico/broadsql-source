package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;
import com.upandcoding.broadsql.dao.util.ContentHasher;
import com.upandcoding.broadsql.dao.util.SafeFileWriter;

/**
 * The Script Library's filesystem-only revision history vault (SPRINT 0917-01) - no database of any
 * kind (binding decision, see {@code docs/plans/SPRINT_0917-01_IMPLEMENTATION_PLAN.md}). Every
 * revision is a complete, immutable text snapshot, never a delta - SQL/scripts are small, so a delta
 * chain would only add fragility for negligible disk savings.
 *
 * <p><b>On-disk layout</b>, rooted at the path {@code ConsoleSettings#resolveScriptHistoryVaultPath()}
 * resolves (default {@code ${user.home}/.broadsql/script-history/}):
 * <pre>
 * script-history/
 *   index.json                         current (root, relativePath) -&gt; assetId mappings
 *   assets/
 *     &lt;assetId&gt;/
 *       manifest.json                  revision summaries + current path (+ archive pointer while archived)
 *       revisions/
 *         v1.txt, v2.txt, ...          full content snapshot per revision
 * </pre>
 *
 * <p><b>History identity</b> (workspace/root-aware): {@code index.json}'s key is the pair
 * {@code (libraryRootIdentity, relativePath)}, never a bare relative path -
 * {@code libraryRootIdentity} is the canonical, normalized absolute path of the resolved Scripts
 * Library root (see {@link #rootIdentityOf}). Two different installations/workspaces whose Scripts
 * Library directories differ never collide on identity even if their relative paths happen to match.
 *
 * <p><b>Deletion is not defined here</b> (SPRINT 1909S): a Script is deleted by {@code ScriptsLibrary}'s
 * archive. This vault only follows it through {@link ScriptsLibrary.HistoryLinkage}: archiving frees the
 * path in the index and remembers the archive file (a lineage pointer), restoring that archive file
 * re-attaches the same history, and a brand-new file later created at the same path starts a new lineage.
 *
 * <p><b>Revision creation semantics</b>: "did anything change" is never gated on the body content hash
 * alone. Each revision's logical state is the triple {@code (bodyHash, metadataHash,
 * relativePathAtRevision)}; a new revision is created if <b>any</b> component differs from the latest
 * revision's stored state (metadata-only and rename-only changes are real, versioned revisions), and
 * is a no-op only if all three are unchanged. {@code recordRestoreRevision}/{@code restore}/
 * always create a new revision regardless of this gate - restore never rewrites
 * or truncates history.
 *
 * <p>Every mutating method is {@code synchronized} on this instance - simple, JVM-local safety, not a
 * distributed lock; adequate given this vault is only ever touched by one BroadSQL process's single
 * Script Library workspace (see the window-lifecycle binding decision).
 */
public final class RevisionVault implements ScriptsLibrary.HistoryLinkage {

	private final Path vaultRoot;
	private final Gson gson;

	public RevisionVault(Path vaultRoot) {
		this.vaultRoot = vaultRoot;
		this.gson = new GsonBuilder().setPrettyPrinting().create();
	}

	/** The canonical, normalized absolute path of a catalog root - the {@code libraryRootIdentity} component of every index key. */
	public static String rootIdentityOf(String rawRootPath) throws BroadSQLException {
		try {
			return Path.of(rawRootPath).toRealPath().normalize().toString();
		} catch (IOException e) {
			throw new BroadSQLException("Could not resolve the canonical path of '" + rawRootPath + "': " + e.getLocalizedMessage(), e);
		}
	}

	private static String normalizePath(String relativePath) {
		return relativePath.replace('\\', '/');
	}

	// ------------------------------------------------------------------
	// Identity / registration
	// ------------------------------------------------------------------

	/** Finds the current asset id for {@code (rootIdentity, relativePath)}, or allocates and registers a brand-new one if no live mapping exists - a new UUID never inherits the history of an archived Script that used the same path (archiving freed the path; see the class comment). */
	public synchronized String resolveOrRegisterAssetId(String rootIdentity, String relativePath) throws BroadSQLException {
		String path = normalizePath(relativePath);
		IndexFile index = loadIndex();
		for (IndexEntry entry : index.entries) {
			if (entry.rootIdentity.equals(rootIdentity) && entry.relativePath.equals(path)) {
				return entry.assetId;
			}
		}
		String assetId = UUID.randomUUID().toString();
		index.entries.add(new IndexEntry(rootIdentity, path, assetId));
		saveIndex(index);

		AssetManifestDto manifest = new AssetManifestDto();
		manifest.assetId = assetId;
		manifest.rootIdentity = rootIdentity;
		manifest.currentRelativePath = path;
		manifest.revisions = new ArrayList<>();
		saveManifest(assetId, manifest);
		return assetId;
	}

	// ------------------------------------------------------------------
	// Recording revisions
	// ------------------------------------------------------------------

	/** Generic entry point (used by Save): creates a new revision if the body, metadata or path differs from the latest revision; a no-op otherwise. Origin is derived automatically. */
	public synchronized RevisionOutcome recordRevisionIfChanged(String assetId, String content, String relativePath, String comment) throws BroadSQLException {
		return recordInternal(assetId, content, normalizePath(relativePath), comment, false, null);
	}

	/** Rename/move only: keeps the latest revision's content, changes only the path. A no-op if {@code newRelativePath} equals the current path. */
	public synchronized RevisionOutcome recordRenameRevision(String assetId, String newRelativePath, String comment) throws BroadSQLException {
		Revision latest = getLatestRevision(assetId);
		String content = latest != null ? latest.content() : "";
		return recordInternal(assetId, content, normalizePath(newRelativePath), comment, false, null);
	}

	/** Always creates a new revision (never a no-op, per the binding decision that Restore never rewrites history), tagged {@link RevisionOrigin#RESTORE}. */
	public synchronized RevisionOutcome recordRestoreRevision(String assetId, String content, String relativePath, String comment) throws BroadSQLException {
		return recordInternal(assetId, content, normalizePath(relativePath), comment, true, RevisionOrigin.RESTORE);
	}

	private RevisionOutcome recordInternal(String assetId, String content, String relativePath, String comment, boolean forceCreate, RevisionOrigin forcedOrigin)
			throws BroadSQLException {
		AssetManifestDto manifest = loadManifest(assetId);
		ScriptMetadataHeader header = ScriptMetadataHeader.parse(content);
		String body = EntryMetadata.stripHeader(content);
		String bodyHash = ContentHasher.sha256Hex(body);
		String metadataHash = ContentHasher.sha256Hex(header.toHeaderText());

		RevisionEntryDto latest = manifest.revisions.isEmpty() ? null : manifest.revisions.get(manifest.revisions.size() - 1);
		boolean bodyChanged = latest == null || !latest.bodyHash.equals(bodyHash);
		boolean metadataChanged = latest == null || !latest.metadataHash.equals(metadataHash);
		boolean pathChanged = latest == null || !latest.relativePathAtRevision.equals(relativePath);

		if (!forceCreate && latest != null && !bodyChanged && !metadataChanged && !pathChanged) {
			return new RevisionOutcome(false, toSummary(assetId, latest));
		}

		RevisionOrigin origin = forcedOrigin != null ? forcedOrigin : deriveOrigin(latest == null, bodyChanged, metadataChanged, pathChanged);
		int newNumber = latest == null ? 1 : latest.revisionNumber + 1;

		try {
			SafeFileWriter.writeString(revisionContentPath(assetId, newNumber), content, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new BroadSQLException("Could not write revision v" + newNumber + " for asset " + assetId + ": " + e.getLocalizedMessage(), e);
		}

		RevisionEntryDto entry = new RevisionEntryDto();
		entry.revisionNumber = newNumber;
		entry.timestampMillis = System.currentTimeMillis();
		entry.comment = comment;
		entry.origin = origin.name();
		entry.relativePathAtRevision = relativePath;
		entry.bodyHash = bodyHash;
		entry.metadataHash = metadataHash;
		manifest.revisions.add(entry);
		manifest.currentRelativePath = relativePath;
		saveManifest(assetId, manifest);

		syncIndexMapping(manifest.rootIdentity, assetId, relativePath);

		return new RevisionOutcome(true, toSummary(assetId, entry));
	}

	private static RevisionOrigin deriveOrigin(boolean isFirst, boolean bodyChanged, boolean metadataChanged, boolean pathChanged) {
		if (isFirst) {
			return RevisionOrigin.CREATE;
		}
		if (bodyChanged) {
			return RevisionOrigin.EDIT_SAVE;
		}
		if (metadataChanged) {
			return RevisionOrigin.METADATA_ONLY;
		}
		return RevisionOrigin.RENAME; // pathChanged must be true to have reached here
	}

	/** Removes any existing index mapping for {@code assetId} (it has at most one live mapping) and adds the current one - keeps a renamed asset's old path free for reuse by an unrelated asset. */
	private void syncIndexMapping(String rootIdentity, String assetId, String relativePath) throws BroadSQLException {
		IndexFile index = loadIndex();
		index.entries.removeIf(e -> e.assetId.equals(assetId));
		index.entries.add(new IndexEntry(rootIdentity, relativePath, assetId));
		saveIndex(index);
	}

	// ------------------------------------------------------------------
	// Reading revisions
	// ------------------------------------------------------------------

	/** Every revision for {@code assetId}, ascending by revision number. */
	public synchronized List<RevisionSummary> listRevisions(String assetId) throws BroadSQLException {
		AssetManifestDto manifest = loadManifest(assetId);
		List<RevisionSummary> summaries = new ArrayList<>();
		for (RevisionEntryDto entry : manifest.revisions) {
			summaries.add(toSummary(assetId, entry));
		}
		return summaries;
	}

	public synchronized Revision getRevision(String assetId, int revisionNumber) throws BroadSQLException {
		AssetManifestDto manifest = loadManifest(assetId);
		for (RevisionEntryDto entry : manifest.revisions) {
			if (entry.revisionNumber == revisionNumber) {
				return new Revision(toSummary(assetId, entry), readRevisionContent(assetId, revisionNumber));
			}
		}
		throw new BroadSQLException("Asset " + assetId + " has no revision v" + revisionNumber);
	}

	public synchronized RevisionSummary getLatestRevisionSummary(String assetId) throws BroadSQLException {
		AssetManifestDto manifest = loadManifest(assetId);
		if (manifest.revisions.isEmpty()) {
			return null;
		}
		return toSummary(assetId, manifest.revisions.get(manifest.revisions.size() - 1));
	}

	public synchronized Revision getLatestRevision(String assetId) throws BroadSQLException {
		RevisionSummary latest = getLatestRevisionSummary(assetId);
		return latest == null ? null : getRevision(assetId, latest.revisionNumber());
	}

	// ------------------------------------------------------------------
	// Restore (historical revision -> current asset, Phase D)
	// ------------------------------------------------------------------

	/** Restores {@code revisionNumber}'s content/metadata into the asset at its <b>current</b> path (path restoration is deliberately cautious, per spec section 11 - this never renames the current asset). Always creates a new revision; never rewrites or removes later revisions. */
	public synchronized RestoreOutcome restore(String assetId, int revisionNumber, String comment) throws BroadSQLException {
		Revision historical = getRevision(assetId, revisionNumber);
		AssetManifestDto manifest = loadManifest(assetId);
		String effectiveComment = comment != null ? comment : ("Restored from v" + revisionNumber);
		RevisionOutcome outcome = recordInternal(assetId, historical.content(), manifest.currentRelativePath, effectiveComment, true, RevisionOrigin.RESTORE);
		return new RestoreOutcome(outcome.latestRevision(), historical.content());
	}

	// ------------------------------------------------------------------
	// Archive lineage (SPRINT 1909S): follows ScriptsLibrary's archive/restore, never defines them
	// ------------------------------------------------------------------

	/** {@link ScriptsLibrary.HistoryLinkage}: the Script at {@code relativeKey} was archived as {@code archiveRelativeKey}. Frees the path and remembers the archive file; never throws (history must not block an archive). */
	@Override
	public synchronized void archived(Path libraryRoot, String relativeKey, String archiveRelativeKey) {
		try {
			String rootIdentity = rootIdentityOf(libraryRoot.toString());
			IndexFile index = loadIndex();
			IndexEntry found = null;
			for (IndexEntry entry : index.entries) {
				if (entry.rootIdentity.equals(rootIdentity) && entry.relativePath.equals(normalizePath(relativeKey))) {
					found = entry;
					break;
				}
			}
			if (found == null) {
				return; // never opened in the editor: no history to keep
			}
			AssetManifestDto manifest = loadManifest(found.assetId);
			manifest.archivedAs = normalizePath(archiveRelativeKey);
			saveManifest(found.assetId, manifest);
			final String assetId = found.assetId;
			index.entries.removeIf(e -> e.assetId.equals(assetId));
			saveIndex(index);
		} catch (BroadSQLException | RuntimeException e) {
			// history never blocks archive
		}
	}

	/** {@link ScriptsLibrary.HistoryLinkage}: the archive file {@code archiveRelativeKey} was restored to {@code relativeKey}. Re-attaches the history that was following that archive file; never throws. */
	@Override
	public synchronized void restored(Path libraryRoot, String archiveRelativeKey, String relativeKey) {
		try {
			String rootIdentity = rootIdentityOf(libraryRoot.toString());
			Path assetsDir = vaultRoot.resolve("assets");
			if (!Files.isDirectory(assetsDir)) {
				return;
			}
			String archiveKey = normalizePath(archiveRelativeKey);
			try (var stream = Files.list(assetsDir)) {
				for (Path assetDir : stream.toList()) {
					String assetId = assetDir.getFileName().toString();
					if (!Files.exists(manifestPath(assetId))) {
						continue;
					}
					AssetManifestDto manifest = loadManifest(assetId);
					if (archiveKey.equals(manifest.archivedAs) && rootIdentity.equals(manifest.rootIdentity)) {
						IndexFile index = loadIndex();
						boolean occupied = false;
						for (IndexEntry entry : index.entries) {
							if (entry.rootIdentity.equals(rootIdentity) && entry.relativePath.equals(normalizePath(relativeKey))) {
								occupied = true;
								break;
							}
						}
						manifest.archivedAs = null;
						manifest.currentRelativePath = normalizePath(relativeKey);
						saveManifest(assetId, manifest);
						if (!occupied) {
							index.entries.add(new IndexEntry(rootIdentity, normalizePath(relativeKey), assetId));
							saveIndex(index);
						}
						return;
					}
				}
			}
		} catch (IOException | BroadSQLException | RuntimeException e) {
			// history never blocks restore
		}
	}

	// ------------------------------------------------------------------
	// Reconciliation (Save/history partial-failure semantics)
	// ------------------------------------------------------------------

	/**
	 * Compares {@code currentOnDiskContent}'s logical state against the vault's last recorded revision;
	 * if they differ (e.g. because a prior Save's history write failed - see the Save/history
	 * partial-failure semantics in the implementation plan), silently records the missing revision as a
	 * catch-up. A no-op if the vault already reflects the on-disk state.
	 */
	public synchronized RevisionOutcome reconcileIfNeeded(String assetId, String relativePath, String currentOnDiskContent) throws BroadSQLException {
		return recordInternal(assetId, currentOnDiskContent, normalizePath(relativePath), "Reconciled after a prior history write failure", false, null);
	}

	// ------------------------------------------------------------------
	// Persistence plumbing
	// ------------------------------------------------------------------

	private RevisionSummary toSummary(String assetId, RevisionEntryDto entry) {
		return new RevisionSummary(assetId, entry.revisionNumber, entry.timestampMillis, entry.comment, RevisionOrigin.valueOf(entry.origin),
				entry.relativePathAtRevision, entry.bodyHash, entry.metadataHash);
	}

	private String readRevisionContent(String assetId, int revisionNumber) throws BroadSQLException {
		try {
			return Files.readString(revisionContentPath(assetId, revisionNumber), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new BroadSQLException("Could not read revision v" + revisionNumber + " for asset " + assetId + ": " + e.getLocalizedMessage(), e);
		}
	}

	private Path indexPath() {
		return vaultRoot.resolve("index.json");
	}

	private Path assetDir(String assetId) {
		return vaultRoot.resolve("assets").resolve(assetId);
	}

	private Path manifestPath(String assetId) {
		return assetDir(assetId).resolve("manifest.json");
	}

	private Path revisionContentPath(String assetId, int revisionNumber) {
		return assetDir(assetId).resolve("revisions").resolve("v" + revisionNumber + ".txt");
	}

	private IndexFile loadIndex() throws BroadSQLException {
		Path path = indexPath();
		if (!Files.exists(path)) {
			return new IndexFile();
		}
		try {
			String json = Files.readString(path, StandardCharsets.UTF_8);
			IndexFile index = gson.fromJson(json, IndexFile.class);
			return index != null ? index : new IndexFile();
		} catch (IOException e) {
			throw new BroadSQLException("Could not read the script history index at '" + path + "': " + e.getLocalizedMessage(), e);
		}
	}

	private void saveIndex(IndexFile index) throws BroadSQLException {
		try {
			SafeFileWriter.writeString(indexPath(), gson.toJson(index), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new BroadSQLException("Could not write the script history index at '" + indexPath() + "': " + e.getLocalizedMessage(), e);
		}
	}

	private AssetManifestDto loadManifest(String assetId) throws BroadSQLException {
		Path path = manifestPath(assetId);
		if (!Files.exists(path)) {
			throw new BroadSQLException("Unknown script history asset id: " + assetId);
		}
		try {
			String json = Files.readString(path, StandardCharsets.UTF_8);
			AssetManifestDto manifest = gson.fromJson(json, AssetManifestDto.class);
			if (manifest.revisions == null) {
				manifest.revisions = new ArrayList<>();
			}
			return manifest;
		} catch (IOException e) {
			throw new BroadSQLException("Could not read the manifest for asset " + assetId + ": " + e.getLocalizedMessage(), e);
		}
	}

	private void saveManifest(String assetId, AssetManifestDto manifest) throws BroadSQLException {
		try {
			SafeFileWriter.writeString(manifestPath(assetId), gson.toJson(manifest), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new BroadSQLException("Could not write the manifest for asset " + assetId + ": " + e.getLocalizedMessage(), e);
		}
	}

	// ------------------------------------------------------------------
	// Plain JSON DTOs (Gson (de)serialization only - never exposed outside this class)
	// ------------------------------------------------------------------

	private static final class IndexFile {
		List<IndexEntry> entries = new ArrayList<>();
	}

	private static final class IndexEntry {
		String rootIdentity;
		String relativePath;
		String assetId;

		IndexEntry(String rootIdentity, String relativePath, String assetId) {
			this.rootIdentity = rootIdentity;
			this.relativePath = relativePath;
			this.assetId = assetId;
		}
	}

	private static final class AssetManifestDto {
		String assetId;
		String rootIdentity;
		String currentRelativePath;
		/** Non-null only while the Script is archived: the archive file's key, so restoring that file re-attaches this history. */
		String archivedAs;
		List<RevisionEntryDto> revisions = new ArrayList<>();
	}

	private static final class RevisionEntryDto {
		int revisionNumber;
		long timestampMillis;
		String comment;
		String origin;
		String relativePathAtRevision;
		String bodyHash;
		String metadataHash;
	}
}
