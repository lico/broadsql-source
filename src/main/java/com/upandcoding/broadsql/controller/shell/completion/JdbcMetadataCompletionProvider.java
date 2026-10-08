package com.upandcoding.broadsql.controller.shell.completion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.completion.JdbcMetadataCompletionCache.TableEntry;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidateType;
import com.upandcoding.broadsql.controller.shell.sql.SqlKeywords;
import com.upandcoding.broadsql.controller.shell.sql.SqlToken;
import com.upandcoding.broadsql.controller.shell.sql.SqlTokenType;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.model.metadata.ColumnMetadata;
import com.upandcoding.broadsql.dao.model.metadata.SchemaMetadata;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

/**
 * Schema/table/view/column completion via JDBC {@code DatabaseMetaData} - SPRINT 0917-02, sections
 * 8/9/10/11/24/25/35. Reuses {@link DatabaseConnection}'s own existing metadata accessors
 * ({@link DatabaseConnection#getSchemas}, {@link DatabaseConnection#getTables},
 * {@link DatabaseConnection#getViews}, {@link DatabaseConnection#getColumns} - already a thin,
 * vendor-neutral wrapper over {@code DatabaseMetaData}, shared with {@code SHOW TABLES}/{@code SHOW
 * COLUMN}/{@code DESCR}/etc.) rather than issuing any new/vendor-specific SQL (section 24).
 *
 * <p>Column completion resolves a table reference two ways, in order (section 10):
 * <ol>
 * <li>qualified - {@code alias.} or {@code table.}, found by scanning the whole current statement's
 * {@code FROM}/{@code JOIN}/{@code UPDATE}/{@code INTO} clauses for a table-alias pair;</li>
 * <li>unqualified - only when exactly one table is referenced anywhere in the statement (deliberately
 * conservative: with zero or several candidate tables, no guess is made - section 10's own "when the
 * referenced table(s) can reasonably be determined").</li>
 * </ol>
 */
public final class JdbcMetadataCompletionProvider implements CompletionProvider {

	private final DatabaseConnection sqlDatabase;
	private final JdbcMetadataCompletionCache cache;

	public JdbcMetadataCompletionProvider(DatabaseConnection sqlDatabase, JdbcMetadataCompletionCache cache) {
		this.sqlDatabase = sqlDatabase;
		this.cache = cache;
	}

	@Override
	public List<CompletionCandidate> complete(CompletionContext context) {
		if (!context.isConnected() || sqlDatabase == null) {
			return List.of();
		}
		String platformId = platformId();
		if (platformId == null) {
			return List.of();
		}

		String qualifier = context.getQualifier();
		if (qualifier != null) {
			return completeQualified(context, platformId, qualifier);
		}

		SqlToken prev = lastSignificantToken(context.getStatementTokensBeforeCursor());
		if (isTablePosition(prev)) {
			return completeTablePosition(context, platformId);
		}
		if (isColumnPosition(prev)) {
			return completeUnqualifiedColumns(context, platformId);
		}
		return List.of();
	}

	// -------------------------------------------------------------------------------------------
	// Position classification
	// -------------------------------------------------------------------------------------------

	private boolean isTablePosition(SqlToken prev) {
		if (prev == null || !prev.isType(SqlTokenType.WORD)) {
			return false;
		}
		return prev.is("FROM") || prev.is("JOIN") || prev.is("INTO") || prev.is("UPDATE");
	}

	private boolean isColumnPosition(SqlToken prev) {
		if (prev == null) {
			return false;
		}
		if (prev.isType(SqlTokenType.PUNCTUATION) && (",".equals(prev.getText()) || "(".equals(prev.getText()))) {
			return true;
		}
		if (!prev.isType(SqlTokenType.WORD)) {
			return false;
		}
		return prev.is("SELECT") || prev.is("WHERE") || prev.is("AND") || prev.is("OR") || prev.is("ON") || prev.is("SET") || prev.is("BY");
	}

	private static SqlToken lastSignificantToken(List<SqlToken> tokens) {
		for (int i = tokens.size() - 1; i >= 0; i--) {
			SqlToken token = tokens.get(i);
			if (!token.isInsignificant()) {
				return token;
			}
		}
		return null;
	}

