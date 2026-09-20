package com.upandcoding.broadsql.dao.compare;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.metadata.ColumnMetadata;

/**
 * Engine behind {@code COMPARE TABLE STRUCTURE <table> WITH <connection>}: resolves the same
 * table name on the current connection and on a second, named connection from the CDF vault, and
 * diffs their columns by name.
 *
 * <p>Standalone by design: opens its own, dedicated JDBC connection to the target database rather
 * than going through the {@code sqlDatabase} singleton, which only ever holds one live connection
 * at a time, the same reasoning documented on
 * {@link com.upandcoding.broadsql.dao.pull.PullToH2Exporter}. Unlike that class, the target here is not
 * restricted to H2: {@link #openConnection(DatabaseDefinition)} duplicates the Derby/H2/other driver
 * branching of {@link DatabaseConnection#openConnection(boolean)} rather than reusing it, for the
 * same reason that method's own {@code testConnectionToPlatform} duplicates it too, it is tied to
 * the singleton's own {@code this.platform} field.
 */
public class TableStructureComparer {

	private static final Logger log = LoggerFactory.getLogger(TableStructureComparer.class);

	/**
	 * Compares {@code rawTableName} between {@code source} (the current connection) and
	 * {@code target} (a connection resolved from the CDF vault). {@code rawTableName} may be
	 * schema-qualified ({@code SCHEMA.TABLE}); when it is not, each side defaults independently to
	 * its own current schema, then the name is tried as typed, upper-cased and lower-cased on each
	 * side independently (mirroring {@code DESCR}), since two different database platforms may fold
	 * unquoted identifiers to a different case.
	 *
	 * @param connectionLabel the connection identifier as the caller looked it up (e.g. the CDF key
	 *                        typed in the {@code WITH} clause), used only to phrase messages - kept
	 *                        separate from {@code target.getId()} since a caller's lookup key is not
	 *                        guaranteed to equal it
	 * @throws BroadSQLException if the table cannot be resolved on either side
	 */
	public Result compare(DatabaseConnection source, DatabaseDefinition target, String connectionLabel, String rawTableName) throws BroadSQLException {

		String schemaNamePattern = null;
		String tableNamePattern = rawTableName;
		if (rawTableName.contains(".")) {
			schemaNamePattern = StringUtils.substringBeforeLast(rawTableName, ".");
			tableNamePattern = StringUtils.substringAfterLast(rawTableName, ".");
		}
		boolean schemaWasExplicit = StringUtils.isNotBlank(schemaNamePattern);

		Connection targetConnection = openConnection(target);
		try {
			ResolvedTable sourceTable = resolveTable(source.getCurrentSchema(), schemaWasExplicit, schemaNamePattern,
					tableNamePattern, (schema, table) -> source.existsTable(schema, table));
			if (sourceTable == null) {
				throw new BroadSQLException("Table name '" + displayName(schemaWasExplicit, schemaNamePattern, tableNamePattern)
						+ "' does not exist on the current connection");
			}

			ResolvedTable targetTable = resolveTable(getCurrentSchema(targetConnection), schemaWasExplicit, schemaNamePattern,
					tableNamePattern, (schema, table) -> existsTable(targetConnection, schema, table));
			if (targetTable == null) {
				throw new BroadSQLException("Table name '" + displayName(schemaWasExplicit, schemaNamePattern, tableNamePattern)
						+ "' does not exist on connection '" + connectionLabel + "'");
			}

			TreeSet<ColumnMetadata> sourceColumns = source.getColumns(null, sourceTable.schema, sourceTable.table, null);
			TreeSet<ColumnMetadata> targetColumns = getColumns(targetConnection, targetTable.schema, targetTable.table);

			return diff(sourceColumns, targetColumns);
		} finally {
			try {
				targetConnection.close();
			} catch (SQLException e) {
				log.warn("Unable to close comparison connection to '{}': {}", target.getId(), e.getLocalizedMessage());
			}
		}
	}

	@FunctionalInterface
	private interface TableExistenceCheck {
		boolean exists(String schema, String table) throws BroadSQLException;
	}

