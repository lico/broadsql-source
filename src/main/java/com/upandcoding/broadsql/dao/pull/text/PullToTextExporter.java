package com.upandcoding.broadsql.dao.pull.text;

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
 * Engine behind {@code PULL ... TO <name> AS CSV/TXT} - see docs/PULL_TO_TEXT.md. Writes a query's
 * current results as a plain, self-contained delimited text file: always a full overwrite of
 * {@code <name>.csv}/{@code .txt} (there is no multi-tab, no info-tab tracking, and no partial-file
 * concept for a flat file - unlike {@code PULL ... AS XLSX/ODS}, see that design doc's "Info tab"
 * decision for why CSV/TXT deliberately don't get one).
 *
 * <p>Standalone by design, sharing no code with {@code QueryExtractorToFile} (used by {@code EXPORT}/
 * {@code DUMP}, deliberately left untouched) nor with the H2/spreadsheet PULL exporters - see
 * docs/PULL_TO_TEXT.md, "Relationship to EXPORT/DUMP and to PULL's other destinations" - though the
 * RFC 4180 quoting rule below mirrors {@code QueryExtractorToFile}'s, since both are solving the same
 * "a value containing the separator/quote/newline must not corrupt the file" problem.
 *
 * <p>Every column type is validated ({@link PullTextTypeMapper}) against the query's own
 * {@link ResultSetMetaData} before the target file is opened or written at all, so an unsupported column
 * type never leaves a half-written file - unlike {@code QueryExtractorToFile}, which has no such guard
 * and simply calls {@code ResultSet.getString()} on every column. The write itself goes to a temporary
 * file, moved over the target only once it has been written and closed successfully - same rationale as
 * {@code PullToXlsxExporter}/{@code PullToOdsExporter}'s atomic write, even though there is no existing
 * content to preserve here (a failure partway through must never leave a half-written file in the
 * target's place).
 *
 * <p>Every value is written as plain text, so - unlike the spreadsheet exporters - there is no
 * double-precision round-trip concern for {@code DECIMAL}/{@code BIGINT}: every numeric type is rendered
 * via its own exact string form ({@link BigDecimal#toPlainString()}, {@link Long#toString(long)}, etc.).
 */
public class PullToTextExporter {

	private static final String NEW_LINE = "\r\n";
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/**
	 * @param filePath  the {@code .csv}/{@code .txt} file's full path - always fully overwritten, whether
	 *                  or not it already exists
	 * @param separator the field separator - {@code ConsoleSettings.getCsvSeparator()} for {@code AS CSV},
	 *                  a literal tab for {@code AS TXT}
	 * @param sourceResults the query's result set, positioned before the first row; consumed to
	 *                      completion (or cancellation) by this method, but not closed - the caller owns it
	 * @return the number of data rows written
	 */
	public int writeFile(String filePath, char separator, ResultSet sourceResults) throws BroadSQLException, SQLException {
		ResultSetMetaData metaData = sourceResults.getMetaData();
		int columnCount = metaData.getColumnCount();
		int[] columnTypes = new int[columnCount];
		for (int i = 1; i <= columnCount; i++) {
			columnTypes[i - 1] = metaData.getColumnType(i);
			PullTextTypeMapper.validateSupported(columnTypes[i - 1], metaData.getColumnLabel(i), metaData.getColumnTypeName(i));
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
			// UTF-8 BOM: lets tools that don't auto-detect UTF-8 (Excel opening a .csv directly, in
			// particular) recognize the encoding instead of falling back to the system's ANSI codepage -
			// same reasoning as QueryExtractorToFile's BOM, independently written here.
			out.write((char) 0xFEFF);

			writeRow(out, headerFields(metaData, columnCount), separator);

			rowsWritten = 0;
			while (sourceResults.next()) {
				if (CommandCancellation.isRequested()) {
					throw new CommandInterruptedException("Command interrupted");
				}
				writeRow(out, rowFields(sourceResults, columnTypes, columnCount), separator);
				rowsWritten++;
			}
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

	private String[] headerFields(ResultSetMetaData metaData, int columnCount) throws SQLException {
		String[] fields = new String[columnCount];
		for (int i = 0; i < columnCount; i++) {
			fields[i] = metaData.getColumnLabel(i + 1);
		}
		return fields;
	}

	private String[] rowFields(ResultSet results, int[] columnTypes, int columnCount) throws SQLException {
		String[] fields = new String[columnCount];
		for (int i = 0; i < columnCount; i++) {
			fields[i] = formatCellValue(results, i + 1, columnTypes[i]);
		}
		return fields;
	}

	private void writeRow(BufferedWriter out, String[] fields, char separator) throws IOException {
		StringBuilder line = new StringBuilder();
		for (int i = 0; i < fields.length; i++) {
			if (i > 0) {
				line.append(separator);
			}
			if (fields[i] != null) {
				line.append(quoteIfNeeded(fields[i], separator));
			}
		}
		line.append(NEW_LINE);
		out.write(line.toString());
	}

	/**
	 * RFC 4180-style quoting: a field is wrapped in double quotes, with any embedded double quote
	 * doubled, when it contains the separator, a double quote, or a line break - otherwise written
	 * as-is. Without this, a value containing the separator would shift every column after it, and a
	 * value containing a newline would split one logical row into several physical lines.
	 */
	private String quoteIfNeeded(String value, char separator) {
		if (value.indexOf(separator) < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
			return value;
		}
		return "\"" + value.replace("\"", "\"\"") + "\"";
	}

	/**
	 * @return the exact text form of column {@code columnIndex}, or {@code null} for a SQL {@code NULL}
	 *         (rendered as an empty field by {@link #writeRow}) - every branch reads the column with the
	 *         JDBC getter matching its actual type, then checks {@code wasNull()}, since the primitive/
	 *         date getters return {@code 0}/{@code false}/a bogus date on a SQL {@code NULL}, not
	 *         {@code null} (same reasoning as {@code PullToXlsxExporter}/{@code PullToOdsExporter}).
	 */
	private String formatCellValue(ResultSet results, int columnIndex, int jdbcType) throws SQLException {
		switch (jdbcType) {
			case Types.DATE: {
				Date value = results.getDate(columnIndex);
				return results.wasNull() ? null : value.toLocalDate().toString();
			}
			case Types.TIME:
			case Types.TIME_WITH_TIMEZONE: {
				Time value = results.getTime(columnIndex);
				return results.wasNull() ? null : value.toLocalTime().format(TIME_FORMAT);
			}
			case Types.TIMESTAMP:
			case Types.TIMESTAMP_WITH_TIMEZONE:
			case OracleJdbcTypes.TIMESTAMPTZ:
			case OracleJdbcTypes.TIMESTAMPLTZ: {
				Timestamp value = results.getTimestamp(columnIndex);
				return results.wasNull() ? null : value.toLocalDateTime().format(TIMESTAMP_FORMAT);
			}
			case Types.TINYINT:
			case Types.SMALLINT:
			case Types.INTEGER:
			case Types.BIGINT: {
				long value = results.getLong(columnIndex);
				return results.wasNull() ? null : Long.toString(value);
			}
			case Types.DECIMAL:
			case Types.NUMERIC: {
				BigDecimal value = results.getBigDecimal(columnIndex);
				return results.wasNull() ? null : value.toPlainString();
			}
			case Types.DOUBLE:
			case Types.REAL: {
				double value = results.getDouble(columnIndex);
				return results.wasNull() ? null : Double.toString(value);
			}
			case Types.FLOAT: {
				float value = results.getFloat(columnIndex);
				return results.wasNull() ? null : Float.toString(value);
			}
			case Types.BOOLEAN:
			case Types.BIT: {
				boolean value = results.getBoolean(columnIndex);
				return results.wasNull() ? null : Boolean.toString(value);
			}
			default: {
				// CHAR/VARCHAR/NVARCHAR/LONGVARCHAR family - the only other types PullTextTypeMapper accepts.
				return results.getString(columnIndex);
			}
		}
	}
}
