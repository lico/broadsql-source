package com.upandcoding.broadsql.controller.shell.reader;

import java.util.List;
import java.util.function.Supplier;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.CompletingParsedLine;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.completion.CompletionContext;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEngine;
import com.upandcoding.broadsql.controller.shell.completion.EntityCompletionService;
import com.upandcoding.broadsql.controller.shell.completion.PendingStatementBufferHolder;
import com.upandcoding.broadsql.controller.shell.completion.SqlCompletionLexer;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;

/**
 * The <b>only</b> class in the completion stack that knows about JLine - a thin adapter translating a
 * JLine {@link ParsedLine} into calls to the JLine-free {@link CompletionService} (SPRINT XT02A - API
 * completion: {@code CONNECT API}/{@code RUN}/{@code SYNTAX}/{@code HELP}) and, as of SPRINT 0917-02,
 * {@link CompletionEngine} (BroadSQL commands, SQL keywords, JDBC metadata) - then translating either
 * one's {@link CompletionCandidate} results back into {@code org.jline.reader.Candidate} (section 12.2's
 * architectural boundary: "The following logic belongs outside JLine... JLine is the interactive front
 * end"). No completion logic of its own lives here.
 *
 * <p>{@link #completionService} is tried first and, when it returns anything, used exclusively - it
 * already narrowly and precisely owns the API-specific grammar ({@code CONNECT API <id>},
 * {@code RUN <method> <url>?<query>=<value>}, ...) that {@link #generalEngine} does not attempt to
 * model at all, so trying both and merging would risk diluting those precise, already-tested results
 * with irrelevant noise. {@link #generalEngine} only runs when the API service found nothing for this
 * context - zero regression risk to SPRINT XT02A's own completion behavior (section 47).
 *
 * <p>Relies on JLine's {@code DefaultParser} splitting the line on whitespace only (its default
 * behavior - BroadSQL's own {@code CommandUtils.getArgumentsFromQuery} already tokenizes the same
 * way), so a {@link ParsedLine#words()} entry is exactly one of BroadSQL's own command-line "words" -
 * {@link CompletionService} never has to know it is being driven by JLine at all.
 */
public final class BroadSqlJLineCompleter implements Completer {

	private final CompletionService completionService;
	private final ApiCatalogService catalog;
	private final Supplier<List<String>> commandKeywordsSupplier;
	private final CompletionEngine generalEngine;
	private final DatabaseConnection sqlDatabase;
	private final EntityCompletionService entityCompletion;

	public BroadSqlJLineCompleter(CompletionService completionService, ApiCatalogService catalog, Supplier<List<String>> commandKeywordsSupplier) {
		this(completionService, catalog, commandKeywordsSupplier, null, null);
	}

	/**
	 * @param generalEngine SPRINT 0917-02's BroadSQL-command/SQL-keyword/JDBC-metadata engine, or
	 *                       {@code null} to run with API completion only (e.g. existing tests that
	 *                       predate this engine)
	 * @param sqlDatabase    the live connection - used only to know whether metadata completion should
	 *                       be attempted at all (section 28); {@code null} is treated as "not connected"
	 */
	public BroadSqlJLineCompleter(CompletionService completionService, ApiCatalogService catalog, Supplier<List<String>> commandKeywordsSupplier,
			CompletionEngine generalEngine, DatabaseConnection sqlDatabase) {
		this(completionService, catalog, commandKeywordsSupplier, generalEngine, sqlDatabase, null);
	}

	/**
	 * @param entityCompletion SPRINT 2009A's stored-entity argument completion (connections, environments, database
	 *                         groups, scripts), or {@code null} to run without it
	 */
	public BroadSqlJLineCompleter(CompletionService completionService, ApiCatalogService catalog, Supplier<List<String>> commandKeywordsSupplier,
			CompletionEngine generalEngine, DatabaseConnection sqlDatabase, EntityCompletionService entityCompletion) {
		this.completionService = completionService;
		this.catalog = catalog;
		this.commandKeywordsSupplier = commandKeywordsSupplier;
		this.generalEngine = generalEngine;
		this.sqlDatabase = sqlDatabase;
		this.entityCompletion = entityCompletion;
	}

