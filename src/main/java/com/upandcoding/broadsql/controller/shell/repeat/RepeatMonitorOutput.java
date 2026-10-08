package com.upandcoding.broadsql.controller.shell.repeat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.repeat.RepeatGuard.CapturedResult;
import com.upandcoding.broadsql.controller.shell.repeat.RepeatStatement.OutputFormat;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.export.LastResultMaterializer;
import com.upandcoding.broadsql.dao.export.MaterializedTabularResult;
import com.upandcoding.broadsql.dao.pull.json.PullToJsonExporter;
import com.upandcoding.broadsql.dao.pull.text.PullToTextExporter;

/**
 * GitHub #196: {@code REPEAT ... TO <file> [AS CSV|JSON|TEXT]}, the monitoring output. The console output stays as it
 * is; after each successful iteration its tabular results are <b>appended</b> to the file, never overwriting what
 * earlier iterations (or an earlier {@code REPEAT} with the same columns) wrote. The rows come from the snapshots the
 * screen display recorded ({@link RepeatGuard}); no query is run again. Values are written by the existing export
 * writers ({@link PullToTextExporter}, {@link PullToJsonExporter}), from the {@code DUMP /} materialization
 * ({@link LastResultMaterializer}).
 * <ul>
 * <li>{@code CSV}: the {@code CsvSeparator} setting, UTF-8 with a BOM, a header row when the file is new. A first
 * column, {@value #TIMESTAMP} ({@value #FALLBACK_TIMESTAMP} when the result already has a {@code TIMESTAMP}
 * column, {@code REPEAT_TIMESTAMP_2} when that one is taken too, and so on), holds the iteration's start time on every row. An existing file is appended to only when its header is
 * the same; otherwise the {@code REPEAT} stops before writing anything into it.</li>
 * <li>{@code JSON}: JSON Lines, one object per row and per line, with the same timestamp member first.</li>
 * <li>{@code TEXT}: a readable log, one section per iteration (its number and time), then, for each result, the
 * statement and its rows tab separated. The only format that records several results per iteration.</li>
 * </ul>
 * {@code CSV} and {@code JSON} hold one table: an iteration producing other than exactly one tabular result is
 * refused rather than mixing unrelated columns in one file.
 */
public final class RepeatMonitorOutput {

	public static final String TIMESTAMP = "TIMESTAMP";
	public static final String FALLBACK_TIMESTAMP = "REPEAT_TIMESTAMP";
	private static final DateTimeFormatter HEADER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final String NEW_LINE = "\r\n";
	private static final char BOM = 0xFEFF;

	private final Path file;
	private final OutputFormat format;
	private final char csvSeparator;
	private boolean headerChecked;

	private RepeatMonitorOutput(Path file, OutputFormat format, char csvSeparator) {
		this.file = file;
		this.format = format;
		this.csvSeparator = csvSeparator;
	}

	/**
	 * @param exportFolder the default export folder ({@code DefaultFolder}), where a relative {@code name} goes
	 * @param name         the {@code TO} file as written; the format's extension is added when it has none
	 */
	public static RepeatMonitorOutput open(String exportFolder, String name, OutputFormat format, char csvSeparator) throws BroadSQLException {
		Path written;
		try {
			written = Path.of(name);
		} catch (RuntimeException e) {
			throw new BroadSQLException("Invalid REPEAT output file name '" + name + "': " + e.getMessage());
		}
		Path file = written.isAbsolute() ? written : Path.of(exportFolder == null ? "" : exportFolder).resolve(written);
		String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
		if (!fileName.contains(".")) {
			file = file.resolveSibling(fileName + "." + format.extension());
		}
		file = file.toAbsolutePath().normalize();
		if (Files.isDirectory(file)) {
			throw new BroadSQLException("The REPEAT output '" + file + "' is a folder: give a file name.");
		}
		Path parent = file.getParent();
		if (parent == null || !Files.isDirectory(parent)) {
			throw new BroadSQLException("The folder of the REPEAT output '" + file + "' does not exist.");
		}
		return new RepeatMonitorOutput(file, format, csvSeparator);
	}

	public Path getFile() {
		return file;
	}

	public OutputFormat getFormat() {
		return format;
	}

	/** Whether an iteration may produce several tabular results ({@code TEXT} only). */
	public boolean acceptsSeveralResults() {
		return format == OutputFormat.TEXT;
	}

