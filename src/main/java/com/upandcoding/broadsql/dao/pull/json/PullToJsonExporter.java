package com.upandcoding.broadsql.dao.pull.json;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.format.DateTimeFormatter;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.errors.CommandInterruptedException;
import com.upandcoding.broadsql.controller.shell.commands.CommandCancellation;
import com.upandcoding.broadsql.dao.OracleJdbcTypes;

/**
 * Engine behind {@code PULL ... TO <name> AS JSON} - see docs/PULL_TO_TEXT.md. Writes a query's current
 * results as a single JSON array of objects, one per row, always a full overwrite of {@code <name>.json}
 * - same "flat file, no MODE, no metadata" design as {@code PullToTextExporter} (CSV/TXT), for the same
 * reason (a JSON array has no tab/section concept, and reliably rewriting "just one part" of a JSON file
 * without a real document object model would be fragile). Standalone by design, sharing no code with any
 * other PULL exporter.
 *
 * <p>Every column type is validated ({@link PullJsonTypeMapper}) before the target file is opened or
 * written at all. A {@code DECIMAL}/{@code NUMERIC}/{@code BIGINT} value is written as a real JSON number
 * when it round-trips exactly through a {@code double} - the numeric type essentially every JSON parser
 * uses - otherwise it falls back to a JSON string, the same rule already used for Excel and for the same
 * reason. {@code DATE}/{@code TIME}/{@code TIMESTAMP} are written as ISO-8601 strings, the convention
 * most JSON-consuming tooling expects, rather than the space-separated, human-oriented format used
 * elsewhere in this codebase (e.g. the spreadsheet exporters' info tab) - JSON's audience here is
 * programmatic, not a human reading a report. A SQL {@code NULL} is written as a literal JSON
 * {@code null}, never the string {@code "null"}.
 */
