package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The result of {@link ScriptDiffService#diffContent} - every row plus the summary counts spec section 10.4 requires ("3 additions, 1 deletion, 2 modified lines"). Read-only: nothing on this class or {@link ScriptDiffService} can write anything back. */
public final class TextDiffResult {

	private final List<DiffLine> lines;
	private final int additions;
	private final int deletions;
	private final int modifications;
	private final List<Integer> changedRowIndices;

	public TextDiffResult(List<DiffLine> lines, int additions, int deletions, int modifications, List<Integer> changedRowIndices) {
		this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
		this.additions = additions;
		this.deletions = deletions;
		this.modifications = modifications;
		this.changedRowIndices = Collections.unmodifiableList(new ArrayList<>(changedRowIndices));
	}

	public List<DiffLine> lines() {
		return lines;
	}

	public int additions() {
		return additions;
	}

	public int deletions() {
		return deletions;
	}

	public int modifications() {
		return modifications;
	}

	public boolean hasChanges() {
		return additions > 0 || deletions > 0 || modifications > 0;
	}

	/** Index into {@link #lines()} of every non-{@link DiffLine.Tag#EQUAL} row, in order - what Previous/Next Change navigation (spec section 10.3) jumps between. */
	public List<Integer> changedRowIndices() {
		return changedRowIndices;
	}
}