	// -------------------------------------------------------------------------------------------
	// Table / schema completion
	// -------------------------------------------------------------------------------------------

	private List<CompletionCandidate> completeTablePosition(CompletionContext context, String platformId) {
		String word = context.getWord();
		List<CompletionCandidate> result = new ArrayList<>();
		for (String schema : loadSchemas(platformId)) {
			if (startsWith(schema, word)) {
				result.add(new CompletionCandidate(schema, schema, "schema", CompletionCandidateType.SCHEMA));
			}
		}
		for (TableEntry entry : loadTables(platformId, "")) {
			if (startsWith(entry.getName(), word)) {
				result.add(new CompletionCandidate(entry.getName(), entry.getName(), entry.isView() ? "view" : "table",
						entry.isView() ? CompletionCandidateType.VIEW : CompletionCandidateType.TABLE));
			}
		}
		return result;
	}

	private List<CompletionCandidate> completeQualified(CompletionContext context, String platformId, String qualifier) {
		String word = context.getWord();

		String aliasTable = resolveAlias(context, qualifier);
		if (aliasTable != null) {
			return columnCandidates(platformId, aliasTable, word);
		}

		// Not a known alias in this statement - treat the qualifier as a schema name (section 9).
		List<CompletionCandidate> result = new ArrayList<>();
		for (TableEntry entry : loadTables(platformId, qualifier.toUpperCase(Locale.ROOT))) {
			if (startsWith(entry.getName(), word)) {
				result.add(new CompletionCandidate(entry.getName(), entry.getName(), entry.isView() ? "view" : "table",
						entry.isView() ? CompletionCandidateType.VIEW : CompletionCandidateType.TABLE));
			}
		}
		return result;
	}

	// -------------------------------------------------------------------------------------------
	// Column completion
	// -------------------------------------------------------------------------------------------

	private List<CompletionCandidate> completeUnqualifiedColumns(CompletionContext context, String platformId) {
		Map<String, String> references = tableReferences(context.getTokens());
		Set<String> distinctTables = new java.util.LinkedHashSet<>(references.values());
		if (distinctTables.size() != 1) {
			return List.of(); // zero or ambiguous - section 10: only guess when reasonably determined
		}
		return columnCandidates(platformId, distinctTables.iterator().next(), context.getWord());
	}

	private List<CompletionCandidate> columnCandidates(String platformId, String tableName, String word) {
		List<CompletionCandidate> result = new ArrayList<>();
		for (String column : loadColumns(platformId, tableName)) {
			if (startsWith(column, word)) {
				result.add(new CompletionCandidate(column, column, "column of " + tableName, CompletionCandidateType.COLUMN));
			}
		}
		return result;
	}

	/** @return the table name a {@code qualifier} resolves to as an alias in the current statement, or {@code null} if it is not a known alias. */
	private String resolveAlias(CompletionContext context, String qualifier) {
		Map<String, String> references = tableReferences(context.getTokens());
		return references.get(qualifier.toUpperCase(Locale.ROOT));
	}

