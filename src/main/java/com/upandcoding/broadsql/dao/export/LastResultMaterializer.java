package com.upandcoding.broadsql.dao.export;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.pull.H2ColumnTypeMapper;

/**
 * {@code DUMP /} (SPRINT 2309T, #161): turns the last displayed result ({@link LastQueryResult}, captured
 * by the screen display itself) into a {@link MaterializedTabularResult}, without running any query
 * against the user's database again.
 *
 * <p>Refuses, with a clear error and before anything is written, a snapshot that is not the complete
 * result: none at all, or one whose display stopped at the configured {@code MaxRowsOnScreen} limit -
 * exporting only the rows that happened to be shown would be a silently partial file. There is no other
 * row limit: a result displayed in full is exported in full. Column types come from the displayed query's own metadata through
 * {@link H2ColumnTypeMapper} (the {@code PULL ... AS H2} mapping), so the same types are accepted and
 * rejected as for {@code DUMP (<query>)}.
 */
public final class LastResultMaterializer {

	private static final String TABLE_NAME = "LAST_RESULT";

	private LastResultMaterializer() {
	}

	/**
	 * @param last        the current snapshot, possibly {@code null}
	 * @param rerunHint   the command to suggest when the snapshot is incomplete, e.g.
	 *                    {@code DUMP (<query>) TO ...}; appended to the error as is
	 * @throws BroadSQLException if there is no complete, typed snapshot, or a column type cannot be exported
	 */
	public static MaterializedTabularResult materialize(LastQueryResult last, String rerunHint) throws BroadSQLException {
		if (last == null || last.columns() == null || last.typedRows() == null) {
			throw new BroadSQLException("No previous tabular result is available to dump. Run a query first, then DUMP /.");
		}
		if (!last.isComplete()) {
			throw new BroadSQLException("The previous result is incomplete: its display stopped at the MaxRowsOnScreen limit ("
					+ last.totalRowCount() + " rows), and the query is never run again for this export. " + rerunHint);
		}

		LastQueryResult.ColumnInfo[] columns = last.columns();
		rejectUnavailableValues(columns, last.typedRows());
		StringBuilder columnDefs = new StringBuilder();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < columns.length; i++) {
			LastQueryResult.ColumnInfo column = columns[i];
			if (!seen.add(column.label())) {
				throw new BroadSQLException("The previous result has two columns both named '" + column.label()
						+ "' - give one an explicit alias in the query, then run it and DUMP / again.");
			}
			String ddlType = H2ColumnTypeMapper.toH2DdlType(column.jdbcType(), column.precision(), column.scale(), column.label(), column.typeName());
			if (i > 0) {
				columnDefs.append(", ");
			}
			columnDefs.append(quote(column.label())).append(' ').append(ddlType);
		}

		Connection connection = open();
		try {
			try (Statement ddl = connection.createStatement()) {
				ddl.execute("CREATE TABLE " + quote(TABLE_NAME) + " (" + columnDefs + ")");
			}
			insert(connection, columns.length, last.typedRows());
			Statement select = connection.createStatement();
			return new MaterializedTabularResult(connection, select, select.executeQuery("SELECT * FROM " + quote(TABLE_NAME)));
		} catch (SQLException e) {
			try {
				connection.close();
			} catch (SQLException ignored) {
				// best-effort cleanup after a failure already being reported
			}
			throw new BroadSQLException("Unable to prepare the previous result for DUMP /: " + e.getLocalizedMessage(), e);
		}
	}

	/**
	 * A value the display showed but whose typed copy could not be read ({@link LastQueryResult.UnavailableValue})
	 * is refused, before anything is written: exporting it would mean writing a value of another type than its
	 * column's.
	 */
	private static void rejectUnavailableValues(LastQueryResult.ColumnInfo[] columns, List<Object[]> rows) throws BroadSQLException {
		for (int row = 0; row < rows.size(); row++) {
			Object[] values = rows.get(row);
			for (int i = 0; i < values.length; i++) {
				if (values[i] instanceof LastQueryResult.UnavailableValue unavailable) {
					throw new BroadSQLException("The previous result cannot be exported: column '" + columns[i].label() + "' (" + columns[i].typeName()
							+ ") displayed the value '" + unavailable.text() + "' on row " + (row + 1) + ", which cannot be exported as "
							+ columns[i].typeName() + ". Nothing was exported. Convert the column to text in the query (for example with "
							+ "CAST(" + columns[i].label() + " AS VARCHAR)), run it again, then DUMP / again.");
				}
			}
		}
	}

	private static void insert(Connection connection, int columnCount, List<Object[]> rows) throws SQLException {
		if (rows.isEmpty()) {
			return;
		}
		StringBuilder placeholders = new StringBuilder();
		for (int i = 0; i < columnCount; i++) {
			placeholders.append(i == 0 ? "?" : ", ?");
		}
		try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + quote(TABLE_NAME) + " VALUES (" + placeholders + ")")) {
			for (Object[] row : rows) {
				for (int i = 0; i < columnCount; i++) {
					insert.setObject(i + 1, row[i]);
				}
				insert.addBatch();
			}
			insert.executeBatch();
		}
	}

	private static Connection open() throws BroadSQLException {
		try {
			Class.forName("org.h2.Driver");
			return DriverManager.getConnection("jdbc:h2:mem:dump_last_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1");
		} catch (ReflectiveOperationException | SQLException e) {
			throw new BroadSQLException("Unable to open the in-memory database backing DUMP /: " + e.getLocalizedMessage(), e);
		}
	}

	private static String quote(String identifier) {
		return "\"" + identifier.replace("\"", "\"\"") + "\"";
	}
}
