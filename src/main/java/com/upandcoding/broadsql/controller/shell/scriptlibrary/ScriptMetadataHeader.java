package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;

/**
 * A preservation-safe wrapper around {@link EntryMetadata} for the Script Library (SPRINT 0917-01):
 * the parsed, known fields ({@link #metadata()}) plus every original raw {@code -- @key: value}
 * header line, in order, exactly as written ({@link #rawLines()}). See
 * {@code docs/plans/SPRINT_0917-01_IMPLEMENTATION_PLAN.md}, "Metadata round-trip preservation" - this
 * class exists specifically so that editing one known field through the GUI never silently drops an
 * unknown/future metadata key, a comment, or unusual formatting {@link EntryMetadata#parse} already
 * tolerates but does not itself round-trip.
 *
 * <p>{@link EntryMetadata} remains the one authoritative model for what a key <em>means</em>
 * (parsing/validation/scoping); this class only adds round-trip-safe serialization on top of it,
 * deliberately without duplicating or reimplementing any of that parsing logic itself.
 */
public final class ScriptMetadataHeader {

	private final EntryMetadata metadata;
	private final List<String> rawLines;

	private ScriptMetadataHeader(EntryMetadata metadata, List<String> rawLines) {
		this.metadata = metadata;
		this.rawLines = Collections.unmodifiableList(rawLines);
	}

	/** Parses {@code content} (a full asset file, header included) into its known fields and raw header lines. */
	public static ScriptMetadataHeader parse(String content) {
		return new ScriptMetadataHeader(EntryMetadata.parse(content), new ArrayList<>(EntryMetadata.extractHeaderLines(content)));
	}

	/** A brand-new asset with no original header to preserve - starts from {@code seedHeaderLines} verbatim (e.g. {@code CommandLibEdit.SEED_SKELETON}'s convention). */
	public static ScriptMetadataHeader fromSeed(List<String> seedHeaderLines) {
		String joined = String.join("\n", seedHeaderLines) + (seedHeaderLines.isEmpty() ? "" : "\n");
		return new ScriptMetadataHeader(EntryMetadata.parse(joined), new ArrayList<>(seedHeaderLines));
	}

	public EntryMetadata metadata() {
		return metadata;
	}

	/** The original header lines, unmodified, in original order - never regenerated from {@link #metadata()} alone. */
	public List<String> rawLines() {
		return rawLines;
	}

	/** Reconstructs the header block text (each raw line, newline-joined, with a trailing newline if non-empty) - concatenate with the body ({@link EntryMetadata#stripHeader}) to get the complete asset content. */
	public String toHeaderText() {
		return rawLines.isEmpty() ? "" : String.join("\n", rawLines) + "\n";
	}

	/**
	 * Patches only {@code key}'s own raw line(s): every existing raw line declaring {@code key}
	 * (case-insensitive) is removed and replaced by a single new {@code -- @key: value} line appended
	 * at the end of the header block - every other original raw line (other known keys, unknown keys,
	 * comments) is left byte-identical, per the round-trip preservation requirement. Returns a new
	 * instance; this one is unchanged.
	 */
	public ScriptMetadataHeader withField(String key, String value) {
		List<String> patched = withoutLinesFor(key);
		patched.add("-- @" + key + ": " + (value == null ? "" : value));
		return rebuild(patched);
	}

	/**
	 * Removes every raw line declaring {@code key} entirely - no replacement line is written. Used when
	 * the field's value is being cleared back to "not declared" (e.g. {@code @instance}/{@code
	 * @environment}'s {@code NONE} display sentinel, which is not itself a valid raw value -
	 * {@link EntryMetadata#parse} has no special handling for a literal {@code NONE} token the way it
	 * does for {@code ALL}, so writing {@code -- @instance: NONE} would round-trip back as a real,
	 * single instance id named "NONE" rather than "no tag at all").
	 */
	public ScriptMetadataHeader withoutField(String key) {
		return rebuild(withoutLinesFor(key));
	}

	private List<String> withoutLinesFor(String key) {
		List<String> remaining = new ArrayList<>();
		for (String line : rawLines) {
			if (!key.equalsIgnoreCase(keyOf(line))) {
				remaining.add(line);
			}
		}
		return remaining;
	}

	private static ScriptMetadataHeader rebuild(List<String> lines) {
		String headerText = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
		return new ScriptMetadataHeader(EntryMetadata.parse(headerText), lines);
	}

	/**
	 * The {@code key} declared by one raw header line, or {@code null} if it isn't a directive line at
	 * all. Delegates to {@link EntryMetadata#analyzeDirectiveLine(String)} - the one canonical
	 * directive-line grammar - rather than reimplementing a second, competing (and previously
	 * colon-only) parser here; a raw line written as {@code -- @environment PROD} (space-separated)
	 * must be recognized as declaring {@code environment} just as reliably as {@code -- @environment:
	 * PROD}, or {@link #withField}/{@link #withoutField} would silently leave the old line behind
	 * instead of patching/removing it (SPRINT 0917-01 corrective acceptance pass).
	 */
	private static String keyOf(String rawLine) {
		EntryMetadata.DirectiveLine directive = EntryMetadata.analyzeDirectiveLine(rawLine);
		return directive == null ? null : directive.key();
	}
}