	private static final class ResolvedTable {
		final String schema;
		final String table;

		ResolvedTable(String schema, String table) {
			this.schema = schema;
			this.table = table;
		}
	}

	private ResolvedTable resolveTable(String currentSchema, boolean schemaWasExplicit, String schemaNamePattern,
			String tableNamePattern, TableExistenceCheck check) throws BroadSQLException {
		String schema = schemaWasExplicit ? schemaNamePattern : currentSchema;
		String[] tableVariants = { tableNamePattern, tableNamePattern.toUpperCase(), tableNamePattern.toLowerCase() };
		for (String candidate : tableVariants) {
			if (check.exists(schema, candidate)) {
				return new ResolvedTable(schema, candidate);
			}
		}
		return null;
	}

	private String displayName(boolean schemaWasExplicit, String schemaNamePattern, String tableNamePattern) {
		return schemaWasExplicit ? schemaNamePattern + "." + tableNamePattern : tableNamePattern;
	}

	/**
	 * Diffs two column sets by name (case insensitively): a column present on only one side, and a
	 * column present on both sides but with a different type name, size, decimal digits or
	 * nullability, are both reported; everything else counts toward {@link Result#getIdenticalCount()}.
	 */
	private Result diff(TreeSet<ColumnMetadata> sourceColumns, TreeSet<ColumnMetadata> targetColumns) {
		TreeMap<String, ColumnMetadata> targetByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (ColumnMetadata column : targetColumns) {
			targetByName.put(column.getName(), column);
		}

		List<ColumnMetadata> onlyOnSource = new ArrayList<>();
		List<ColumnDifference> differing = new ArrayList<>();
		TreeSet<String> matchedTargetNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

		int identicalCount = 0;
		for (ColumnMetadata sourceColumn : sourceColumns) {
			ColumnMetadata targetColumn = targetByName.get(sourceColumn.getName());
			if (targetColumn == null) {
				onlyOnSource.add(sourceColumn);
			} else {
				matchedTargetNames.add(targetColumn.getName());
				if (sameDefinition(sourceColumn, targetColumn)) {
					identicalCount++;
				} else {
					differing.add(new ColumnDifference(sourceColumn, targetColumn));
				}
			}
		}

		List<ColumnMetadata> onlyOnTarget = new ArrayList<>();
		for (ColumnMetadata targetColumn : targetColumns) {
			if (!matchedTargetNames.contains(targetColumn.getName())) {
				onlyOnTarget.add(targetColumn);
			}
		}

		return new Result(onlyOnSource, onlyOnTarget, differing, identicalCount);
	}

	private boolean sameDefinition(ColumnMetadata a, ColumnMetadata b) {
		return StringUtils.equalsIgnoreCase(a.getTypeName(), b.getTypeName())
				&& a.getColumnSize() == b.getColumnSize()
				&& a.getDecimalDigit() == b.getDecimalDigit()
				&& StringUtils.equalsIgnoreCase(a.getIsoNullable(), b.getIsoNullable());
	}

	/** One column present on both sides, but with a different type, size, decimals or nullability. */
	public static final class ColumnDifference {
		private final ColumnMetadata source;
		private final ColumnMetadata target;

		private ColumnDifference(ColumnMetadata source, ColumnMetadata target) {
			this.source = source;
			this.target = target;
		}

		public ColumnMetadata getSource() {
			return source;
		}

		public ColumnMetadata getTarget() {
			return target;
		}
	}

	/** The outcome of {@link #compare(DatabaseConnection, DatabaseDefinition, String)}. */
	public static final class Result {
		private final List<ColumnMetadata> onlyOnSource;
		private final List<ColumnMetadata> onlyOnTarget;
		private final List<ColumnDifference> differing;
		private final int identicalCount;

		private Result(List<ColumnMetadata> onlyOnSource, List<ColumnMetadata> onlyOnTarget,
				List<ColumnDifference> differing, int identicalCount) {
			this.onlyOnSource = onlyOnSource;
			this.onlyOnTarget = onlyOnTarget;
			this.differing = differing;
			this.identicalCount = identicalCount;
		}

