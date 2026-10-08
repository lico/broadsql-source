package com.upandcoding.broadsql.dao.metadata;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.dao.model.metadata.ColumnMetadata;
import com.upandcoding.broadsql.dao.model.metadata.ForeignKeyMetadata;
import com.upandcoding.broadsql.dao.model.metadata.IndexMetadata;
import com.upandcoding.broadsql.dao.model.metadata.PrimaryKeyMetadata;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

/**
 * SPRINT 2409K: the one normalized, vendor-neutral source of table structure metadata for
 * {@code DESCR}, {@code SHOW PK}, {@code SHOW FK}, {@code SHOW REFERENCES}, {@code SHOW INDEXES},
 * {@code COMPARE TABLE STRUCTURE}, {@code FIND FK} and {@code FIND INDEX}. Everything comes from JDBC
 * {@link DatabaseMetaData}; no vendor catalog is queried.
 *
 * <p><b>Table resolution</b> ({@link #resolveTable}) is the one canonical resolver of every command that
 * names exactly one existing table: a name may be qualified ({@code SCHEMA.TABLE}); an unqualified name is
 * looked up in the connection's current schema (and current catalog). The name is tried as typed, then
 * upper-cased, then lower-cased; the first variant naming an existing table or view wins. Matching is exact
 * (the JDBC pattern characters {@code _}/{@code %} are escaped and results are re-checked, so
 * {@code FOO_BAR} never matches {@code FOOXBAR} and {@code TRY} never matches {@code COUNTRY}), unlike
 * {@code DatabaseConnection.existsTable}, which is a substring search kept for discovery commands. For a
 * driver that qualifies tables by catalog rather than schema (MySQL), the qualifier is taken as the catalog,
 * decided from {@link DatabaseMetaData#supportsSchemasInTableDefinitions()}.
 *
 * <p><b>Outcomes</b> are kept distinct: {@link MetadataException.Kind#INVALID_NAME},
 * {@link MetadataException.Kind#NOT_FOUND},
 * {@link MetadataException.Kind#AMBIGUOUS}, {@link MetadataException.Kind#UNSUPPORTED} (the driver throws
 * {@link SQLFeatureNotSupportedException} or does not implement the method) and
 * {@link MetadataException.Kind#RETRIEVAL_FAILED}; an existing table with nothing to report is an empty
 * list.
 *
 * <p><b>Ordering</b> never relies on the driver: columns by ordinal position, foreign keys by (table,
 * constraint name, key sequence), indexes by (table, index name, ordinal position).
 *
 * <p><b>FIND</b> ({@link #findForeignKeys}, {@link #findIndexes}) reuses exactly the per-table calls of the
 * SHOW methods over every table of the current schema, then keeps each foreign key/index having any part
 * that matches the search text. The search text follows {@code FIND COLUMN}'s convention: a substring, in
 * which {@code %} and {@code _} keep their SQL {@code LIKE} meaning; matching ignores case.
 */
public class MetadataService {

	private final Connection connection;

	public MetadataService(Connection connection) {
		this.connection = connection;
	}

	/** Functional shape of one JDBC metadata call, so every call gets the same error classification. */
	private interface MetadataCall<T> {
		T run(DatabaseMetaData dbmd) throws SQLException, MetadataException;
	}

	private <T> T call(String what, MetadataCall<T> body) throws MetadataException {
		if (connection == null) {
			throw new MetadataException(MetadataException.Kind.RETRIEVAL_FAILED, "Not connected to a database");
		}
		try {
			return body.run(connection.getMetaData());
		} catch (SQLFeatureNotSupportedException | UnsupportedOperationException | AbstractMethodError e) {
			throw new MetadataException(MetadataException.Kind.UNSUPPORTED, "The JDBC driver" + driverName() + " does not provide " + what + " metadata");
		} catch (SQLException e) {
			throw new MetadataException(MetadataException.Kind.RETRIEVAL_FAILED, "Unable to read " + what + " metadata: " + e.getLocalizedMessage());
		}
	}

