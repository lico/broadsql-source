package com.upandcoding.broadsql.dao.compare;

import java.sql.Connection;
import java.sql.DriverManager;
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
import com.upandcoding.broadsql.dao.metadata.MetadataException;
import com.upandcoding.broadsql.dao.metadata.MetadataService;
import com.upandcoding.broadsql.dao.model.metadata.ColumnMetadata;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

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

		Connection targetConnection = openConnection(target);
		try {
			// The one canonical exact resolver (as DESCR, SHOW PK/FK/REFERENCES/INDEXES), applied on each side
			// independently: each side defaults to its own current schema and tries the name as typed, upper-cased
			// and lower-cased, since two database platforms may fold unquoted identifiers to a different case.
			MetadataService sourceMetadata = source.getMetadataService();
			MetadataService targetMetadata = new MetadataService(targetConnection);
			TableMetadata sourceTable = resolve(sourceMetadata, rawTableName, "the current connection");
			TableMetadata targetTable = resolve(targetMetadata, rawTableName, "connection '" + connectionLabel + "'");

			return diff(new TreeSet<>(sourceMetadata.columns(sourceTable)), new TreeSet<>(targetMetadata.columns(targetTable)));
		} finally {
			try {
				targetConnection.close();
			} catch (SQLException e) {
				log.warn("Unable to close comparison connection to '{}': {}", target.getId(), e.getLocalizedMessage());
			}
		}
	}

	/** {@code rawTableName} resolved on one side; a table missing on that side names the side in the message. */
	private static TableMetadata resolve(MetadataService metadata, String rawTableName, String side) throws BroadSQLException {
		try {
			return metadata.resolveTable(rawTableName);
		} catch (MetadataException e) {
			if (e.getKind() == MetadataException.Kind.NOT_FOUND) {
				throw new BroadSQLException("Table name '" + StringUtils.trim(rawTableName) + "' does not exist on " + side);
			}
			throw new BroadSQLException(e.getMessage() + " (" + side + ")");
		}
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
}
