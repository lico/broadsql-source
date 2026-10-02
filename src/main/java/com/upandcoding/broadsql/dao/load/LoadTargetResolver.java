package com.upandcoding.broadsql.dao.load;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

/**
 * Resolves a {@code LOAD} target ({@code TABLE} or {@code SCHEMA.TABLE}, exactly as typed by the
 * user) against real database metadata before any SQL is built from it - the safety requirement in
 * SPRINT 0912B section 5.2: identifiers must be metadata-resolved and validated, never trusted as
 * typed.
 *
 * <p>{@link DatabaseConnection#getTables} does a {@code %pattern%} substring search (it exists for
 * the interactive "find a table" use case), so it is deliberately over-inclusive here; every
 * candidate it returns is then filtered down to an exact (case-insensitive) name/schema match. Only
 * the database-echoed {@link TableMetadata#getName()}/{@link TableMetadata#getSchema()} - never the
 * user's raw input - are used to build the qualified name subsequently embedded in generated SQL.
 */
public final class LoadTargetResolver {

	private LoadTargetResolver() {
	}

	public static LoadTarget resolve(DatabaseConnection sqlDatabase, String rawTableName) throws BroadSQLException {
		String schemaPattern = CommandUtils.getSchemaName(rawTableName);
		String tableNamePattern = CommandUtils.getTableName(rawTableName);

		TreeSet<TableMetadata> candidates = sqlDatabase.getTables(null, schemaPattern, tableNamePattern);
		List<TableMetadata> exactMatches = new ArrayList<>();
		for (TableMetadata candidate : candidates) {
			boolean nameMatches = candidate.getName() != null && candidate.getName().equalsIgnoreCase(tableNamePattern);
			boolean schemaMatches = schemaPattern == null || schemaPattern.equalsIgnoreCase(candidate.getSchema());
			if (nameMatches && schemaMatches) {
				exactMatches.add(candidate);
			}
		}

		if (exactMatches.isEmpty()) {
			// getTables returns real tables only: a view of that name is not a LOAD target
			for (TableMetadata view : sqlDatabase.getViews(null, schemaPattern, tableNamePattern)) {
				if (view.getName() != null && view.getName().equalsIgnoreCase(tableNamePattern)
						&& (schemaPattern == null || schemaPattern.equalsIgnoreCase(view.getSchema()))) {
					throw new BroadSQLException("LOAD target '" + rawTableName + "' is a view, not a table. LOAD writes to tables only.");
				}
			}
			throw new BroadSQLException("LOAD target table '" + rawTableName + "' does not exist.");
		}
		if (exactMatches.size() > 1) {
			throw new BroadSQLException("LOAD target table '" + rawTableName + "' is ambiguous - it matches "
					+ exactMatches.size() + " tables across different schemas. Qualify it as SCHEMA.TABLE.");
		}

		TableMetadata resolved = exactMatches.get(0);
		String resolvedSchema = (resolved.getSchema() != null && !resolved.getSchema().isBlank()) ? resolved.getSchema() : null;
		String qualifiedName = resolvedSchema != null ? resolvedSchema + "." + resolved.getName() : resolved.getName();

		ResultSetMetaData metaData = sqlDatabase.getMetaData(qualifiedName);
		List<LoadTargetColumn> columns = new ArrayList<>();
		try {
			for (int i = 1; i <= metaData.getColumnCount(); i++) {
				columns.add(new LoadTargetColumn(
						metaData.getColumnLabel(i),
						metaData.getColumnType(i),
						metaData.isNullable(i) != ResultSetMetaData.columnNoNulls,
						metaData.isAutoIncrement(i),
						metaData.isReadOnly(i)));
			}
		} catch (SQLException se) {
			throw new BroadSQLException(se);
		}

		return new LoadTarget(resolvedSchema, resolved.getName(), columns);
	}
}