		public List<ColumnMetadata> getOnlyOnSource() {
			return onlyOnSource;
		}

		public List<ColumnMetadata> getOnlyOnTarget() {
			return onlyOnTarget;
		}

		public List<ColumnDifference> getDiffering() {
			return differing;
		}

		public int getIdenticalCount() {
			return identicalCount;
		}

		public boolean isIdentical() {
			return onlyOnSource.isEmpty() && onlyOnTarget.isEmpty() && differing.isEmpty();
		}
	}

	/**
	 * Opens a dedicated JDBC connection to {@code target}, mirroring the driver branching of
	 * {@link DatabaseConnection#openConnection(boolean)} (Derby, H2, everything else), duplicated
	 * rather than reused because that method is tied to the {@code sqlDatabase} singleton's own
	 * {@code this.platform} field, which a second, short lived connection has no business touching.
	 */
	private Connection openConnection(DatabaseDefinition target) throws BroadSQLException {
		try {
			Connection conn;
			if (SpringPropertiesConfig.DBTYPE_DERBY_Embedded.equalsIgnoreCase(target.getDbType())
					|| SpringPropertiesConfig.DBTYPE_DERBY_Client.equalsIgnoreCase(target.getDbType())) {
				System.setProperty("derby.system.home", "logs");
				Class.forName(target.getDbDriver()).getDeclaredConstructor().newInstance();
				Properties props = new Properties();
				if (StringUtils.isNotBlank(target.getUserName())) {
					props.put("user", target.getUserName());
					props.put("password", target.getUserPassword());
				}
				conn = DriverManager.getConnection(target.getConnectorDatabase(), props);
			} else if (SpringPropertiesConfig.DBTYPE_H2.equalsIgnoreCase(target.getDbType())) {
				Class.forName(target.getDbDriver()).getDeclaredConstructor().newInstance();
				String connector = target.getConnectorDatabase();
				if (StringUtils.isNotBlank(target.getUserName())) {
					connector = connector + ";USER=" + target.getUserName();
				}
				if (StringUtils.isNotBlank(target.getUserPassword())) {
					connector = connector + ";PASSWORD=" + target.getUserPassword();
				}
				conn = DriverManager.getConnection(connector);
			} else {
				Class.forName(target.getDbDriver()).getDeclaredConstructor().newInstance();
				Properties props = new Properties();
				if (StringUtils.isNotBlank(target.getUserName())) {
					props.put("user", target.getUserName());
					props.put("password", target.getUserPassword());
				}
				conn = DriverManager.getConnection(target.getConnectorDatabase(), props);
			}
			return conn;
		} catch (ReflectiveOperationException | SQLException e) {
			throw new BroadSQLException("Unable to connect to '" + target.getId() + "': " + e.getLocalizedMessage(), e);
		}
	}

	private String getCurrentSchema(Connection conn) throws BroadSQLException {
		try {
			return conn.getSchema();
		} catch (SQLException e) {
			throw new BroadSQLException(e);
		}
	}

	private boolean existsTable(Connection conn, String schemaPattern, String tableNamePattern) throws BroadSQLException {
		try {
			DatabaseMetaData dmd = conn.getMetaData();
			try (ResultSet rs = dmd.getTables(null, schemaPattern, tableNamePattern, null)) {
				return rs.next();
			}
		} catch (SQLException e) {
			throw new BroadSQLException(e);
		}
	}

	/** Duplicates the mapping loop of {@link DatabaseConnection#getColumns}, against a raw {@link Connection}. */
	private TreeSet<ColumnMetadata> getColumns(Connection conn, String schemaPattern, String tableNamePattern) throws BroadSQLException {
		try {
			TreeSet<ColumnMetadata> results = new TreeSet<>();
			DatabaseMetaData dmd = conn.getMetaData();
			try (ResultSet rs = dmd.getColumns(null, schemaPattern, tableNamePattern, null)) {
				while (rs.next()) {
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
					results.add(column);
				}
			}
			return results;
		} catch (SQLException e) {
			throw new BroadSQLException(e);
		}
	}
}
