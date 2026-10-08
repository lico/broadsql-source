package com.upandcoding.broadsql.controller.shell.commands.core.export;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import com.upandcoding.broadsql.dao.extractors.SpreadsheetSheetName;
import com.upandcoding.broadsql.dao.metadata.MetadataException;
import com.upandcoding.broadsql.dao.metadata.MetadataService;
import com.upandcoding.broadsql.dao.pull.PullCommandParser;
import com.upandcoding.broadsql.dao.pull.PullStatement;

/**
 * Exports tabular data to a file: {@code DUMP <source> [TO <destination>] [AS <format>]}. {@code PULL} is
 * a compatibility alias of this command. DUMP writes data or results to a file; it is not a database
 * backup.
 *
 * <p>Sources: a table or view name ({@code DUMP CUSTOMER AS CSV}; a name containing spaces or other special
 * characters in double quotes, {@code DUMP "Sales Data" AS CSV}), read by the exact name of the table it
 * designates, found as {@code DESCR} finds it; {@code /}, the previous tabular result
 * exactly as it was displayed, without running the query again; a query in parentheses, run now, in which
 * {@code ${name}} variables are passed to the database as typed values ({@code DUMP (SELECT * FROM orders WHERE
 * customer_id = ${id}) TO orders AS CSV}); {@code LIB <script> [name=value ...]}, the final tabular result of a
 * Scripts Library script, run as {@code LIB RUN} would run it, with the same named arguments, but without
 * displaying its results ({@code @<script>}, as a Script is run at the prompt, is the same source:
 * {@code DUMP @sales.sql} is {@code DUMP LIB sales.sql}); {@code API RESULT}, the last {@code RUN} result.
 * Only the query in parentheses is read for variables: {@code TO}, {@code AS} and the other parts of the
 * command are taken as written.
 *
 * <p>{@code DUMP /} exports only a complete result: if its display stopped at the configured
 * {@code MaxRowsOnScreen} limit, it refuses and suggests {@code DUMP (<query>)}; the query is never run
 * again. {@code DUMP LIB} writes the file only when the script's final status is {@code SUCCESS}: nothing is
 * written for {@code COMPLETED_WITH_ERRORS}, {@code FAILED} (a missing script or argument included) or
 * {@code CANCELLED}, and an error states the status. Nothing is written either when the script produces no
 * tabular result. A {@code LET} query of the script is never the exported result.
 *
 * <p>Formats: {@code CSV} (comma or semicolon, from the {@code CsvSeparator} setting, standard quoting),
 * {@code TEXT} (UTF-8, always tab separated), {@code JSON} (an array of objects), {@code XLSX} and
 * {@code ODS} (worksheet {@code DATA} unless named, as in {@code TO report.MONTHLY}), {@code H2},
 * {@code MD} and {@code HTML}. {@code TXT} is accepted as {@code TEXT}. Without {@code AS}, the
 * {@code DefaultFileFormat} setting applies.
 *
 * <p>Time-zone-aware timestamps are exported the same way on every computer: the text formats keep each
 * value's own offset ({@code 2024-01-15 10:00:00+05:00}), XLSX and ODS hold a native date/time cell of the
 * same moment in UTC, and {@code H2} keeps the offset. Time-zone-less dates, times and timestamps are never
 * converted.
 *
 * <p>Files are written to the export folder ({@code DefaultFolder}). Without {@code TO}, the file is named
 * after the table, after the library script, or after {@code DefaultExtFileName} ({@code results}) for
 * {@code /} and a query. {@code AS H2} always needs {@code TO <connection>.<table>}.
 *
 * <p>Write modes, for {@code AS H2}, {@code AS XLSX} and {@code AS ODS}: {@code MODE OVERWRITE} (the default)
 * replaces the table or worksheet. {@code MODE APPEND} adds the rows of the result to the existing table or
 * worksheet when it has the same number of columns, with the same names, in the same order as the result
 * (names compared without regard to case). Nothing else is compared: the data types are not, and the query is
 * not examined, so a join, a subquery, a {@code WITH} query, a {@code UNION} or any other query the database
 * runs can be appended. A destination that does not exist yet is created. When the columns differ, nothing is
 * written and the error names the difference. A result with no rows appends nothing and is not an error;
 * {@code NULL} values are appended as {@code NULL} (an empty cell in a worksheet). {@code MODE APPEND} works with every source.
 * {@code AS H2} also accepts {@code MODE APPEND KEY(<column>) [FORCE]}, described for {@code PULL}, which adds
 * only the rows whose key is greater than the table's current maximum.
 *
 * <p>The Connections Definition File ({@code $CDF}) is a protected BroadSQL system database: {@code AS H2} refuses
 * it as a destination, and so does any other connection that opens the same database file. Nothing is written.
 *
 * <p>{@code DUMP <table>} alone, with no other word, keeps its original file naming and format choice: the
 * file is named after the table (worksheet named after the table too) and its format is
 * {@code DefaultFileFormat}, or tab separated text beyond {@code MaxRowXLSX} rows. It is written by the
 * same writers as every other form, so a CSV file uses the {@code CsvSeparator} setting.
 *
 * <p>See the [Export guide](../export.md) for workflows and every format, and
 * [Exporting a Script's result](../scripting_export.md) in the SQL scripting guide.
 */
