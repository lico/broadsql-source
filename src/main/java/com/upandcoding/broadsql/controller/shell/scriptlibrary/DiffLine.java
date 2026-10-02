package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.Collections;
import java.util.List;

/**
 * One row of a side-by-side text diff (SPRINT 0917-01, spec section 10.2) - an unchanged, added,
 * removed, or modified line pair, with the changed character ranges within a modified line's old/new
 * text (intra-line highlighting, spec section 10.2's "highlight the changed portion within a modified
 * line, not merely the whole line").
 */
public final class DiffLine {

	public enum Tag {
		EQUAL, INSERT, DELETE, CHANGE
	}

	private final Tag tag;
	private final Integer oldLineNumber;
	private final Integer newLineNumber;
	private final String oldText;
	private final String newText;
	private final List<int[]> oldHighlightRanges;
	private final List<int[]> newHighlightRanges;

	public DiffLine(Tag tag, Integer oldLineNumber, Integer newLineNumber, String oldText, List<int[]> oldHighlightRanges,
			String newText, List<int[]> newHighlightRanges) {
		this.tag = tag;
		this.oldLineNumber = oldLineNumber;
		this.newLineNumber = newLineNumber;
		this.oldText = oldText;
		this.newText = newText;
		this.oldHighlightRanges = oldHighlightRanges == null ? List.of() : Collections.unmodifiableList(oldHighlightRanges);
		this.newHighlightRanges = newHighlightRanges == null ? List.of() : Collections.unmodifiableList(newHighlightRanges);
	}

	public Tag tag() {
		return tag;
	}

	/** {@code null} for an {@link Tag#INSERT} row - there is no corresponding old-side line. */
	public Integer oldLineNumber() {
		return oldLineNumber;
	}

	/** {@code null} for a {@link Tag#DELETE} row - there is no corresponding new-side line. */
	public Integer newLineNumber() {
		return newLineNumber;
	}

	public String oldText() {
		return oldText;
	}

	public String newText() {
		return newText;
	}

	/** {@code [start, end)} character ranges within {@link #oldText()} that changed - only ever non-empty for {@link Tag#CHANGE}. */
	public List<int[]> oldHighlightRanges() {
		return oldHighlightRanges;
	}

	/** {@code [start, end)} character ranges within {@link #newText()} that changed - only ever non-empty for {@link Tag#CHANGE}. */
	public List<int[]> newHighlightRanges() {
		return newHighlightRanges;
	}
}