	private String driverName() {
		try {
			return " (" + connection.getMetaData().getDriverName() + ")";
		} catch (SQLException | RuntimeException e) {
			return "";
		}
	}

	// ---------------------------------------------------------------- table resolution

	/**
	 * Resolves a user-typed table name to exactly one existing table or view (see the class comment).
	 *
	 * @throws MetadataException {@code NOT_FOUND} or {@code AMBIGUOUS}, or a driver failure;
	 *                           {@code INVALID_NAME} for a blank name or an empty schema/table part
	 */
	public TableMetadata resolveTable(String userName) throws MetadataException {
		String name = StringUtils.trimToEmpty(userName);
		if (name.isEmpty()) {
			throw new MetadataException(MetadataException.Kind.INVALID_NAME, "A table name is required");
		}
		String qualifier = name.contains(".") ? StringUtils.trimToNull(StringUtils.substringBeforeLast(name, ".")) : null;
		String table = name.contains(".") ? StringUtils.trim(StringUtils.substringAfterLast(name, ".")) : name;
		if (StringUtils.isBlank(table) || (name.contains(".") && qualifier == null)) {
			throw new MetadataException(MetadataException.Kind.INVALID_NAME,
					"Invalid table name '" + name + "': expected TABLE or SCHEMA.TABLE");
		}
		return call("table", dbmd -> {
			boolean qualifierIsCatalog = qualifier != null && !dbmd.supportsSchemasInTableDefinitions() && dbmd.supportsCatalogsInTableDefinitions();
			String catalog;
			String schema;
			if (qualifier == null) {
				catalog = connection.getCatalog();
				schema = connection.getSchema();
			} else if (qualifierIsCatalog) {
				catalog = qualifier;
				schema = null;
			} else {
				catalog = null;
				schema = qualifier;
			}
			String[][] variants = { { catalog, schema, table }, { upper(catalog, qualifierIsCatalog), upper(schema, qualifier != null), table.toUpperCase(Locale.ROOT) },
					{ lower(catalog, qualifierIsCatalog), lower(schema, qualifier != null), table.toLowerCase(Locale.ROOT) } };
			Set<String> tried = new LinkedHashSet<>();
			for (String[] variant : variants) {
				if (!tried.add(variant[0] + "|" + variant[1] + "|" + variant[2])) {
					continue;
				}
				List<TableMetadata> matches = exactTables(dbmd, variant[0], variant[1], variant[2]);
				if (matches.size() == 1) {
					return matches.get(0);
				}
				if (matches.size() > 1) {
					List<String> places = new ArrayList<>();
					for (TableMetadata match : matches) {
						places.add(qualified(match.getCatalog(), match.getSchema(), match.getName()));
					}
					throw new MetadataException(MetadataException.Kind.AMBIGUOUS,
							"Table name '" + name + "' is ambiguous, it matches " + String.join(", ", places) + ". Qualify it with its schema.");
				}
			}
			throw new MetadataException(MetadataException.Kind.NOT_FOUND, "Table name '" + name + "' does not exist");
		});
	}

	/**
	 * The SQL reference to a table {@link #resolveTable} returned, quoted component by component for this driver
	 * ({@link SqlIdentifiers#qualifiedName}). Generated SQL that reads a resolved table uses this reference, never
	 * the name as typed, so the database reads exactly the table the lookup found.
	 */
	public String sqlName(TableMetadata table) throws MetadataException {
		return call("table", dbmd -> SqlIdentifiers.qualifiedName(dbmd, table));
	}

	private static String upper(String value, boolean vary) {
		return value == null || !vary ? value : value.toUpperCase(Locale.ROOT);
	}

	private static String lower(String value, boolean vary) {
		return value == null || !vary ? value : value.toLowerCase(Locale.ROOT);
	}

