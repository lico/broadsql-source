package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.List;

/** The result of {@link ScriptFormatterService#format} - mirrors {@code SqlFormatResult}'s success/unsupported shape, plus per-statement notes for a mixed script where one region could not be safely reformatted and was therefore left unchanged. */
public final class FormatOutcome {

	private final boolean supported;
	private final String formattedContent;
	private final String reason;
	private final List<String> notes;

	private FormatOutcome(boolean supported, String formattedContent, String reason, List<String> notes) {
		this.supported = supported;
		this.formattedContent = formattedContent;
		this.reason = reason;
		this.notes = notes;
	}

	public static FormatOutcome success(String formattedContent, List<String> notes) {
		return new FormatOutcome(true, formattedContent, null, notes);
	}

	public static FormatOutcome unsupported(String reason) {
		return new FormatOutcome(false, null, reason, List.of());
	}

	public boolean isSupported() {
		return supported;
	}

	public String formattedContent() {
		return formattedContent;
	}

	public String reason() {
		return reason;
	}

	/** Non-blocking notes about individual regions/statements left unchanged - empty on a fully-formatted result. */
	public List<String> notes() {
		return notes;
	}
}