	@Override
	public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
		if (catalog != null) {
			ApiSessionContext context = ApiSessionContextHolder.get();
			String apiId = context == null ? null : context.getApiId();
			List<String> commandKeywords = commandKeywordsSupplier == null ? null : commandKeywordsSupplier.get();

			List<CompletionCandidate> apiResults = completionService.complete(line.words(), line.wordIndex(), apiId, catalog, commandKeywords);
			if (!apiResults.isEmpty()) {
				for (CompletionCandidate candidate : apiResults) {
					// complete=false: never auto-append a trailing space - a RUN alias expands to a URL the
					// user very likely wants to keep typing (adding ?query=...), and a query key/value
					// candidate already ends in "=" or is meant to be extended, never a finished token.
					candidates.add(new Candidate(candidate.getValue(), candidate.getDisplay(), null, candidate.getDescription(), null, null, false));
				}
				return;
			}
		}

		if (entityCompletion != null) {
			// SPRINT 2009A: after the API grammar (which owns CONNECT API <id> and RUN), before the general engine.
			List<CompletionCandidate> entities = entityCompletion.complete(line.words(), line.wordIndex());
			if (!entities.isEmpty()) {
				boolean quotedWord = startsWithOpeningQuote(line);
				for (CompletionCandidate candidate : entities) {
					String value = quotedWord ? unquoted(candidate.getValue()) : candidate.getValue();
					// SPRINT 2409K: a directory keeps the word open (no trailing space) so the next TAB descends into it
					boolean complete = candidate.getType() != CompletionCandidateType.DIRECTORY;
					candidates.add(new Candidate(value, candidate.getDisplay(), null, candidate.getDescription(), null, null, complete));
				}
				return;
			}
		}

		if (generalEngine == null) {
			return;
		}
		String pending = PendingStatementBufferHolder.get();
		String currentLine = line.line() == null ? "" : line.line();
		String fullBuffer;
		int cursor;
		if (pending.isEmpty()) {
			fullBuffer = currentLine;
			cursor = line.cursor();
		} else {
			String separator = pending.endsWith(" ") ? "" : " ";
			fullBuffer = pending + separator + currentLine;
			cursor = pending.length() + separator.length() + line.cursor();
		}

		CompletionContext context = SqlCompletionLexer.analyze(fullBuffer, cursor, isConnected());
		List<CompletionCandidate> results = generalEngine.complete(context);
		for (CompletionCandidate candidate : results) {
			candidates.add(new Candidate(candidate.getValue(), candidate.getDisplay(), null, candidate.getDescription(), null, null,
					autoAppendSpace(candidate.getType())));
		}
	}

	/**
	 * SPRINT 2409K: {@code true} when the word being completed was opened with {@code "} by the user. JLine's
	 * parser then gives the word without that quote and re-inserts it (plus the closing quote for a completed
	 * candidate) itself, so a candidate must not carry its own quotes too: before this, completing
	 * {@code CONNECT "My} inserted {@code ""My Conn""} (verified with a DefaultParser probe).
	 */
	static boolean startsWithOpeningQuote(ParsedLine line) {
		if (!(line instanceof CompletingParsedLine) || line.line() == null) {
			return false;
		}
		int start = line.cursor() - ((CompletingParsedLine) line).rawWordCursor();
		return start >= 0 && start < line.line().length() && line.line().charAt(start) == '"';
	}

	/** {@code "value"} or {@code "value} without its quotes; any other value unchanged. */
	static String unquoted(String value) {
		if (value == null || !value.startsWith("\"")) {
			return value;
		}
		String inner = value.substring(1);
		return inner.endsWith("\"") ? inner.substring(0, inner.length() - 1) : inner;
	}

	private boolean isConnected() {
		if (sqlDatabase == null) {
			return false;
		}
		try {
			return sqlDatabase.isConnected();
		} catch (BroadSQLException | RuntimeException e) {
			return false;
		}
	}

	/** Section 12: a completed BroadSQL/SQL keyword or a resolved table/view/column is normally followed by more typing after a space; a schema name is normally followed immediately by {@code .} instead (section 9). */
	private static boolean autoAppendSpace(CompletionCandidateType type) {
		return type != CompletionCandidateType.SCHEMA;
	}
}