	private static String qualified(String catalog, String schema, String table) {
		String owner = StringUtils.isNotBlank(schema) ? schema : catalog;
		return StringUtils.isNotBlank(owner) ? owner + "." + table : table;
	}

	private List<TableMetadata> exactTables(DatabaseMetaData dbmd, String catalog, String schema, String table) throws SQLException {
		String escape = dbmd.getSearchStringEscape();
		List<TableMetadata> result = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		try (ResultSet rs = dbmd.getTables(catalog, escapePattern(schema, escape), escapePattern(table, escape), null)) {
			while (rs.next()) {
				String foundName = rs.getString("TABLE_NAME");
				String foundSchema = rs.getString("TABLE_SCHEM");
				String foundCatalog = rs.getString("TABLE_CAT");
				if (!table.equals(foundName) || (schema != null && foundSchema != null && !schema.equals(foundSchema))) {
					continue;
				}
				if (seen.add(foundCatalog + "|" + foundSchema + "|" + foundName)) {
					TableMetadata t = new TableMetadata();
					t.setCatalog(foundCatalog);
					t.setSchema(foundSchema);
					t.setName(foundName);
					t.setType(rs.getString("TABLE_TYPE"));
					result.add(t);
				}
			}
		}
		return result;
	}

	static String escapePattern(String value, String escape) {
		if (value == null || StringUtils.isEmpty(escape)) {
			return value;
		}
		StringBuilder sb = new StringBuilder(value.length() + 4);
		for (char c : value.toCharArray()) {
			if (c == '_' || c == '%' || escape.indexOf(c) >= 0) {
				sb.append(escape);
			}
			sb.append(c);
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------- SHOW

	/** The columns of {@code table}, in ordinal order. */
	public List<ColumnMetadata> columns(TableMetadata table) throws MetadataException {
		return call("column", dbmd -> {
			String escape = dbmd.getSearchStringEscape();
			List<ColumnMetadata> result = new ArrayList<>();
			try (ResultSet rs = dbmd.getColumns(table.getCatalog(), escapePattern(table.getSchema(), escape), escapePattern(table.getName(), escape), "%")) {
				while (rs.next()) {
					if (!table.getName().equals(rs.getString("TABLE_NAME"))) {
						continue;
					}
					result.add(toColumn(rs));
				}
			}
			result.sort(Comparator.comparingInt(ColumnMetadata::getIndexInTable));
			return result;
		});
	}

	/**
	 * Maps one {@link DatabaseMetaData#getColumns} row. Shared with {@code DatabaseConnection.getColumns}
	 * ({@code DESCR}, {@code FIND COLUMN}) so both read column metadata identically.
	 */
	public static ColumnMetadata toColumn(ResultSet rs) throws SQLException {
		ColumnMetadata column = new ColumnMetadata();
		column.setName(rs.getString("COLUMN_NAME"));
		column.setTable(rs.getString("TABLE_NAME"));
		column.setCatalog(rs.getString("TABLE_CAT"));
		column.setSchema(rs.getString("TABLE_SCHEM"));
		column.setDataType(rs.getInt("DATA_TYPE"));
		column.setTypeName(rs.getString("TYPE_NAME"));
		column.setColumnSize(rs.getInt("COLUMN_SIZE"));
		column.setDecimalDigit(rs.getInt("DECIMAL_DIGITS"));
		column.setNumPrecRadix(rs.getInt("NUM_PREC_RADIX"));
		column.setNullable(rs.getInt("NULLABLE"));
		column.setRemarks(rs.getString("REMARKS"));
		column.setDefaultValue(rs.getString("COLUMN_DEF"));
		column.setIndexInTable(rs.getInt("ORDINAL_POSITION"));
		column.setIsoNullable(rs.getString("IS_NULLABLE"));
		// IS_AUTOINCREMENT not found in MySQL databases; IS_GENERATEDCOLUMN not found in H2
		return column;
	}

	/** The primary key columns of {@code table}, in key order (empty when the table has no primary key). */
	public List<PrimaryKeyMetadata> primaryKeys(TableMetadata table) throws MetadataException {
		return call("primary key", dbmd -> {
			List<PrimaryKeyMetadata> result = new ArrayList<>();
			try (ResultSet rs = dbmd.getPrimaryKeys(table.getCatalog(), table.getSchema(), table.getName())) {
				while (rs.next()) {
					PrimaryKeyMetadata key = new PrimaryKeyMetadata();
					key.setName(rs.getString("COLUMN_NAME"));
					key.setTable(rs.getString("TABLE_NAME"));
					key.setCatalog(rs.getString("TABLE_CAT"));
					key.setSchema(rs.getString("TABLE_SCHEM"));
					key.setPkName(rs.getString("PK_NAME"));
					key.setKeySec(rs.getInt("KEY_SEQ"));
					result.add(key);
				}
			}
			result.sort(Comparator.comparingInt(PrimaryKeyMetadata::getKeySec));
			return result;
		});
	}

	/** Foreign keys declared by {@code table} (what it references). */
	public List<ForeignKeyMetadata> foreignKeys(TableMetadata table) throws MetadataException {
		return call("foreign key", dbmd -> readKeys(dbmd.getImportedKeys(table.getCatalog(), table.getSchema(), table.getName())));
	}

	/** Foreign keys declared by other tables (or itself) that reference {@code table}. */
	public List<ForeignKeyMetadata> references(TableMetadata table) throws MetadataException {
		return call("foreign key", dbmd -> readKeys(dbmd.getExportedKeys(table.getCatalog(), table.getSchema(), table.getName())));
	}

	private static List<ForeignKeyMetadata> readKeys(ResultSet rs) throws SQLException {
		List<ForeignKeyMetadata> result = new ArrayList<>();
		try (rs) {
			while (rs.next()) {
				result.add(new ForeignKeyMetadata(rs.getString("FK_NAME"), rs.getString("FKTABLE_CAT"), rs.getString("FKTABLE_SCHEM"), rs.getString("FKTABLE_NAME"),
						rs.getString("FKCOLUMN_NAME"), rs.getString("PKTABLE_CAT"), rs.getString("PKTABLE_SCHEM"), rs.getString("PKTABLE_NAME"),
						rs.getString("PKCOLUMN_NAME"), rs.getInt("KEY_SEQ")));
			}
		}
		result.sort(FK_ORDER);
		return result;
	}

	private static final Comparator<String> TEXT = Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER);

	static final Comparator<ForeignKeyMetadata> FK_ORDER = Comparator.comparing(ForeignKeyMetadata::getFkSchema, TEXT)
			.thenComparing(ForeignKeyMetadata::getFkTable, TEXT).thenComparing(ForeignKeyMetadata::getFkName, TEXT)
			.thenComparing(ForeignKeyMetadata::getPkTable, TEXT).thenComparingInt(ForeignKeyMetadata::getKeySeq);

	static final Comparator<IndexMetadata> INDEX_ORDER = Comparator.comparing(IndexMetadata::getSchema, TEXT)
			.thenComparing(IndexMetadata::getTable, TEXT).thenComparing(IndexMetadata::getIndexName, TEXT)
			.thenComparingInt(IndexMetadata::getOrdinalPosition);

	/**
	 * Real indexes of {@code table}. Rows of type {@link DatabaseMetaData#tableIndexStatistic} (table
	 * statistics some drivers return from {@code getIndexInfo}) and rows without an index name are dropped.
	 */
	public List<IndexMetadata> indexes(TableMetadata table) throws MetadataException {
		return call("index", dbmd -> {
			List<IndexMetadata> result = new ArrayList<>();
			try (ResultSet rs = dbmd.getIndexInfo(table.getCatalog(), table.getSchema(), table.getName(), false, true)) {
				while (rs.next()) {
					short type = rs.getShort("TYPE");
					String indexName = rs.getString("INDEX_NAME");
					if (type == DatabaseMetaData.tableIndexStatistic || indexName == null) {
						continue;
					}
					String column = rs.getString("COLUMN_NAME");
					result.add(new IndexMetadata(rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME"), indexName, column,
							rs.getInt("ORDINAL_POSITION"), !rs.getBoolean("NON_UNIQUE")));
				}
			}
			result.sort(INDEX_ORDER);
			return result;
		});
	}

	// ---------------------------------------------------------------- FIND

	/**
	 * The ordinary tables of the current schema (and catalog); every schema when the driver reports no
	 * current schema. Views and system tables are excluded: they carry no foreign keys or indexes of their own.
	 */
	public List<TableMetadata> tablesInScope() throws MetadataException {
		return call("table", dbmd -> {
			List<TableMetadata> result = new ArrayList<>();
			try (ResultSet rs = dbmd.getTables(connection.getCatalog(), escapePattern(connection.getSchema(), dbmd.getSearchStringEscape()), "%",
					tableTypes(dbmd, false))) {
				while (rs.next()) {
					TableMetadata t = new TableMetadata();
					t.setCatalog(rs.getString("TABLE_CAT"));
					t.setSchema(rs.getString("TABLE_SCHEM"));
					t.setName(rs.getString("TABLE_NAME"));
					t.setType(rs.getString("TABLE_TYPE"));
					result.add(t);
				}
			}
			result.sort(Comparator.comparing(TableMetadata::getSchema, TEXT).thenComparing(TableMetadata::getName, TEXT));
			return result;
		});
	}

	// ---------------------------------------------------------------- object categories

	/**
	 * The driver's table types that name real tables, the object category of {@code SHOW TABLES}, {@code LOAD}
	 * targets and {@code FIND}: every type containing {@code TABLE} ({@code TABLE}, H2's {@code BASE TABLE},
	 * PostgreSQL's {@code PARTITIONED TABLE} and {@code FOREIGN TABLE}...) and the temporary table types
	 * ({@code GLOBAL TEMPORARY}, {@code LOCAL TEMPORARY}, {@code TEMPORARY TABLE}); never a view, materialized view,
	 * synonym, alias, index, sequence, type or TOAST table, even when its type name contains {@code TABLE} or
	 * {@code TEMPORARY} (PostgreSQL's {@code TEMPORARY VIEW}, {@code SYSTEM TOAST TABLE}). {@code SYSTEM TABLE} is
	 * kept only when {@code includeSystemTables}. A driver reporting none of these types gets {@code TABLE}.
	 */
	public static String[] tableTypes(DatabaseMetaData dbmd, boolean includeSystemTables) throws SQLException {
		List<String> types = new ArrayList<>();
		for (String type : reportedTypes(dbmd)) {
			if (isTableType(type, includeSystemTables)) {
				types.add(type);
			}
		}
		return types.isEmpty() ? new String[] { "TABLE" } : types.toArray(new String[0]);
	}

	/** Whether a {@code TABLE_TYPE} value names a real table (see {@link #tableTypes}). */
	static boolean isTableType(String type, boolean includeSystemTables) {
		String upper = StringUtils.trimToEmpty(type).toUpperCase(Locale.ROOT);
		for (String excluded : new String[] { "VIEW", "SYNONYM", "ALIAS", "INDEX", "SEQUENCE", "TYPE", "TOAST" }) {
			if (upper.contains(excluded)) {
				return false;
			}
		}
		if (upper.contains("SYSTEM") && !includeSystemTables) {
			return false;
		}
		return upper.contains("TABLE") || upper.contains("TEMPORARY");
	}

	/**
	 * The driver's view types, the object category of {@code SHOW VIEWS}: {@code VIEW}, and {@code MATERIALIZED VIEW}
	 * where the driver reports it (PostgreSQL). {@code VIEW} alone when the driver reports neither.
	 */
	public static String[] viewTypes(DatabaseMetaData dbmd) throws SQLException {
		List<String> types = new ArrayList<>();
		for (String type : reportedTypes(dbmd)) {
			String upper = type.toUpperCase(Locale.ROOT);
			if (upper.equals("VIEW") || upper.equals("MATERIALIZED VIEW")) {
				types.add(type);
			}
		}
		return types.isEmpty() ? new String[] { "VIEW" } : types.toArray(new String[0]);
	}

	/** {@link DatabaseMetaData#getTableTypes()}, trimmed (some drivers pad the value). */
	private static List<String> reportedTypes(DatabaseMetaData dbmd) throws SQLException {
		List<String> types = new ArrayList<>();
		try (ResultSet rs = dbmd.getTableTypes()) {
			while (rs.next()) {
				String type = rs.getString("TABLE_TYPE");
				if (type != null) {
					types.add(type.trim());
				}
			}
		}
		return types;
	}

	/**
	 * Every column pair of each foreign key (declared in the current schema) whose constraint name,
	 * referencing table/column or referenced table/column matches {@code searchText}. A composite key
	 * that matches through one column is returned whole.
	 */
	public List<ForeignKeyMetadata> findForeignKeys(String searchText) throws MetadataException {
		Pattern pattern = searchPattern(searchText);
		Map<String, List<ForeignKeyMetadata>> byConstraint = new LinkedHashMap<>();
		for (TableMetadata table : tablesInScope()) {
			for (ForeignKeyMetadata fk : foreignKeys(table)) {
				byConstraint.computeIfAbsent(fk.constraintKey(), k -> new ArrayList<>()).add(fk);
			}
		}
		List<ForeignKeyMetadata> result = new ArrayList<>();
		for (List<ForeignKeyMetadata> constraint : byConstraint.values()) {
			boolean matches = false;
			for (ForeignKeyMetadata fk : constraint) {
				matches |= matches(pattern, fk.getFkName(), fk.getFkTable(), fk.getFkColumn(), fk.getPkTable(), fk.getPkColumn());
			}
			if (matches) {
				result.addAll(constraint);
			}
		}
		result.sort(FK_ORDER);
		return result;
	}

	/**
	 * Every column of each index (on a table of the current schema) whose name, table or one of whose
	 * columns matches {@code searchText}. A composite index that matches through one column is returned whole.
	 */
	public List<IndexMetadata> findIndexes(String searchText) throws MetadataException {
		Pattern pattern = searchPattern(searchText);
		Map<String, List<IndexMetadata>> byIndex = new LinkedHashMap<>();
		for (TableMetadata table : tablesInScope()) {
			for (IndexMetadata index : indexes(table)) {
				byIndex.computeIfAbsent(index.indexKey(), k -> new ArrayList<>()).add(index);
			}
		}
		List<IndexMetadata> result = new ArrayList<>();
		for (List<IndexMetadata> index : byIndex.values()) {
			boolean matches = false;
			for (IndexMetadata column : index) {
				matches |= matches(pattern, column.getIndexName(), column.getTable(), column.getColumn());
			}
			if (matches) {
				result.addAll(index);
			}
		}
		result.sort(INDEX_ORDER);
		return result;
	}

	/** {@code FIND COLUMN}'s convention: substring, {@code %} = any run, {@code _} = any one character; case-insensitive. */
	static Pattern searchPattern(String searchText) {
		StringBuilder regex = new StringBuilder(".*");
		for (char c : StringUtils.trimToEmpty(searchText).toCharArray()) {
			if (c == '%') {
				regex.append(".*");
			} else if (c == '_') {
				regex.append('.');
			} else {
				regex.append(Pattern.quote(String.valueOf(c)));
			}
		}
		regex.append(".*");
		return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
	}

	private static boolean matches(Pattern pattern, String... values) {
		for (String value : values) {
			if (value != null && pattern.matcher(value).matches()) {
				return true;
			}
		}
		return false;
	}
}
