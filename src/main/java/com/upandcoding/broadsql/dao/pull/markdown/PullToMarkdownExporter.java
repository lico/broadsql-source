package com.upandcoding.broadsql.dao.pull.markdown;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;

import com.upandcoding.broadsql.dao.pull.TemporalValues;
import com.upandcoding.broadsql.dao.pull.TemporalText;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.errors.CommandInterruptedException;
import com.upandcoding.broadsql.controller.shell.commands.CommandCancellation;

/**
 * Engine behind {@code PULL ... TO <name> AS MD} - see docs/PULL_TO_TEXT.md. Writes a query's current
 * results as a single GitHub-Flavored-Markdown table, always a full overwrite of {@code <name>.md} - same
 * "flat file, no MODE, no metadata" design as {@code PullToTextExporter} (CSV/TXT) and
 * {@code PullToJsonExporter}, for the same reason (reliably rewriting "just one table" inside a
 * hand-editable Markdown file, which has no real document object model, would be fragile). Standalone by
 * design, sharing no code with any other PULL exporter.
 *
 * <p>Every column type is validated ({@link PullMarkdownTypeMapper}) before the target file is opened or
 * written at all. A literal {@code |} in a cell is escaped as {@code \|} (the only character a GFM table
 * row needs escaped); an embedded newline is rendered as {@code <br>}, which every major GFM renderer
 * (GitHub, GitLab, Slack, Notion, Confluence) treats as a line break inside a table cell - a raw newline
 * would otherwise split one logical row into two malformed physical rows. A SQL {@code NULL} produces an
 * empty cell.
 */
public class PullToMarkdownExporter {


	/**
	 * @param filePath      the {@code .md} file's full path - always fully overwritten, whether or not it
	 *                      already exists
	 * @param sourceResults the query's result set, positioned before the first row; consumed to
	 *                      completion (or cancellation) by this method, but not closed - the caller owns it
	 * @return the number of data rows written
	 */
	public int writeFile(String filePath, ResultSet sourceResults) throws BroadSQLException, SQLException {
		ResultSetMetaData metaData = sourceResults.getMetaData();
		int columnCount = metaData.getColumnCount();
		int[] columnTypes = new int[columnCount];
		String[] columnLabels = new String[columnCount];
		for (int i = 1; i <= columnCount; i++) {
			columnTypes[i - 1] = TemporalValues.effectiveType(metaData.getColumnType(i), metaData.getColumnTypeName(i));
			PullMarkdownTypeMapper.validateSupported(columnTypes[i - 1], metaData.getColumnLabel(i), metaData.getColumnTypeName(i));
			columnLabels[i - 1] = escapeCell(metaData.getColumnLabel(i));
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
			writeRow(out, columnLabels);
			String[] separators = new String[columnCount];
			for (int i = 0; i < columnCount; i++) {
				separators[i] = "---";
			}
			writeRow(out, separators);

			rowsWritten = 0;
			while (sourceResults.next()) {
				if (CommandCancellation.isRequested()) {
					throw new CommandInterruptedException("Command interrupted");
				}
				String[] fields = new String[columnCount];
				for (int i = 0; i < columnCount; i++) {
					fields[i] = formatCellValue(sourceResults, i + 1, columnTypes[i]);
				}
				writeRow(out, fields);
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

	private void writeRow(BufferedWriter out, String[] fields) throws IOException {
		StringBuilder line = new StringBuilder("|");
		for (String field : fields) {
			line.append(' ').append(field == null ? "" : field).append(" |");
		}
		line.append('\n');
		out.write(line.toString());
	}

	/**
	 * Escapes the literal {@code |} character (GFM's only table-row-breaking character) as {@code \|},
	 * and replaces an embedded newline with {@code <br>} (see the class Javadoc for why).
	 */
	private String escapeCell(String value) {
		return value.replace("|", "\\|").replace("\r\n", "<br>").replace("\n", "<br>").replace("\r", "<br>");
	}

	/**
	 * @return the empty string for a SQL {@code NULL} (rendered as a blank cell by {@link #writeRow});
	 *         every branch reads the column with the JDBC getter matching its actual type, then checks
	 *         {@code wasNull()}, since the primitive/date getters return {@code 0}/{@code false}/a bogus
	 *         date on a SQL {@code NULL}, not {@code null} (same reasoning as every other PULL exporter).
	 */
	private String formatCellValue(ResultSet results, int columnIndex, int jdbcType) throws SQLException {
		switch (jdbcType) {
			case Types.DATE: {
				LocalDate value = TemporalValues.getLocalDate(results, columnIndex);
				return value == null ? "" : value.toString();
			}
			case Types.TIME: {
				LocalTime value = TemporalText.getTime(results, columnIndex);
				return value == null ? "" : TemporalText.time(value);
			}
			case Types.TIME_WITH_TIMEZONE: {
				OffsetTime value = TemporalValues.getOffsetTime(results, columnIndex);
				return value == null ? "" : TemporalText.offsetTime(value);
			}
			case Types.TIMESTAMP: {
				LocalDateTime value = TemporalValues.getLocalDateTime(results, columnIndex);
				return value == null ? "" : TemporalText.dateTime(value);
			}
			case Types.TIMESTAMP_WITH_TIMEZONE: {
				// columnTypes holds TemporalValues.effectiveType: every zone-aware timestamp, Oracle's included
				OffsetDateTime value = TemporalValues.getOffsetDateTime(results, columnIndex);
				return value == null ? "" : TemporalText.offsetDateTime(value);
			}
			case Types.TINYINT:
			case Types.SMALLINT:
			case Types.INTEGER:
			case Types.BIGINT: {
				long value = results.getLong(columnIndex);
				return results.wasNull() ? "" : Long.toString(value);
			}
			case Types.DECIMAL:
			case Types.NUMERIC: {
				BigDecimal value = results.getBigDecimal(columnIndex);
				return results.wasNull() ? "" : value.toPlainString();
			}
			case Types.DOUBLE:
			case Types.REAL: {
				double value = results.getDouble(columnIndex);
				return results.wasNull() ? "" : Double.toString(value);
			}
			case Types.FLOAT: {
				float value = results.getFloat(columnIndex);
				return results.wasNull() ? "" : Float.toString(value);
			}
			case Types.BOOLEAN:
			case Types.BIT: {
				boolean value = results.getBoolean(columnIndex);
				return results.wasNull() ? "" : Boolean.toString(value);
			}
			default: {
				// CHAR/VARCHAR/NVARCHAR/LONGVARCHAR family - the only other types PullMarkdownTypeMapper accepts.
				String value = results.getString(columnIndex);
				return value == null ? "" : escapeCell(value);
			}
		}
	}
}