	/**
	 * Scans every {@code FROM}/{@code JOIN}/{@code UPDATE}/{@code INTO} clause of the whole token
	 * stream (not just before the cursor - section 16's "the referenced table may be typed after the
	 * cursor" case, e.g. completing mid-statement) for a table reference and its optional alias.
	 *
	 * @return every table/alias name (upper-cased) mapped to the table's real name - both the table's
	 *         own name and, when present, its alias map to the same table, so a lookup never needs to
	 *         know in advance which one it was given.
	 */
	private Map<String, String> tableReferences(List<SqlToken> allTokens) {
		List<SqlToken> significant = new ArrayList<>();
		for (SqlToken token : allTokens) {
			if (!token.isInsignificant()) {
				significant.add(token);
			}
		}
		Map<String, String> references = new LinkedHashMap<>();
		for (int i = 0; i < significant.size(); i++) {
			SqlToken token = significant.get(i);
			if (!token.isType(SqlTokenType.WORD)) {
				continue;
			}
			boolean introducesTable = token.is("FROM") || token.is("JOIN") || token.is("UPDATE")
					|| (token.is("INTO") && i > 0 && significant.get(i - 1).is("INSERT"));
			if (!introducesTable || i + 1 >= significant.size()) {
				continue;
			}
			SqlToken firstToken = significant.get(i + 1);
			if (!firstToken.isType(SqlTokenType.WORD)) {
				continue;
			}

			// "FROM schema.table" (section 9) must not be mistaken for "FROM <table literally named
			// 'schema'>" - when a "." immediately follows, the real table is the word after the dot.
			String tableName;
			int afterTableIndex;
			if (i + 3 < significant.size() && significant.get(i + 2).isType(SqlTokenType.PUNCTUATION) && ".".equals(significant.get(i + 2).getText())
					&& significant.get(i + 3).isType(SqlTokenType.WORD)) {
				tableName = significant.get(i + 3).getText();
				afterTableIndex = i + 4;
			} else {
				tableName = firstToken.getText();
				afterTableIndex = i + 2;
			}
			references.put(tableName.toUpperCase(Locale.ROOT), tableName);

			int aliasIndex = afterTableIndex;
			if (aliasIndex < significant.size() && significant.get(aliasIndex).is("AS")) {
				aliasIndex++;
			}
			if (aliasIndex < significant.size()) {
				SqlToken aliasToken = significant.get(aliasIndex);
				if (aliasToken.isType(SqlTokenType.WORD) && !SqlKeywords.ALL.contains(aliasToken.getText().toUpperCase(Locale.ROOT))) {
					references.put(aliasToken.getText().toUpperCase(Locale.ROOT), tableName);
				}
			}
		}
		return references;
	}

	// -------------------------------------------------------------------------------------------
	// Cache-backed metadata loading - section 27: any JDBC failure degrades to "no candidates", never an error.
	// -------------------------------------------------------------------------------------------

	private List<String> loadSchemas(String platformId) {
		return cache.schemas(platformId, () -> {
			try {
				List<String> names = new ArrayList<>();
				for (SchemaMetadata schema : sqlDatabase.getSchemas(null)) {
					names.add(schema.getName());
				}
				return names;
			} catch (BroadSQLException | RuntimeException e) {
				return List.of();
			}
		});
	}

	private List<TableEntry> loadTables(String platformId, String schemaKey) {
		String schemaPattern = schemaKey.isEmpty() ? null : schemaKey;
		return cache.tables(platformId, schemaKey, () -> {
			try {
				List<TableEntry> entries = new ArrayList<>();
				for (TableMetadata table : sqlDatabase.getTables(null, schemaPattern, null)) {
					entries.add(new TableEntry(table.getName(), false));
				}
				for (TableMetadata view : sqlDatabase.getViews(null, schemaPattern, null)) {
					entries.add(new TableEntry(view.getName(), true));
				}
				return entries;
			} catch (BroadSQLException | RuntimeException e) {
				return List.of();
			}
		});
	}

	private List<String> loadColumns(String platformId, String tableName) {
		return cache.columns(platformId, tableName, () -> {
			try {
				List<String> names = new ArrayList<>();
				// Matches CommandDescr's own existing convention: an unquoted identifier is stored
				// upper-cased by every DBMS this metadata path is actually exercised against (H2, Oracle,
				// ...), so the JDBC lookup pattern itself must be upper-cased too, even though the table
				// name as the user actually typed it (used for the cache key and the alias map) is not.
				for (ColumnMetadata column : sqlDatabase.getColumns(null, null, tableName.toUpperCase(Locale.ROOT), null)) {
					names.add(column.getName());
				}
				return names;
			} catch (BroadSQLException | RuntimeException e) {
				return List.of();
			}
		});
	}

	private String platformId() {
		try {
			return sqlDatabase.isConnected() && sqlDatabase.getPlatform() != null ? sqlDatabase.getPlatform().getId() : null;
		} catch (BroadSQLException | RuntimeException e) {
			return null;
		}
	}

	private static boolean startsWith(String value, String prefix) {
		return value != null && value.regionMatches(true, 0, prefix, 0, prefix.length());
	}
}
