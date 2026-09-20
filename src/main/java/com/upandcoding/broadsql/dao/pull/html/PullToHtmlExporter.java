package com.upandcoding.broadsql.dao.pull.html;

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
 * Engine behind {@code PULL ... TO <name> AS HTML} - see docs/PULL_TO_TEXT.md. Writes a query's current
 * results as a single, self-contained {@code <table>...</table>} HTML fragment - deliberately not a full
 * {@code <html>/<head>/<body>} document (per an explicit product decision, 28/08/2026): the fragment is
 * meant to be pasted straight into an existing email/wiki/document body, where a full document's wrapper
 * tags would be unwanted or stripped anyway. Because there is no {@code <head>} to hold a stylesheet, the
 * header row's styling is inline, so it survives a paste into a plain-text-unaware editor exactly as well
 * as the data itself does.
 *
 * <p>Always a full overwrite of {@code <name>.html} - same "flat file, no MODE, no metadata" design as
 * every other PULL text-family exporter, for the same reason (no tab/section concept, and no reliable way
 * to rewrite "just one table" inside an arbitrary existing HTML file without a real DOM library). No BOM
 * is written - unlike {@code PullToTextExporter}'s CSV/TXT output (meant to be opened directly, where a
 * BOM helps a tool like Excel auto-detect UTF-8), this fragment is meant to be pasted into a page that
 * already declares its own encoding, where a stray BOM character could show up as visible noise.
 * Standalone by design, sharing no code with any other PULL exporter.
 *
 * <p>Every column type is validated ({@link PullHtmlTypeMapper}) before the target file is opened or
 * written at all. Cell text is HTML-escaped (`&amp;`, `&lt;`, `&gt;`); a SQL {@code NULL} produces an
 * empty {@code <td></td>}.
 */
public class PullToHtmlExporter {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final String HEADER_STYLE = "font-weight:bold;background-color:#C6D9F1;border:1px solid #999;padding:4px 8px;text-align:left;";
	private static final String CELL_STYLE = "border:1px solid #999;padding:4px 8px;";

	/**
	 * @param filePath      the {@code .html} file's full path - always fully overwritten, whether or not
	 *                      it already exists
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
			columnTypes[i - 1] = metaData.getColumnType(i);
			PullHtmlTypeMapper.validateSupported(columnTypes[i - 1], metaData.getColumnLabel(i), metaData.getColumnTypeName(i));
			columnLabels[i - 1] = metaData.getColumnLabel(i);
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
			out.write("<table>\n<thead>\n<tr>\n");
			for (String label : columnLabels) {
				out.write("<th style=\"" + HEADER_STYLE + "\">" + escapeHtml(label) + "</th>\n");
			}
			out.write("</tr>\n</thead>\n<tbody>\n");

			rowsWritten = 0;
			while (sourceResults.next()) {
				if (CommandCancellation.isRequested()) {
					throw new CommandInterruptedException("Command interrupted");
				}
				out.write("<tr>\n");
				for (int i = 0; i < columnCount; i++) {
					String value = formatCellValue(sourceResults, i + 1, columnTypes[i]);
					out.write("<td style=\"" + CELL_STYLE + "\">" + (value == null ? "" : escapeHtml(value)) + "</td>\n");
				}
				out.write("</tr>\n");
				rowsWritten++;
			}

			out.write("</tbody>\n</table>\n");
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

	private String escapeHtml(String value) {
		StringBuilder sb = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '&':
					sb.append("&amp;");
					break;
				case '<':
					sb.append("&lt;");
					break;
				case '>':
					sb.append("&gt;");
					break;
				default:
					sb.append(c);
			}
		}
		return sb.toString();
	}

	/**
	 * @return {@code null} for a SQL {@code NULL} (rendered as an empty cell by the caller); every branch
	 *         reads the column with the JDBC getter matching its actual type, then checks
	 *         {@code wasNull()}, since the primitive/date getters return {@code 0}/{@code false}/a bogus
	 *         date on a SQL {@code NULL}, not {@code null} (same reasoning as every other PULL exporter).
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
				// CHAR/VARCHAR/NVARCHAR/LONGVARCHAR family - the only other types PullHtmlTypeMapper accepts.
				return results.getString(columnIndex);
			}
		}
	}
}
