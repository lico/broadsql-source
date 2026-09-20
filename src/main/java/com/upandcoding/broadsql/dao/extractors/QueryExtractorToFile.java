package com.upandcoding.broadsql.dao.extractors;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.errors.CommandInterruptedException;
import com.upandcoding.broadsql.controller.shell.commands.CommandCancellation;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Used by {@code EXPORT}/{@code DUMP} for {@code .csv}/{@code .txt} output - see
 * {@link com.upandcoding.broadsql.dao.DatabaseConnection#setFileName(String)}. Unaffected by, and shares no
 * code with, {@code PULL ... TO <name> AS CSV/TXT}
 * ({@code com.upandcoding.broadsql.dao.pull.text.PullToTextExporter}, docs/PULL_TO_TEXT.md) - the latter is
 * the recommended alternative for a one-shot query/table export going forward, with its own separate
 * {@code CsvSeparator} setting and a BLOB/CLOB fail-fast guard this class does not have (see that
 * class's Javadoc for the comparison).
 */
public class QueryExtractorToFile implements IQueryExtractor {

	private static final String NEW_LINE = "\r\n";

	/**
	 * Renders a single field for the delimited output, quoting it (RFC 4180 style: wrapped in double
	 * quotes, internal double quotes doubled) when it contains the column separator, a double quote,
	 * or a line break. Without this, a value containing the separator shifts every column that
	 * follows it, and a value containing a newline splits one logical row into several physical
	 * lines in the output file.
	 */
	private static String csvField(String value, char sep) {
		if (value == null) {
			return "";
		}
		if (value.indexOf(sep) < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
			return value;
		}
		return "\"" + value.replace("\"", "\"\"") + "\"";
	}

	public static void extractToTextFile(ResultSet results, String fileName, char sep, boolean isAppendToFile) throws BroadSQLException, IOException, SQLException {

		BufferedWriter out = null;

		try {
			if (results != null) {
				ResultSetMetaData metaData = results.getMetaData();

				// Open output file
				if (fileName != null) {
					// When appending to a file that already has content, skip the header row (and the BOM
					// below) - otherwise every append would insert a duplicate header line in the middle
					// of the file.
					boolean writeHeader = !isAppendToFile || new File(fileName).length() == 0;
					// Explicit UTF-8, independent of the process-wide -Dfile.encoding (Cp850, set for the
					// Windows console in BroadSQL.bat/.ps1) - writing with that legacy OEM codepage produced
					// files that most tools misread as the ANSI codepage, e.g. "réponse" coming out as
					// "r‚ponse" once opened. FileWriter would have silently inherited that same setting.
					out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fileName, isAppendToFile), StandardCharsets.UTF_8));

					if (writeHeader) {
						// UTF-8 BOM: lets tools that don't auto-detect UTF-8 (Excel opening a .csv directly,
						// in particular) recognize the encoding instead of falling back to the system's
						// ANSI codepage.
						out.write((char) 0xFEFF);

						StringBuilder header = new StringBuilder();
						for (int i = 0; i < metaData.getColumnCount(); i++) {
							if (i > 0) {
								header.append(sep);
							}
							header.append(csvField(metaData.getColumnLabel(i + 1), sep));
						}
						header.append(NEW_LINE);
						out.write(header.toString());
					}
				}

				// Data rows
				while (results.next()) {
					// CTRL+C support: abort the export without closing the connection
					// (see docs/TODO.md, "Ameliorations de la GUI")
					if (CommandCancellation.isRequested()) {
						throw new CommandInterruptedException("Command interrupted");
					}

					StringBuilder line = new StringBuilder();
					for (int i = 0; i < metaData.getColumnCount(); i++) {
						String cell = results.getString(i + 1);
						boolean isNull = results.wasNull();
						if (i > 0) {
							line.append(sep);
						}
						// A true SQL NULL renders as an empty field; a non-null value (even "", "   ", or
						// the literal text "null") is written as-is, quoted if needed - previously both
						// were blanked out identically, so a real column value of "null" was silently
						// erased and indistinguishable from an actual NULL.
						if (!isNull) {
							line.append(csvField(cell, sep));
						}
					}
					line.append(NEW_LINE);

					if (out != null) {
						// BufferedWriter.write() declares IOException and does not swallow it the way
						// PrintWriter.print() used to (silently, observable only via checkError(), which
						// was never called) - a disk-full or other write failure now surfaces to the
						// caller instead of producing a silently truncated file.
						out.write(line.toString());
					}
				}
			}

		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		} finally {
			// Release the file handle even when interrupted mid-export, so a partial file can be
			// deleted right away by the caller (see docs/TODO.md, "Ameliorations de la GUI")
			if (out != null) {
				out.close();
			}
			try {
				if (results != null) {
					results.close();
				}
			} catch (SQLException ie) {
				throw new BroadSQLException(ie);
			}
		}
	}

	@Override
	public void extract(DatabaseDefinition platform, ShellConsole cmdLineConsole, String query, ResultSet results, int maxRowsOnScreen, boolean listMode, String fileName, char screenSep, char sep,
			boolean isAppendToFile, Instant start) throws BroadSQLException, IOException, SQLException {
		extractToTextFile(results, fileName, sep, isAppendToFile);
	}
}