public class PullToJsonExporter {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	/**
	 * @param filePath      the {@code .json} file's full path - always fully overwritten, whether or not
	 *                      it already exists
	 * @param sourceResults the query's result set, positioned before the first row; consumed to
	 *                      completion (or cancellation) by this method, but not closed - the caller owns it
	 * @return the number of data rows written
	 */
	public int writeFile(String filePath, ResultSet sourceResults) throws BroadSQLException, SQLException {
		ResultSetMetaData metaData = sourceResults.getMetaData();
		int columnCount = metaData.getColumnCount();
		int[] columnTypes = new int[columnCount];
		String[] columnKeys = new String[columnCount];
		for (int i = 1; i <= columnCount; i++) {
			columnTypes[i - 1] = metaData.getColumnType(i);
			PullJsonTypeMapper.validateSupported(columnTypes[i - 1], metaData.getColumnLabel(i), metaData.getColumnTypeName(i));
			columnKeys[i - 1] = metaData.getColumnLabel(i);
		}

		File targetFile = new File(filePath);
		File parent = targetFile.getAbsoluteFile().getParentFile();
		File tempFile;
		try {
			tempFile = File.createTempFile(targetFile.getName() + ".", ".tmp", parent);
		} catch (IOException e) {
			throw new BroadSQLException("Unable to create a temporary file next to '" + targetFile.getAbsolutePath() + "': " + e.getLocalizedMessage(), e);
		}

		int rowsWritten;
		try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8))) {
			out.write("[\n");
			rowsWritten = 0;
			boolean first = true;
			while (sourceResults.next()) {
				if (CommandCancellation.isRequested()) {
					throw new CommandInterruptedException("Command interrupted");
				}
				if (!first) {
					out.write(",\n");
				}
				first = false;
				out.write("  ");
				out.write(writeObject(sourceResults, columnKeys, columnTypes));
				rowsWritten++;
			}
			out.write("\n]\n");
		} catch (IOException e) {
			tempFile.delete();
			throw new BroadSQLException("Unable to write '" + filePath + "': " + e.getLocalizedMessage(), e);
		} catch (RuntimeException re) {
			// Normalizes an unexpected runtime failure into the exception type the rest of the app expects
			// to catch and display cleanly - CommandInterruptedException is a checked BroadSQLException,
			// not a RuntimeException, so CTRL+C cancellation still propagates through untouched.
			tempFile.delete();
			throw new BroadSQLException(re);
		}

		try {
			Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			tempFile.delete();
			throw new BroadSQLException("Unable to write '" + targetFile.getAbsolutePath() + "': " + e.getLocalizedMessage(), e);
		}
		return rowsWritten;
	}

	private String writeObject(ResultSet results, String[] columnKeys, int[] columnTypes) throws SQLException {
		StringBuilder obj = new StringBuilder("{ ");
		for (int i = 0; i < columnKeys.length; i++) {
			if (i > 0) {
				obj.append(", ");
			}
			obj.append(jsonString(columnKeys[i])).append(": ").append(jsonValue(results, i + 1, columnTypes[i]));
		}
		obj.append(" }");
		return obj.toString();
	}

	/**
	 * @return the JSON literal for column {@code columnIndex} - {@code null} (unquoted), a JSON number, a
	 *         JSON string, or a JSON boolean, per {@link #formatCellValue}'s rules
	 */
	private String jsonValue(ResultSet results, int columnIndex, int jdbcType) throws SQLException {
		return formatCellValue(results, columnIndex, jdbcType);
	}

	/**
	 * Every branch reads the column with the JDBC getter matching its actual type, then checks
	 * {@code wasNull()}, since the primitive/date getters return {@code 0}/{@code false}/a bogus date on
	 * a SQL {@code NULL}, not {@code null} (same reasoning as every other PULL exporter in this project).
	 */
	private String formatCellValue(ResultSet results, int columnIndex, int jdbcType) throws SQLException {
		switch (jdbcType) {
			case Types.DATE: {
				Date value = results.getDate(columnIndex);
				return results.wasNull() ? "null" : jsonString(value.toLocalDate().toString());
			}
			case Types.TIME:
			case Types.TIME_WITH_TIMEZONE: {
				Time value = results.getTime(columnIndex);
				return results.wasNull() ? "null" : jsonString(value.toLocalTime().format(TIME_FORMAT));
			}
			case Types.TIMESTAMP:
			case Types.TIMESTAMP_WITH_TIMEZONE:
			case OracleJdbcTypes.TIMESTAMPTZ:
			case OracleJdbcTypes.TIMESTAMPLTZ: {
				Timestamp value = results.getTimestamp(columnIndex);
				return results.wasNull() ? "null" : jsonString(value.toLocalDateTime().format(TIMESTAMP_FORMAT));
			}
			case Types.TINYINT:
			case Types.SMALLINT:
			case Types.INTEGER:
			case Types.BIGINT: {
				long value = results.getLong(columnIndex);
				if (results.wasNull()) {
					return "null";
				}
				double asDouble = value;
				return (long) asDouble == value ? Long.toString(value) : jsonString(Long.toString(value));
			}
			case Types.DECIMAL:
			case Types.NUMERIC: {
				BigDecimal value = results.getBigDecimal(columnIndex);
				if (results.wasNull()) {
					return "null";
				}
				double asDouble = value.doubleValue();
				return BigDecimal.valueOf(asDouble).compareTo(value) == 0 ? value.toPlainString() : jsonString(value.toPlainString());
			}
			case Types.DOUBLE:
			case Types.REAL: {
				double value = results.getDouble(columnIndex);
				return results.wasNull() ? "null" : Double.toString(value);
			}
			case Types.FLOAT: {
				float value = results.getFloat(columnIndex);
				return results.wasNull() ? "null" : Float.toString(value);
			}
			case Types.BOOLEAN:
			case Types.BIT: {
				boolean value = results.getBoolean(columnIndex);
				return results.wasNull() ? "null" : Boolean.toString(value);
			}
			default: {
				// CHAR/VARCHAR/NVARCHAR/LONGVARCHAR family - the only other types PullJsonTypeMapper accepts.
				String value = results.getString(columnIndex);
				return value == null ? "null" : jsonString(value);
			}
		}
	}

	/** Wraps {@code value} in double quotes, escaping per the JSON spec (RFC 8259, section 7). */
	private String jsonString(String value) {
		StringBuilder sb = new StringBuilder(value.length() + 2);
		sb.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				case '\b':
					sb.append("\\b");
					break;
				case '\f':
					sb.append("\\f");
					break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		sb.append('"');
		return sb.toString();
	}
}