public class CommandDumpTable extends Command {

	// Implementation (SPRINT 2309T, #161/#163): DUMP and PULL parse into the same request (PullStatement,
	// PullCommandParser.Grammar.dump/pull) and run the same ExportPipeline. DUMP <table> alone is a source
	// adapter only: its own checks and file naming, then the same pipeline and writers.

	/** The {@code OPEN} keyword's test seam, same as {@link CommandPull#fileOpener}. */
	FileOpener fileOpener = DesktopFileOpener.INSTANCE;

	public CommandDumpTable() {
		super("DUMP");
	}

	/**
	 * Runs {@code DUMP}: the legacy table form ({@link #executeLegacyTableDump}) or the export grammar.
	 *
	 * @param query the full command line as typed, including the {@code DUMP} keyword
	 */
	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (isLegacyTableForm(args)) {
			executeLegacyTableDump(args);
			return;
		}

		// SPRINT 2309T (#161/#163): every other form - a source other than a bare table (/, (<query>),
		// LIB <script>, API RESULT), or any TO/AS clause - is the canonical export grammar, run by the same
		// pipeline as PULL.
		String afterKeyword = textAfterKeyword(query);
		ExportPipeline.DefaultFormat defaultFormat = ExportPipeline.defaultFormat(consoleSettings, "DUMP");
		PullStatement statement = PullCommandParser.parse(PullCommandParser.Grammar.dump(ExportPipeline.defaultResultName(consoleSettings)), afterKeyword,
				defaultFormat.token(), defaultFormat.unavailableReason());
		new ExportPipeline(this, "DUMP", "exported to", fileOpener).execute(statement);
	}

	/**
	 * {@code DUMP <table>} alone - one word, nothing else - keeps its original checks, file naming, format
	 * choice (row count and {@code DefaultFileFormat}) and message; the export itself goes through the
	 * common {@link ExportPipeline}. {@code DUMP} with no argument also stays on that path, for its original error.
	 */
	/** The worksheet name {@code DUMP <table>} alone always used: the table name, made valid for Excel/ODS. */
	private static String legacyWorksheetName(String tableName) {
		String name = SpreadsheetSheetName.sanitize(tableName);
		return "QUERIES".equalsIgnoreCase(name) ? "Data" : name;
	}

	private static boolean isLegacyTableForm(String[] args) {
		if (!CommandUtils.isValidArgs(args)) {
			return true;
		}
		String first = args[0].trim();
		// DUMP @<script> is the Scripts Library source (same as DUMP LIB <script>), never a table
		return args.length == 1 && !"/".equals(first) && !first.startsWith("(") && !first.startsWith("@");
	}

	/**
	 * Extracts the full content of the table named in {@code args} to a local file.
	 *
	 * <p>Fails with a console error if no table name is given, or if the table does not exist.
	 * Otherwise resolves the table's row count ({@code -1} if it could not be determined) and picks
	 * the file extension: a tab-separated {@code .txt} beyond {@code MaxRowXLSX}, or the extension
	 * matching {@code DefaultFileFormat}
	 * ({@link com.upandcoding.broadsql.controller.shell.ConsoleSettings#getDefaultFileExtension()})
	 * at or below it. The file is written to the configured export folder ({@code DefaultFolder}),
	 * named after {@code tableName} as typed.
	 *
	 */
	private void executeLegacyTableDump(String[] args) throws BroadSQLException {

		String tableName = null;
		String fileExtension = ".txt";
		if (CommandUtils.isValidArgs(args)) {
			tableName = args[0].trim();
		}

		if (StringUtils.isNotBlank(tableName)) {
			// The table must exist exactly (the canonical resolver of DESCR and SHOW PK/FK/REFERENCES/INDEXES):
			// never a substring or JDBC pattern match, so DUMP TRY does not pass the check because COUNTRY exists
			// The export then reads exactly the resolved table, by its quoted identity, never the name as typed
			String tableReference;
			try {
				MetadataService metadata = sqlDatabase.getMetadataService();
				tableReference = metadata.sqlName(metadata.resolveTable(tableName));
			} catch (MetadataException e) {
				console.error(e.getMessage());
				return;
			}
			// GitHub #154, section 7 ("feature-local failure"): DUMP is the one feature that actually
			// needs MaxRowXLSX (to decide the XLSX/text-file threshold below) - if the configured value
			// is present but invalid, fail clearly and locally here rather than either crashing BroadSQL
			// startup (fixed separately in ConsoleSettings) or silently applying a fallback threshold the
			// user never configured for this specific decision.
			String invalidMaxRowXlsx = consoleSettings.getMaxRowXlsxInvalidValue();
			if (invalidMaxRowXlsx != null) {
				throw new BroadSQLException("DUMP cannot determine the XLSX/text-file row-count threshold: BroadSQL setting '"
						+ SpringPropertiesConfig.MAX_ROW_XL + "' is set to '" + invalidMaxRowXlsx + "', which is not a valid integer. "
						+ "Correct " + SpringPropertiesConfig.MAX_ROW_XL + " in BroadSQL.ini and try again.");
			}

			// Check number of records
			int nbRecords = 0;
			try {
				nbRecords = sqlDatabase.getNumberOfRecords(tableName);
			} catch (BroadSQLException se) {
				nbRecords = -1;
			}
			String fileFormat = nbRecords <= consoleSettings.getMaxRowXlsx() ? consoleSettings.getDefaultFileFormat() : ConsoleSettings.FILE_FORMAT_TXT;

			// SPRINT 2309T (#163): this form is only a source/destination adapter. The table, the file named
			// after it and the format chosen above become an ordinary export request, written by the same
			// ExportPipeline as every other DUMP/PULL form; only the success message keeps its wording.
			PullStatement.Format format = PullStatement.Format.valueOf(fileFormat);
			String worksheet = format == PullStatement.Format.XLSX || format == PullStatement.Format.ODS ? legacyWorksheetName(tableName) : null;
			PullStatement statement = new PullStatement(PullStatement.SourceKind.TABLE, "SELECT * FROM " + tableReference, null, tableName, worksheet,
					format, PullStatement.Mode.OVERWRITE, null, false, false);
			try {
				new ExportPipeline(this, "DUMP", fileOpener, (rows, location, filePath) -> console.println(rows + " records extracted to " + filePath))
						.execute(statement);
			} catch (BroadSQLException se) {
				console.error(se);
				console.println("");
			}
			console.println("");
		} else {
			console.error(BroadSQLErrorMessages.ERR_GAL_01);
		}
	}
	
	@Override
	public String getDescription() {
		return "Exports a table, the previous result, a query or a library script's result to a file (CSV, TEXT, JSON, XLSX, ODS...)";
	}

	@Override
	public String getDetailedDescription() {
		return "DUMP is the export command. DUMP <table>, DUMP / (the previous result, as displayed, without running the query "
				+ "again), DUMP (<query>) and DUMP LIB <script> or DUMP @<script> (the final result of a Scripts Library script) all accept "
				+ "[TO <destination>] [AS <format>]. Formats: CSV (comma or semicolon, from the CsvSeparator setting), TEXT "
				+ "(UTF-8, tab separated), JSON, XLSX and ODS (worksheet DATA unless named, e.g. TO report.MONTHLY), H2, MD and "
				+ "HTML. Without AS, the DefaultFileFormat setting applies; without TO, the file is named after the table, the "
				+ "script, or DefaultExtFileName (results). Files are written to the export folder (DefaultFolder). PULL is a "
				+ "compatibility alias of DUMP. DUMP / exports only a result displayed in full (never cut at MaxRowsOnScreen) and "
				+ "never runs the query again. DUMP <table> alone keeps its original file naming: format from DefaultFileFormat, or "
				+ "tab separated text beyond MaxRowXLSX rows. For AS H2, XLSX and ODS, MODE APPEND adds the rows to the existing "
				+ "table or worksheet when it has the same columns, with the same names, in the same order as the result (types "
				+ "are not compared, any query can be appended); a missing destination is created. The Connections Definition File "
				+ "($CDF) cannot be a destination.";
	}

	@Override
	public String getArguments() {
		return "<source> [TO <destination>] [AS <format>], where <source> is a table name, / (the previous result), a query in "
				+ "parentheses (which may use ${name} variables), LIB <script> or @<script> (a Scripts Library script, both forms are the same, followed by optional name=value arguments) or API RESULT, <destination> is a file name (optionally <name>.<worksheet> for XLSX/ODS, "
				+ "<connection>.<table> for H2), and <format> is CSV, TEXT, JSON, XLSX, ODS, H2, MD or HTML; AS H2, XLSX and ODS also accept "
				+ "[MODE OVERWRITE | MODE APPEND] (AS H2 also MODE APPEND KEY(<column>) [FORCE]), and AS XLSX and ODS a final [OPEN]";
	}

	@Override
	public String getExamples() {
		return "DUMP CUSTOMER;\n\tDUMP CUSTOMER TO customers AS CSV;\n\tSELECT * FROM CUSTOMER WHERE COUNTRY = 'FR';\n\tDUMP /;\n\t"
				+ "DUMP / TO customers AS TEXT;\n\tDUMP (SELECT * FROM CUSTOMER WHERE COUNTRY = 'FR') TO customers.DATA AS XLSX;\n\t"
				+ "DUMP LIB sales.sql;\n\tDUMP LIB sales.sql TO sales AS JSON;\n\tDUMP @sales.sql TO sales AS CSV;\n\t"
				+ "DUMP LIB monthly.bsql region='EU' TO revenue_eu AS CSV;\n\tLET c = 'FR';\n\tDUMP (SELECT * FROM CUSTOMER WHERE COUNTRY = ${c}) TO fr AS CSV;\n\t"
				+ "DUMP (SELECT o.ID, c.NAME AS CUSTOMER, o.AMOUNT FROM ORDERS o JOIN CUSTOMER c ON c.ID = o.CUSTOMER_ID) TO WORKCOPY.ORDERS AS H2 MODE APPEND;\n\t"
				+ "DUMP / TO report.HISTORY AS XLSX MODE APPEND;";
	}

	/**
	 * SPRINT 2309T: {@code DUMP} arguments follow the export grammar, completed by
	 * {@code EntityCompletionService} ({@code /}, {@code LIB}, tables, scripts, {@code TO}, {@code AS},
	 * formats); the first position still offers table names through this declaration.
	 */
	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.TABLE);
	}
}
