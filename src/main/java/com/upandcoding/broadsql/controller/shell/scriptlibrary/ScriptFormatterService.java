package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandCategoryCatalog;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.scripts.StatementSplitter;
import com.upandcoding.broadsql.controller.shell.sql.SqlFormatResult;
import com.upandcoding.broadsql.controller.shell.sql.SqlFormatterService;

/**
 * {@code Format Document} / {@code Format Selection} (SPRINT 0917-01, spec section 6.13/20; reshaped by
 * SPRINT 1909S). A Script may hold one SQL statement, several, BroadSQL commands, or a mixture, so the
 * whole text is never handed to the SQL formatter. Instead:
 * <ol>
 * <li>the text is split with {@link StatementSplitter#splitSpans}, the very statement model the executor
 * uses, so the formatter sees exactly the statements that will run;</li>
 * <li>a statement that is a BroadSQL command ({@code @...}, or whose first word is the first word of a
 * registered command keyword; a deliberately conservative rule) is left <b>byte-for-byte untouched</b>;</li>
 * <li>every other statement is reformatted via {@link SqlFormatterService} only if it accepts it,
 * otherwise left unchanged and reported in {@link FormatOutcome#notes()};</li>
 * <li>everything between statements (separators, comments, blank lines) is copied from the original text
 * unchanged;</li>
 * <li>if a quote or block comment is left open anywhere, statement boundaries cannot be trusted and the
 * whole text is declined, unchanged, with the reason.</li>
 * </ol>
 * Formatting is always user-triggered (never automatic on save) and never itself saves anything.
 */
public final class ScriptFormatterService {

	private static final Set<String> COMMAND_FIRST_WORDS = computeCommandFirstWords();

	/** Formats a whole Script: the metadata header is set aside and re-prepended untouched. */
	public FormatOutcome format(String content) {
		ScriptMetadataHeader header = ScriptMetadataHeader.parse(content);
		String headerText = header.toHeaderText();
		String body = EntryMetadata.stripHeader(content);
		if (!content.endsWith("\n") && body.endsWith("\n")) {
			body = body.substring(0, body.length() - 1); // stripHeader terminates every line; do not invent a trailing newline
		}
		FormatOutcome outcome = formatText(body);
		if (!outcome.isSupported()) {
			return outcome;
		}
		return FormatOutcome.success(headerText + outcome.formattedContent(), outcome.notes());
	}

	/** Formats a selected fragment with the same rules as {@link #format}; no header handling. */
	public FormatOutcome formatSelection(String selection) {
		return formatText(selection);
	}

	private FormatOutcome formatText(String text) {
		StatementSplitter.Result split = StatementSplitter.splitSpans(text);
		if (!split.isTerminated()) {
			return FormatOutcome.unsupported("Cannot safely format the current script (" + split.describeUnterminated() + "). Current script unchanged.");
		}
		List<String> notes = new ArrayList<>();
		StringBuilder rebuilt = new StringBuilder();
		int cursor = 0;
		for (StatementSplitter.Span span : split.getSpans()) {
			rebuilt.append(text, cursor, span.getStart()); // separators, comments, blank lines between statements
			String original = text.substring(span.getStart(), span.getEnd());
			if (isBroadSqlCommand(span.getText())) {
				rebuilt.append(original);
			} else {
				SqlFormatResult result = SqlFormatterService.format(original);
				if (result.isSupported()) {
					rebuilt.append(result.getFormattedSql());
				} else {
					rebuilt.append(original);
					notes.add("A statement was left unchanged: " + result.getReason());
				}
			}
			cursor = span.getEnd();
		}
		rebuilt.append(text, cursor, text.length());
		return FormatOutcome.success(rebuilt.toString(), notes);
	}

	/**
	 * {@code true} for a BroadSQL command: {@code @...}, or a first word that is the first word of a registered
	 * command keyword ({@link CommandCategoryCatalog}, the single authoritative command vocabulary). A statement
	 * starting with such a word is preserved rather than sent to the SQL formatter, which errs on the safe side.
	 */
	static boolean isBroadSqlCommand(String statementText) {
		String trimmed = statementText.trim();
		if (trimmed.isEmpty()) {
			return false;
		}
		if (trimmed.startsWith("@")) {
			return true;
		}
		String firstWord = trimmed.split("\\s+", 2)[0].toUpperCase();
		return COMMAND_FIRST_WORDS.contains(firstWord);
	}

	/**
	 * Every registered command's first keyword token, derived from {@link CommandCategoryCatalog}. Computed
	 * once; command registration is static for the life of the JVM.
	 */
	private static Set<String> computeCommandFirstWords() {
		Set<String> words = new HashSet<>();
		for (Class<? extends Command> commandClass : CommandCategoryCatalog.registeredClasses().keySet()) {
			try {
				Command command = commandClass.getDeclaredConstructor().newInstance();
				String[] keywords = command.getKeywords();
				if (keywords == null) {
					continue;
				}
				for (String keyword : keywords) {
					String trimmed = keyword.trim();
					if (trimmed.isEmpty()) {
						continue;
					}
					words.add(trimmed.split("\\s+", 2)[0].toUpperCase());
				}
			} catch (ReflectiveOperationException ignored) {
				// Every class in CommandCategoryCatalog is a registered, instantiable Command (enforced by
				// TestCommandCategoryCatalog) - unreachable in practice, skipped defensively.
			}
		}
		return Set.copyOf(words);
	}
}