	/**
	 * Appends one iteration.
	 *
	 * @param timestamp when the iteration started
	 * @param results   the iteration's tabular results, in execution order
	 * @return the number of data rows appended
	 */
	public int append(int iteration, LocalDateTime timestamp, List<CapturedResult> results) throws BroadSQLException {
		if (format == OutputFormat.TEXT) {
			return appendText(iteration, timestamp, results);
		}
		if (results.size() != 1) {
			throw new BroadSQLException("Iteration " + iteration + " produced " + (results.isEmpty() ? "no tabular result" : results.size() + " tabular results")
					+ ": REPEAT ... AS " + format + " records exactly one result per iteration, so unrelated columns are never mixed in one file. "
					+ "Use AS TEXT to record several results.");
		}
		LastQueryResult result = results.get(0).result();
		String timestampColumn = timestampColumnName(result.columnLabels());
		try (MaterializedTabularResult materialized = LastResultMaterializer.materializeWithTimestamp(result, completeResultHint(), "REPEAT", timestampColumn,
				timestamp.withNano(0))) {
			if (format == OutputFormat.CSV) {
				return appendCsv(materialized);
			}
			String lines = new PullToJsonExporter().renderJsonLines(materialized.getResultSet());
			write(lines);
			return result.totalRowCount();
		} catch (SQLException e) {
			throw new BroadSQLException("Unable to write the REPEAT output '" + file + "': " + e.getLocalizedMessage(), e);
		}
	}

	private int appendCsv(MaterializedTabularResult materialized) throws BroadSQLException, SQLException {
		PullToTextExporter.RenderedTable rendered = new PullToTextExporter().renderDelimited(materialized.getResultSet(), csvSeparator, true);
		String text = rendered.text();
		int headerEnd = text.indexOf(NEW_LINE);
		String header = headerEnd < 0 ? text : text.substring(0, headerEnd);
		String rows = headerEnd < 0 ? "" : text.substring(headerEnd + NEW_LINE.length());
		if (headerChecked) {
			write(rows);
			return rendered.rowCount();
		}
		String existingHeader = existingFirstLine();
		if (existingHeader == null) {
			write(BOM + text);
		} else if (existingHeader.equals(header)) {
			write(rows);
		} else {
			throw new BroadSQLException("The REPEAT output '" + file + "' already exists with other columns (" + existingHeader + " instead of " + header
					+ "). REPEAT only appends to a file with the same columns: give another file name.");
		}
		headerChecked = true;
		return rendered.rowCount();
	}

	private int appendText(int iteration, LocalDateTime timestamp, List<CapturedResult> results) throws BroadSQLException {
		StringBuilder text = new StringBuilder();
		text.append("=== Repeat #").append(iteration).append(" at ").append(timestamp.format(HEADER_TIME)).append(" ===").append(NEW_LINE);
		int rows = 0;
		if (results.isEmpty()) {
			text.append("(no tabular result)").append(NEW_LINE).append(NEW_LINE);
		}
		for (int i = 0; i < results.size(); i++) {
			CapturedResult captured = results.get(i);
			text.append('[').append(i + 1).append('/').append(results.size()).append("] ").append(RepeatSafety.shorten(captured.statement())).append(NEW_LINE);
			try (MaterializedTabularResult materialized = LastResultMaterializer.materialize(captured.result(), completeResultHint())) {
				PullToTextExporter.RenderedTable rendered = new PullToTextExporter().renderTabSeparated(materialized.getResultSet());
				text.append(rendered.text());
				rows += rendered.rowCount();
			} catch (SQLException e) {
				throw new BroadSQLException("Unable to write the REPEAT output '" + file + "': " + e.getLocalizedMessage(), e);
			}
			text.append(NEW_LINE);
		}
		write(text.toString());
		return rows;
	}

	private static String completeResultHint() {
		return "REPEAT ... TO writes complete results only: raise MaxRowsOnScreen, or make the query return fewer rows.";
	}

	/**
	 * The name of the timestamp column for a result with {@code labels}: the first of {@value #TIMESTAMP},
	 * {@value #FALLBACK_TIMESTAMP}, {@code REPEAT_TIMESTAMP_2}, {@code REPEAT_TIMESTAMP_3}... that no column of the
	 * result already has (compared without regard to case). The result's own columns are never renamed.
	 */
	public static String timestampColumnName(List<String> labels) {
		if (!hasColumn(labels, TIMESTAMP)) {
			return TIMESTAMP;
		}
		if (!hasColumn(labels, FALLBACK_TIMESTAMP)) {
			return FALLBACK_TIMESTAMP;
		}
		int n = 2;
		while (hasColumn(labels, FALLBACK_TIMESTAMP + "_" + n)) {
			n++;
		}
		return FALLBACK_TIMESTAMP + "_" + n;
	}

	private static boolean hasColumn(List<String> labels, String label) {
		for (String column : labels) {
			if (label.equalsIgnoreCase(column)) {
				return true;
			}
		}
		return false;
	}

	/** The first line of the existing file without its BOM, or {@code null} when the file does not exist or is empty. */
	private String existingFirstLine() throws BroadSQLException {
		try {
			if (!Files.exists(file) || Files.size(file) == 0) {
				return null;
			}
			try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				String line = reader.readLine();
				if (line == null) {
					return null;
				}
				return !line.isEmpty() && line.charAt(0) == BOM ? line.substring(1) : line;
			}
		} catch (IOException e) {
			throw new BroadSQLException("Unable to read the existing REPEAT output '" + file + "': " + e.getLocalizedMessage(), e);
		}
	}

	private void write(String text) throws BroadSQLException {
		try {
			Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new BroadSQLException("Unable to write the REPEAT output '" + file + "': " + e.getLocalizedMessage(), e);
		}
	}
}
