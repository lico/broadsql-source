package com.upandcoding.broadsql.dao.api.tabular;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.LastApiExecutionResult;

/**
 * Turns a {@link LastApiExecutionResult}'s tabularized rows into a real {@link ResultSet}, so
 * {@code PULL API RESULT TO ...} can hand it to exactly the same exporters
 * ({@code com.upandcoding.broadsql.dao.pull.PullToH2Exporter}, {@code PullToXlsxExporter}/
 * {@code PullToOdsExporter}, {@code PullToTextExporter}, {@code PullToJsonExporter}/
 * {@code PullToMarkdownExporter}/{@code PullToHtmlExporter}) a SQL-sourced {@code PULL} already uses -
 * docs/BroadSQL XT02 overnight batch plan, sub-sprint 7 ("API result export and local snapshot"). None of
 * those exporters are touched by this class: every one of them only ever consumes a {@code ResultSet}.
 *
 * <p>Opens a throwaway {@code jdbc:h2:mem:api_result_<random>;DB_CLOSE_DELAY=-1} connection, creates one
 * table whose columns are exactly {@link ApiResultTable#getColumnNames()} (every column {@code VARCHAR} -
 * safe/widening, no numeric/date typing this sprint, consistent with sub-sprint 6's own console-rendering
 * boundary), inserts every row (a missing key or a JSON {@code null} - both represented by
 * {@link ApiResultTable#getRows()}'s maps having no/a {@code null} entry for that column, see
 * {@link ApiJsonTabularizer#tabularize}'s Javadoc - is inserted as a real SQL {@code NULL}, never the
 * string {@code "null"}), then returns a {@link MaterializedApiResult} wrapping a
 * {@code SELECT * FROM ...} {@code ResultSet} against it.
 */
public final class ApiResultMaterializer {

	private static final String TABLE_NAME = "API_RESULT";

	private ApiResultMaterializer() {
	}

	/**
	 * @throws BroadSQLException if {@code apiResult}'s table has no columns to export (an empty-array
	 *                           response, or a non-tabular body) - there is nothing meaningful to
	 *                           materialize, and an empty/zero-column {@code CREATE TABLE} is not valid
	 *                           SQL to begin with - or if the throwaway H2 database itself could not be
	 *                           created/populated
	 */
	public static MaterializedApiResult materialize(LastApiExecutionResult apiResult) throws BroadSQLException {
		ApiResultTable table = apiResult.getTable();
		List<String> columns = table.getColumnNames();
		if (columns.isEmpty()) {
			throw new BroadSQLException("PULL API RESULT: the last API execution result has no columns to export "
					+ "(an empty array response, or a body that could not be rendered as a table) - nothing to pull.");
		}

		Connection connection = openThrowawayConnection();
		try {
			String quotedTable = quoteIdentifier(TABLE_NAME);
			createTable(connection, quotedTable, columns);
			insertRows(connection, quotedTable, columns, table.getRows());

			Statement selectStatement = connection.createStatement();
			ResultSet resultSet = selectStatement.executeQuery("SELECT * FROM " + quotedTable);
			return new MaterializedApiResult(connection, selectStatement, resultSet);

		} catch (SQLException e) {
			closeQuietly(connection);
			throw new BroadSQLException("Failed to materialize the API result into a throwaway H2 database: " + e.getLocalizedMessage(), e);
		}
	}

	private static Connection openThrowawayConnection() throws BroadSQLException {
		try {
			Class.forName("org.h2.Driver");
			String dbName = "api_result_" + UUID.randomUUID().toString().replace("-", "");
			return DriverManager.getConnection("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
		} catch (ReflectiveOperationException | SQLException e) {
			throw new BroadSQLException("Unable to open the throwaway H2 database backing PULL API RESULT: " + e.getLocalizedMessage(), e);
		}
	}

	private static void createTable(Connection connection, String quotedTable, List<String> columns) throws SQLException {
		StringBuilder columnDefs = new StringBuilder();
		for (int i = 0; i < columns.size(); i++) {
			if (i > 0) {
				columnDefs.append(", ");
			}
			columnDefs.append(quoteIdentifier(columns.get(i))).append(" VARCHAR");
		}
		try (Statement ddl = connection.createStatement()) {
			ddl.execute("CREATE TABLE " + quotedTable + " (" + columnDefs + ")");
		}
	}

	private static void insertRows(Connection connection, String quotedTable, List<String> columns, List<Map<String, String>> rows) throws SQLException {
		if (rows.isEmpty()) {
			return;
		}
		String insertSql = buildInsertSql(quotedTable, columns);
		try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
			for (Map<String, String> row : rows) {
				for (int i = 0; i < columns.size(); i++) {
					String value = row.get(columns.get(i));
					if (value == null) {
						insert.setNull(i + 1, Types.VARCHAR);
					} else {
						insert.setString(i + 1, value);
					}
				}
				insert.addBatch();
			}
			insert.executeBatch();
		}
	}

	private static String buildInsertSql(String quotedTable, List<String> columns) {
		StringBuilder insertSql = new StringBuilder("INSERT INTO ").append(quotedTable).append(" (");
		StringBuilder placeholders = new StringBuilder();
		for (int i = 0; i < columns.size(); i++) {
			if (i > 0) {
				insertSql.append(", ");
				placeholders.append(", ");
			}
			insertSql.append(quoteIdentifier(columns.get(i)));
			placeholders.append("?");
		}
		insertSql.append(") VALUES (").append(placeholders).append(")");
		return insertSql.toString();
	}

	private static void closeQuietly(Connection connection) {
		try {
			connection.close();
		} catch (SQLException ignored) {
			// best-effort cleanup after a failure already being reported
		}
	}

	/**
	 * Wraps {@code identifier} in double quotes (H2's quoting character), doubling any embedded quote -
	 * mirrors {@code PullToH2Exporter#quoteIdentifier} exactly, so a JSON key with mixed case, a
	 * reserved word, or a special character survives as an exact column label once this throwaway
	 * table's {@code ResultSetMetaData} is read back by the real destination exporter.
	 */
	private static String quoteIdentifier(String identifier) {
		return "\"" + identifier.replace("\"", "\"\"") + "\"";
	}
}
