package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.sql.SqlLexException;
import com.upandcoding.broadsql.controller.shell.sql.SqlToken;
import com.upandcoding.broadsql.controller.shell.sql.SqlTokenType;
import com.upandcoding.broadsql.controller.shell.sql.SqlTokenizer;

/**
 * Changes one metadata directive of a Script's text <b>surgically</b> (SPRINT 3009A): the Editor's Metadata tab
 * edits the Script, never rewrites its comment header. Only the characters of the edited directive change:
 * every comment, blank line, other directive, its order and spacing, and the whole SQL body stay byte for byte.
 *
 * <p>Directives are found exactly where {@link EntryMetadata#parse} finds them, with the same grammar
 * ({@link EntryMetadata#analyzeDirectiveLine}): {@code --} line comments and the lines of {@code /* ... *}{@code /}
 * block comments, anywhere in the text (or every physical line when the text cannot be tokenized, the parser's own
 * fallback). Rules:
 * <ul>
 * <li><b>Set, the key exists</b>: the value of its first occurrence is replaced in place (the {@code --}, the
 * spacing, {@code :} or space separator are kept). For a key whose values accumulate over occurrences
 * ({@code instance}, {@code environment}, {@code tags}, {@code alias}), the other occurrences are removed, or the
 * new value would not be the Script's value; for a first-wins key ({@code description}, {@code status}) they are
 * left alone, since they have no effect.</li>
 * <li><b>Set, the key is missing</b>: one line is inserted after the last directive of the leading comment header,
 * in that line's own style ({@code -- @key: value}, or the block comment's prefix); after a directive sharing its
 * line with other text, or when the header has no directive, a {@code -- @key: value} line ({@code
 * ScriptLibraryService.newAssetSeedContent}'s convention), at the top of the file in the last case.</li>
 * <li><b>Clear</b> ({@code null}): every occurrence is removed: its whole line when the directive is alone on it
 * (that line only, neighbouring blank lines stay), otherwise only the directive's own characters.</li>
 * </ul>
 */
public final class MetadataDirectiveEditor {

	/** Keys whose values accumulate over every occurrence (see {@link EntryMetadata#parse}). */
	private static final Set<String> ACCUMULATING_KEYS = Set.of("instance", "environment", "tags", "alias");

	private MetadataDirectiveEditor() {
	}

	/** Applies {@link #setField} for each entry, in order ({@code null} value: clear). */
	public static String apply(String text, Map<String, String> changes) {
		String result = text;
		for (Map.Entry<String, String> change : changes.entrySet()) {
			result = setField(result, change.getKey(), change.getValue());
		}
		return result;
	}

	/**
	 * @param key   the directive name, without {@code @} (case-insensitive)
	 * @param value the new value, or {@code null} to remove the directive
	 */
	public static String setField(String text, String key, String value) {
		String source = text == null ? "" : text;
		String wanted = key.toLowerCase();
		List<Occurrence> all = occurrences(source);
		List<Occurrence> matching = all.stream().filter(o -> o.key.equals(wanted)).toList();
		List<Edit> edits = new ArrayList<>();
		if (value == null) {
			for (Occurrence o : matching) {
				edits.add(o.removal());
			}
		} else if (!matching.isEmpty()) {
			edits.add(matching.get(0).valueReplacement(value));
			if (ACCUMULATING_KEYS.contains(wanted)) {
				for (Occurrence o : matching.subList(1, matching.size())) {
					edits.add(o.removal());
				}
			}
		} else {
			edits.add(insertion(source, all, wanted, value));
		}
		return applyEdits(source, edits);
	}

	// ------------------------------------------------------------------
	// Locating directives
	// ------------------------------------------------------------------

	/** One directive occurrence, in absolute offsets of the text. */
	private static final class Occurrence {
		final String text;
		final String key;
		/** The {@code '@'}. */
		final int at;
		/** Just after the key name. */
		final int keyEnd;
		/** Where the value starts (after the separator and its spacing), and ends (trailing spaces excluded). */
		final int valueStart;
		final int valueEnd;
		final boolean hasSeparator;
		final boolean colon;
		/** The physical line holding the directive: [lineStart, lineEnd) without its line break, nextLine after it. */
		final int lineStart;
		final int lineEnd;
		final int nextLine;
		/** Whether the line holds nothing but the directive (with its comment marker and spacing). */
		final boolean ownsLine;

		Occurrence(String text, String segment, int segmentStart, EntryMetadata.DirectiveLine directive) {
			this.text = text;
			this.key = directive.key();
			this.at = segmentStart + directive.atIndex();
			this.keyEnd = at + 1 + directive.key().length();
			int segmentEnd = segmentStart + segment.length();
			int end = segmentEnd;
			while (end > keyEnd && Character.isWhitespace(text.charAt(end - 1))) {
				end--;
			}
			int i = keyEnd;
			while (i < end && Character.isWhitespace(text.charAt(i)) && text.charAt(i) != ':') {
				i++;
			}
			boolean sawColon = i < end && text.charAt(i) == ':';
			if (sawColon) {
				i++;
				while (i < end && Character.isWhitespace(text.charAt(i))) {
					i++;
				}
			}
			this.colon = sawColon || (keyEnd < text.length() && text.charAt(keyEnd) == ':');
			this.hasSeparator = directive.hasSeparator();
			this.valueStart = Math.min(i, end);
			this.valueEnd = end;
			this.lineStart = lineStartOf(text, at);
			this.lineEnd = lineEndOf(text, at);
			this.nextLine = nextLineStart(text, lineEnd);
			this.ownsLine = text.substring(lineStart, segmentStart).isBlank() && text.substring(segmentEnd, lineEnd).isBlank();
		}

