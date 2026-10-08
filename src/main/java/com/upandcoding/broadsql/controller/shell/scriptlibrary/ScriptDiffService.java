package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;

/**
 * {@code Compare} (SPRINT 0917-01, spec section 10 - "not optional"): pure data, no Swing - produces
 * exactly what {@code CompareDialog} needs to make a difference obvious at first glance, never a plain
 * unified text diff alone. Read-only by construction: no method on this class, or on any result type it
 * returns, ever writes anything back to an asset/revision.
 *
 * <p>Text diffing uses {@code java-diff-utils}'s {@link DiffRowGenerator} (line-level diff, with
 * intra-line highlighting via its own inline-diff mode) rather than a hand-rolled algorithm - the spec
 * explicitly requires "highlight the changed portion within a modified line, not merely the whole line,"
 * which a naive line-by-line comparison cannot produce reliably. Metadata and path differences are
 * always their own, separately-shown result (spec sections 10.5/10.6 - "the user must not miss
 * metadata-only revisions"), never folded into the text diff.
 */
public final class ScriptDiffService {

	/** A private-use marker character, never expected in real SQL/script text - toggles an intra-line highlight range on/off when parsing {@link DiffRowGenerator}'s tagged output. */
	private static final String HIGHLIGHT_MARKER = "";

	public TextDiffResult diffContent(String oldContent, String newContent) {
		List<String> oldLines = splitLines(oldContent);
		List<String> newLines = splitLines(newContent);

		DiffRowGenerator generator = DiffRowGenerator.create()
				.showInlineDiffs(true)
				.inlineDiffByWord(false)
				.oldTag(open -> HIGHLIGHT_MARKER)
				.newTag(open -> HIGHLIGHT_MARKER)
				.build();
		List<DiffRow> rows = generator.generateDiffRows(oldLines, newLines);

		List<DiffLine> lines = new ArrayList<>();
		List<Integer> changedIndices = new ArrayList<>();
		int additions = 0;
		int deletions = 0;
		int modifications = 0;
		int oldLineNo = 0;
		int newLineNo = 0;

		for (DiffRow row : rows) {
			DiffLine.Tag tag = mapTag(row.getTag());
			Integer oldNum = null;
			Integer newNum = null;
			ParsedLine oldParsed = null;
			ParsedLine newParsed = null;

			if (tag != DiffLine.Tag.INSERT) {
				oldLineNo++;
				oldNum = oldLineNo;
				oldParsed = parseMarkedLine(row.getOldLine());
			}
			if (tag != DiffLine.Tag.DELETE) {
				newLineNo++;
				newNum = newLineNo;
				newParsed = parseMarkedLine(row.getNewLine());
			}

			switch (tag) {
				case INSERT -> additions++;
				case DELETE -> deletions++;
				case CHANGE -> modifications++;
				default -> {
					// EQUAL - no count, no navigation entry.
				}
			}
			if (tag != DiffLine.Tag.EQUAL) {
				changedIndices.add(lines.size());
			}

			lines.add(new DiffLine(tag, oldNum, newNum,
					oldParsed != null ? oldParsed.text : null, oldParsed != null ? oldParsed.ranges : List.of(),
					newParsed != null ? newParsed.text : null, newParsed != null ? newParsed.ranges : List.of()));
		}

		return new TextDiffResult(lines, additions, deletions, modifications, changedIndices);
	}

	public MetadataDiffResult diffMetadata(ScriptMetadataHeader oldHeader, ScriptMetadataHeader newHeader) {
		List<MetadataFieldDiff> diffs = new ArrayList<>();
		addIfDifferent(diffs, "Description", oldHeader.metadata().getDescription(), newHeader.metadata().getDescription());
		addIfDifferent(diffs, "Instance", oldHeader.metadata().instanceDisplayValue(), newHeader.metadata().instanceDisplayValue());
		addIfDifferent(diffs, "Environment", oldHeader.metadata().environmentDisplayValue(), newHeader.metadata().environmentDisplayValue());
		addIfDifferent(diffs, "Tags", oldHeader.metadata().getTagsDisplayValue(), newHeader.metadata().getTagsDisplayValue());
		addIfDifferent(diffs, "Status", oldHeader.metadata().getStatus(), newHeader.metadata().getStatus());
		return new MetadataDiffResult(diffs);
	}

	public PathDiffResult diffPath(String oldPath, String newPath) {
		return new PathDiffResult(oldPath, newPath);
	}

	private static void addIfDifferent(List<MetadataFieldDiff> diffs, String fieldName, String oldValue, String newValue) {
		if (!Objects.equals(oldValue, newValue)) {
			diffs.add(new MetadataFieldDiff(fieldName, oldValue, newValue));
		}
	}

	private static DiffLine.Tag mapTag(DiffRow.Tag tag) {
		return switch (tag) {
			case INSERT -> DiffLine.Tag.INSERT;
			case DELETE -> DiffLine.Tag.DELETE;
			case CHANGE -> DiffLine.Tag.CHANGE;
			case EQUAL -> DiffLine.Tag.EQUAL;
		};
	}

	private static List<String> splitLines(String content) {
		if (content == null || content.isEmpty()) {
			return List.of();
		}
		return List.of(content.split("\n", -1));
	}

	/** Strips {@link #HIGHLIGHT_MARKER} pairs out of {@code marked}, recording the {@code [start, end)} range each pair enclosed in the resulting plain text. */
	private static ParsedLine parseMarkedLine(String marked) {
		if (marked == null) {
			return new ParsedLine("", List.of());
		}
		StringBuilder plain = new StringBuilder();
		List<int[]> ranges = new ArrayList<>();
		boolean inHighlight = false;
		int highlightStart = -1;
		int i = 0;
		while (i < marked.length()) {
			if (marked.startsWith(HIGHLIGHT_MARKER, i)) {
				if (!inHighlight) {
					inHighlight = true;
					highlightStart = plain.length();
				} else {
					ranges.add(new int[] { highlightStart, plain.length() });
					inHighlight = false;
				}
				i += HIGHLIGHT_MARKER.length();
			} else {
				plain.append(marked.charAt(i));
				i++;
			}
		}
		return new ParsedLine(plain.toString(), ranges);
	}

	private static final class ParsedLine {
		final String text;
		final List<int[]> ranges;

		ParsedLine(String text, List<int[]> ranges) {
			this.text = text;
			this.ranges = ranges;
		}
	}
}
