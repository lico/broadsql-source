package com.upandcoding.broadsql.controller.shell.reader;

/**
 * One completion candidate - SPRINT XT02A (URL-Native API Execution), section 13. Deliberately not a
 * JLine type: {@link CompletionService} (and everything it calls) knows nothing about JLine, so it
 * can be unit-tested without a real terminal - only the JLine-coupled adapter
 * ({@code BroadSqlJLineCompleter}) translates this into {@code org.jline.reader.Candidate}.
 */
public final class CompletionCandidate {

	/** The full replacement text for the current word - never a partial/delta string, e.g. selecting the {@code CUST} alias's candidate replaces the whole word with {@code /api/customer/:id}. */
	private final String value;
	/** Short label shown in the candidate menu (defaults to {@link #value} if not given separately - e.g. the alias {@code CUST} while {@link #value} is the expanded URL). */
	private final String display;
	/** One-line description shown alongside the candidate, or {@code null}. Never a resolved/secret value (section 13.5/13.11/22.1). */
	private final String description;
	/** SPRINT 0917-02, section 21 - {@code null} for every SPRINT XT02A API candidate (never set, never needed there); always set by the newer BroadSQL/SQL/JDBC-metadata engine. */
	private final CompletionCandidateType type;

	public CompletionCandidate(String value, String display, String description) {
		this(value, display, description, null);
	}

	public CompletionCandidate(String value, String display, String description, CompletionCandidateType type) {
		this.value = value;
		this.display = display == null ? value : display;
		this.description = description;
		this.type = type;
	}

	public String getValue() {
		return value;
	}

	public String getDisplay() {
		return display;
	}

	public String getDescription() {
		return description;
	}

	public CompletionCandidateType getType() {
		return type;
	}

	@Override
	public String toString() {
		return "CompletionCandidate{value=" + value + ", display=" + display + "}";
	}
}