		Edit valueReplacement(String value) {
			if (!hasSeparator) {
				return new Edit(keyEnd, keyEnd, ": " + value);
			}
			if (valueStart == valueEnd && valueStart == keyEnd + (colon ? 1 : 0) && colon) {
				return new Edit(valueStart, valueEnd, " " + value); // "@key:" with nothing after the colon
			}
			return new Edit(valueStart, valueEnd, value);
		}

		Edit removal() {
			if (ownsLine) {
				return new Edit(lineStart, nextLine, "");
			}
			return new Edit(at, valueEnd, "");
		}

		/** The text from the line's start to the {@code '@'}: the comment marker and indentation to reuse for a new line. */
		String prefix() {
			return text.substring(lineStart, at);
		}
	}

	private static List<Occurrence> occurrences(String text) {
		List<Occurrence> found = new ArrayList<>();
		try {
			for (SqlToken token : SqlTokenizer.tokenize(text)) {
				if (token.getType() == SqlTokenType.LINE_COMMENT) {
					addIfDirective(found, text, token.getText(), token.getStart());
				} else if (token.getType() == SqlTokenType.BLOCK_COMMENT) {
					String body = token.getText();
					int bodyStart = token.getStart();
					if (body.startsWith("/*")) {
						body = body.substring(2);
						bodyStart += 2;
					}
					if (body.endsWith("*/")) {
						body = body.substring(0, body.length() - 2);
					}
					int offset = bodyStart;
					int i = 0;
					while (i <= body.length()) {
						int lineBreak = nextBreak(body, i);
						String inner = body.substring(i, lineBreak);
						addIfDirective(found, text, inner, offset + i);
						if (lineBreak >= body.length()) {
							break;
						}
						int breakLength = body.startsWith("\r\n", lineBreak) ? 2 : 1;
						i = lineBreak + breakLength;
					}
				}
			}
		} catch (SqlLexException e) {
			// The parser's own fallback: every physical line.
			found.clear();
			int start = 0;
			while (start <= text.length()) {
				int end = lineEndOf(text, start);
				addIfDirective(found, text, text.substring(start, end), start);
				if (end >= text.length()) {
					break;
				}
				start = nextLineStart(text, end);
			}
		}
		return found;
	}

	private static void addIfDirective(List<Occurrence> found, String text, String segment, int segmentStart) {
		EntryMetadata.DirectiveLine directive = EntryMetadata.analyzeDirectiveLine(segment);
		if (directive != null) {
			found.add(new Occurrence(text, segment, segmentStart, directive));
		}
	}

	// ------------------------------------------------------------------
	// Inserting a missing directive
	// ------------------------------------------------------------------

	private static Edit insertion(String text, List<Occurrence> all, String key, String value) {
		String lineBreak = text.contains("\r\n") ? "\r\n" : "\n";
		int headerEnd = headerEndOffset(text);
		Occurrence last = null;
		for (Occurrence o : all) {
			if (o.at < headerEnd) {
				last = o;
			}
		}
		if (last == null) {
			return new Edit(0, 0, "-- @" + key + ": " + value + lineBreak);
		}
		String line = last.ownsLine ? last.prefix() + "@" + key + (last.colon ? ": " : " ") + value : "-- @" + key + ": " + value;
		if (last.lineEnd == text.length()) {
			return new Edit(text.length(), text.length(), lineBreak + line);
		}
		return new Edit(last.nextLine, last.nextLine, line + lineBreak);
	}

	/** The offset where the leading comment header ends ({@link EntryMetadata#extractHeaderLines}' boundary). */
	private static int headerEndOffset(String text) {
		int headerLines = EntryMetadata.extractHeaderLines(text).size();
		int offset = 0;
		for (int i = 0; i < headerLines && offset < text.length(); i++) {
			offset = nextLineStart(text, lineEndOf(text, offset));
		}
		return offset;
	}

	// ------------------------------------------------------------------
	// Text helpers
	// ------------------------------------------------------------------

	private record Edit(int start, int end, String replacement) {
	}

	/** Applies non-overlapping edits from the end of the text backwards, so earlier offsets stay valid. */
	private static String applyEdits(String text, List<Edit> edits) {
		List<Edit> ordered = new ArrayList<>(edits);
		ordered.sort((a, b) -> Integer.compare(b.start, a.start));
		StringBuilder result = new StringBuilder(text);
		for (Edit edit : ordered) {
			result.replace(edit.start, edit.end, edit.replacement);
		}
		return result.toString();
	}

	private static int nextBreak(String s, int from) {
		for (int i = from; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\n' || c == '\r') {
				return i;
			}
		}
		return s.length();
	}

	private static int lineStartOf(String text, int offset) {
		int i = Math.min(offset, text.length());
		while (i > 0 && text.charAt(i - 1) != '\n' && text.charAt(i - 1) != '\r') {
			i--;
		}
		return i;
	}

	private static int lineEndOf(String text, int offset) {
		return nextBreak(text, offset);
	}

	private static int nextLineStart(String text, int lineEnd) {
		if (lineEnd >= text.length()) {
			return text.length();
		}
		return text.startsWith("\r\n", lineEnd) ? lineEnd + 2 : lineEnd + 1;
	}
}
