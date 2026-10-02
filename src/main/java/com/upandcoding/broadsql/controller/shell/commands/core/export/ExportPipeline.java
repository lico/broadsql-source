package com.upandcoding.broadsql.controller.shell.commands.core.export;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.controller.shell.commands.core.script.ScriptStatements;
import com.upandcoding.broadsql.controller.shell.scripts.PreparedSql;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptCall;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptExecutor;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptRunResult;
import com.upandcoding.broadsql.controller.shell.scripts.SqlReferences;
import com.upandcoding.broadsql.dao.JdbcBinder;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptResolver;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.LastApiExecutionResult;
import com.upandcoding.broadsql.dao.LastApiExecutionResultHolder;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultMaterializer;
import com.upandcoding.broadsql.dao.api.tabular.MaterializedApiResult;
import com.upandcoding.broadsql.dao.export.LastResultMaterializer;
import com.upandcoding.broadsql.dao.export.MaterializedTabularResult;
import com.upandcoding.broadsql.dao.export.TabularResultSpool;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.pull.PullSourceShapeValidator;
import com.upandcoding.broadsql.dao.pull.PullStatement;
import com.upandcoding.broadsql.dao.pull.PullToH2Exporter;
import com.upandcoding.broadsql.dao.pull.html.PullToHtmlExporter;
import com.upandcoding.broadsql.dao.pull.json.PullToJsonExporter;
import com.upandcoding.broadsql.dao.pull.markdown.PullToMarkdownExporter;
import com.upandcoding.broadsql.dao.pull.spreadsheet.PullToOdsExporter;
import com.upandcoding.broadsql.dao.pull.spreadsheet.PullToXlsxExporter;
import com.upandcoding.broadsql.dao.pull.text.PullToTextExporter;

/**
 * SPRINT 2309T (#161/#163/#165): the one export pipeline behind {@code DUMP} (canonical) and {@code PULL}
 * (compatibility alias). Both commands only parse their command line into a {@link PullStatement}; this
 * class then does everything else, the same way for both:
 *
 * <pre>
 * source (TABLE | QUERY | LAST_RESULT | LIBRARY_SCRIPT | API_RESULT)
 *     -> one ResultSet
 *     -> one writer per format (H2 | XLSX | ODS | CSV | TEXT | JSON | MD | HTML)
 * </pre>
 *
 * Sources never know about formats and writers never know about sources, so no source x format class
 * exists. The single exception is {@code AS H2 MODE APPEND}, whose delta filter has to be injected into
 * the source SQL itself ({@link #appendToH2}); it is only reachable from a SQL source.
 *
 * <p>Format rules (#165): {@code CSV} uses the validated {@code CsvSeparator} setting (comma or semicolon
 * only, {@link ConsoleSettings#getCsvExportSeparator()}); {@code TEXT} is always tab-separated UTF-8; JSON
 * has no separator. None reads the session's {@code SET SEPARATOR} value.
 */
final class ExportPipeline {

	/**
	 * How a successful export is reported: {@code rows} written to {@code location} (as shown in the
	 * standard message: a file path, {@code tab 'X' of <path>}, or {@code NAME.TABLE (...)} for H2);
	 * {@code filePath} is the written file, {@code null} for H2.
	 */
	@FunctionalInterface
	interface SuccessReport {
		void report(int rows, String location, String filePath);
	}

	private final Command host;
	private final String commandName;
	private final FileOpener fileOpener;
	private final SuccessReport successReport;

	/**
	 * @param doneVerb how the standard success message names what happened, e.g. {@code "pulled into"}
	 *                 (PULL's unchanged wording) or {@code "exported to"} (DUMP)
	 */
	ExportPipeline(Command host, String commandName, String doneVerb, FileOpener fileOpener) {
		this(host, commandName, fileOpener, (rows, location, filePath) -> {
			host.getConsole().println(rows + " row(s) " + doneVerb + " " + location + ".");
			host.getConsole().println("");
		});
	}

	/** With a caller-specific success message ({@code DUMP <table>} alone keeps its original wording). */
	ExportPipeline(Command host, String commandName, FileOpener fileOpener, SuccessReport successReport) {
		this.host = host;
		this.commandName = commandName;
		this.fileOpener = fileOpener;
		this.successReport = successReport;
	}

	/**
	 * The destination name used when {@code TO} is omitted for {@code /}, a query or {@code API RESULT}:
	 * the base name of the existing {@code DefaultExtFileName} setting ({@code results.xlsx} gives
	 * {@code results}).
	 */
	static String defaultResultName(ConsoleSettings settings) {
		String configured = settings.getExtractDefaultFileName() == null ? "" : settings.getExtractDefaultFileName().trim();
		String base = configured.contains(".") ? configured.substring(0, configured.lastIndexOf('.')) : configured;
		return base.isBlank() ? "results" : base;
	}

	private ShellConsole console() {
		return host.getConsole();
	}

	private ConsoleSettings settings() {
		return host.getConsoleSettings();
	}

	private DatabaseConnection sqlDatabase() {
		return host.getSqlDatabase();
	}

	private DatabaseDefinitionsVault vault() {
		return host.getDatabaseConnectionsVault();
	}

	private String currentConnectionId() {
		return sqlDatabase() != null && sqlDatabase().getPlatform() != null ? sqlDatabase().getPlatform().getId() : null;
	}

	/**
	 * The format to use when {@code AS <format>} is omitted (GitHub #152): the existing
	 * {@code DefaultFileFormat} setting. {@code token} is {@code null} when that setting is present but
	 * unrecognized; {@code unavailableReason} then says so, and only the implicit form is refused.
	 */
	record DefaultFormat(String token, String unavailableReason) {
	}

	static DefaultFormat defaultFormat(ConsoleSettings settings, String commandName) {
		String invalidDefaultFormat = settings.getDefaultFileFormatInvalidValue();
		if (invalidDefaultFormat != null) {
			return new DefaultFormat(null, commandName + " requires AS <format> because DefaultFileFormat value '" + invalidDefaultFormat
					+ "' in BroadSQL.ini is not a supported export format (expected XLSX, ODS, CSV or TXT). "
					+ "Fix DefaultFileFormat in BroadSQL.ini, or add AS <format> explicitly on this " + commandName + ", e.g. AS XLSX.");
		}
		return new DefaultFormat(settings.getDefaultFileFormat(), null);
	}

	void execute(PullStatement statement) throws BroadSQLException {
		if (statement.getSourceKind() == PullStatement.SourceKind.TABLE && statement.getSourceTable() != null) {
			// A table source reads the table metadata resolves, by its quoted identity, never the name as typed
			statement = statement.withSourceQuery("SELECT * FROM " + sqlDatabase().sqlTableReference(statement.getSourceTable()));
		}
		switch (statement.getSourceKind()) {
			case API_RESULT:
				exportApiResult(statement);
				break;
			case LAST_RESULT:
				exportLastResult(statement);
				break;
			case LIBRARY_SCRIPT:
				exportLibraryScript(statement);
				break;
			default:
				exportSqlQuery(statement);
				break;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Sources
	// ---------------------------------------------------------------------------------------------

	/** TABLE and QUERY: run the SQL on a dedicated statement of the current connection. */
	private void exportSqlQuery(PullStatement statement) throws BroadSQLException {
		if (statement.getFormat() == PullStatement.Format.H2 && statement.getMode() == PullStatement.Mode.APPEND) {
			appendToH2(statement);
			return;
		}
		Statement sourceStatement = null;
		ResultSet sourceResults = null;
		try {
			Connection sourceConnection = sqlDatabase().getDirectConnection();
			PreparedSql prepared = prepareSourceQuery(statement);
			if (prepared == null) {
				sourceStatement = sourceConnection.createStatement();
				sourceResults = sourceStatement.executeQuery(statement.getSourceQuery());
			} else {
				PreparedStatement preparedStatement = sourceConnection.prepareStatement(prepared.getJdbcText());
				sourceStatement = preparedStatement;
				JdbcBinder.bindAll(preparedStatement, prepared.getBinds());
				sourceResults = preparedStatement.executeQuery();
			}
			write(statement, sourceResults, statement.getSourceQuery(), currentConnectionId());
		} catch (SQLException e) {
			throw new BroadSQLException(e);
		} finally {
			closeAll(sourceResults, sourceStatement);
		}
	}

	/**
	 * SPRINT 0110A: the {@code ${name}} references of a parenthesized source query, bound exactly as on the SQL
	 * path (spec section 19.2); {@code null} when there is none (the query runs exactly as before) or the source is
	 * not a query (a table name is never scanned).
	 */
	private PreparedSql prepareSourceQuery(PullStatement statement) throws BroadSQLException {
		if (statement.getSourceKind() != PullStatement.SourceKind.QUERY || statement.getSourceQuery() == null) {
			return null;
		}
		DatabaseConnection db = sqlDatabase();
		PreparedSql prepared = SqlReferences.prepare(statement.getSourceQuery(), ScriptStatements.variables(host), db::isPgJdbc);
		ScriptStatements.echoBoundValues(host, prepared);
		return prepared;
	}

	/**
	 * {@code API RESULT}: the last {@code RUN} result, materialized ({@link ApiResultMaterializer}). Its
	 * JSON export is the flattened tabular shape, not the raw response body.
	 */
	private void exportApiResult(PullStatement statement) throws BroadSQLException {
		LastApiExecutionResult apiResult = LastApiExecutionResultHolder.get();
		if (apiResult == null) {
			throw new BroadSQLException(commandName + " API RESULT: no API execution result held in memory. Run RUN first.");
		}
		try (MaterializedApiResult materialized = ApiResultMaterializer.materialize(apiResult)) {
			String label = "API RESULT: " + apiResult.getEndpoint() + " (" + apiResult.getEnvironment() + ")";
			write(statement, materialized.getResultSet(), label, null);
		} catch (SQLException e) {
			throw new BroadSQLException(e);
		}
	}

	/**
	 * {@code DUMP /} (#161): the last tabular result already displayed, from the existing last-result
	 * snapshot ({@link LastQueryResultHolder}), never by running its query again.
	 */
	private void exportLastResult(PullStatement statement) throws BroadSQLException {
		LastQueryResult last = LastQueryResultHolder.get();
		String hint = "Export it with " + commandName + " (<query>) TO ... instead, which runs the query and exports every row.";
		try (MaterializedTabularResult materialized = LastResultMaterializer.materialize(last, hint)) {
			write(statement, materialized.getResultSet(), commandName + " / (previous result)", last.connectionId());
		} catch (SQLException e) {
			throw new BroadSQLException(e);
		}
	}

	/**
	 * {@code DUMP LIB <script>} (#163): runs the Scripts Library script through the one script pipeline
	 * ({@link ScriptExecutor}) in capture mode, with its named arguments, then exports its own final tabular
	 * result. SPRINT 0110A: written only when the run's status is {@code SUCCESS} (spec section 19.1); nothing is
	 * written for {@code COMPLETED_WITH_ERRORS}, {@code FAILED} (including a missing script or argument) or
	 * {@code CANCELLED}, nor when the script produces no tabular result.
	 */
	private void exportLibraryScript(PullStatement statement) throws BroadSQLException {
		String reference = statement.getLibraryScript();
		ScriptResolver resolver = new ScriptResolver(settings().getScriptsLibraryPath());
		try (TabularResultSpool spool = TabularResultSpool.open()) {
			ScriptRunResult result = ScriptExecutor.forCommand(host).runCapturingResults(
					ScriptCall.of(reference, () -> resolver.resolveLibraryScript(reference).getPath(), statement.getScriptArguments()), spool);
			if (!result.isSuccess()) {
				throw new BroadSQLException(commandName + " LIB " + reference + " not exported: the script finished with status " + result.getStatus());
			}
			if (!spool.hasResult()) {
				throw new BroadSQLException("Library script '" + reference + "' produced no exportable tabular result; nothing was exported.");
			}
			try (ResultSet captured = spool.openResult()) {
				write(statement, captured, "LIB " + reference, currentConnectionId());
			} catch (SQLException e) {
				throw new BroadSQLException(e);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Writers
	// ---------------------------------------------------------------------------------------------

	/** The single format dispatch, whatever the source. {@code sourceResults} is consumed, not closed. */
	private void write(PullStatement statement, ResultSet sourceResults, String sourceLabel, String sourceConnectionId) throws BroadSQLException {
		try {
			switch (statement.getFormat()) {
				case H2:
					overwriteH2(statement, sourceResults, sourceLabel, sourceConnectionId);
					break;
				case XLSX:
				case ODS:
					writeSpreadsheet(statement, sourceResults, sourceLabel, sourceConnectionId);
					break;
				default:
					writeFlatFile(statement, sourceResults);
					break;
			}
		} catch (SQLException e) {
			throw new BroadSQLException(e);
		}
	}

	private void overwriteH2(PullStatement statement, ResultSet sourceResults, String sourceLabel, String sourceConnectionId)
			throws BroadSQLException, SQLException {
		H2Target target = resolveH2Target(statement.getTargetName());
		int rowsWritten = new PullToH2Exporter().overwrite(target.definition, statement.getTargetTable(), sourceResults, sourceLabel, sourceConnectionId);
		reportH2(statement, target, rowsWritten, "table dropped and recreated");
	}

	/**
	 * {@code AS H2 MODE APPEND KEY(<column>) [FORCE]} - see docs/EXPORT_TO_H2.md, "Modes". Only rows whose
	 * key is greater than the target's current maximum are fetched, by filtering the source SQL itself.
	 */
	private void appendToH2(PullStatement statement) throws BroadSQLException {
		H2Target target = resolveH2Target(statement.getTargetName());
		PullToH2Exporter exporter = new PullToH2Exporter();
		Statement sourceStatement = null;
		PreparedStatement sourcePreparedStatement = null;
		ResultSet sourceResults = null;
		try {
			Connection sourceConnection = sqlDatabase().getDirectConnection();
			boolean tableExisted = exporter.tableExists(target.definition, statement.getTargetTable());
			Object watermark = tableExisted ? exporter.computeWatermark(target.definition, statement.getTargetTable(), statement.getKeyColumn()) : null;

			// SPRINT 0110A: the user's ${name} binds come first, left to right; the watermark marker the filter appends at
			// the end of the query keeps its position and is bound after them (spec section 19.2)
			PreparedSql prepared = prepareSourceQuery(statement);
			String executedQuery;
			if (watermark != null) {
				String sourceSql = prepared == null ? statement.getSourceQuery() : prepared.getJdbcText();
				executedQuery = PullSourceShapeValidator.appendKeyFilter(sourceSql, statement.getKeyColumn());
				sourcePreparedStatement = sourceConnection.prepareStatement(executedQuery);
				int userBinds = 0;
				if (prepared != null) {
					JdbcBinder.bindAll(sourcePreparedStatement, prepared.getBinds());
					userBinds = prepared.getBinds().size();
				}
				sourcePreparedStatement.setObject(userBinds + 1, watermark);
				sourceResults = sourcePreparedStatement.executeQuery();
			} else if (prepared != null) {
				executedQuery = prepared.getJdbcText();
				sourcePreparedStatement = sourceConnection.prepareStatement(executedQuery);
				JdbcBinder.bindAll(sourcePreparedStatement, prepared.getBinds());
				sourceResults = sourcePreparedStatement.executeQuery();
			} else {
				executedQuery = statement.getSourceQuery();
				sourceStatement = sourceConnection.createStatement();
				sourceResults = sourceStatement.executeQuery(executedQuery);
			}

			exporter.checkKeyType(sourceResults.getMetaData(), statement.getKeyColumn());

			if (PullSourceShapeValidator.usesOuterJoin(statement.getSourceQuery())) {
				// Unconditional - printed regardless of what checkKeyNullability concludes below, since
				// it was verified empirically (docs/EXPORT_TO_H2.md) that an outer join's unmatched-side
				// key column can pass that check with a confidently wrong "safe" answer.
				console().println("NOTE: this " + commandName + " source uses an outer join (LEFT/RIGHT/FULL) - BroadSQL cannot always "
						+ "verify whether KEY column '" + statement.getKeyColumn() + "' can be NULL for an unmatched row on "
						+ "every database driver. If it can, that row will be silently and permanently skipped by future " + commandName + "s.");
			}
			String nullabilityWarning = exporter.checkKeyNullability(sourceResults.getMetaData(), statement.getKeyColumn(), statement.isForce());
			if (nullabilityWarning != null) {
				console().println(nullabilityWarning);
			}

			int rowsWritten = exporter.append(target.definition, statement.getTargetTable(), statement.getKeyColumn(), sourceResults, tableExisted,
					executedQuery, currentConnectionId());
			reportH2(statement, target, rowsWritten, tableExisted ? "appended - delta since the last pull" : "table created");
		} catch (SQLException e) {
			throw new BroadSQLException(e);
		} finally {
			closeAll(sourceResults, sourceStatement);
			closeAll(null, sourcePreparedStatement);
		}
	}

	private void reportH2(PullStatement statement, H2Target target, int rowsWritten, String resultDescription) {
		if (target.created) {
			console().println("New H2 connection '" + statement.getTargetName() + "' created (" + target.definition.getUrl() + "), environment '"
					+ target.definition.getEnvironment() + "' (standalone, no Database Group - change via CONFIG if needed).");
		}
		successReport.report(rowsWritten, statement.getTargetName() + "." + statement.getTargetTable() + " (" + resultDescription + ")", null);
	}

	/**
	 * {@code AS XLSX}/{@code AS ODS} - see docs/PULL_TO_SPREADSHEET.md: {@code <name>} is a file in the
	 * default export folder; only the named tab (default {@code DATA}) is erased and replaced, and the
	 * {@code QUERIES} info tab records the source.
	 */
	private void writeSpreadsheet(PullStatement statement, ResultSet sourceResults, String sourceLabel, String sourceConnectionId)
			throws BroadSQLException, SQLException {
		String extension = statement.getFormat() == PullStatement.Format.XLSX ? "xlsx" : "ods";
		String filePath = settings().getExtractFolderName() + statement.getTargetName() + "." + extension;

		int rowsWritten = statement.getFormat() == PullStatement.Format.XLSX
				? new PullToXlsxExporter().writeTab(filePath, statement.getTargetTable(), sourceLabel, sourceConnectionId, sourceResults)
				: new PullToOdsExporter().writeTab(filePath, statement.getTargetTable(), sourceLabel, sourceConnectionId, sourceResults);

		successReport.report(rowsWritten, "tab '" + statement.getTargetTable() + "' of " + filePath, filePath);

		if (statement.isOpen()) {
			openExportedFile(filePath);
		}
	}

	/**
	 * The flat-file family - see docs/PULL_TO_TEXT.md: always a full overwrite of
	 * {@code <default export folder>/<name>.<extension>}.
	 */
	private void writeFlatFile(PullStatement statement, ResultSet sourceResults) throws BroadSQLException, SQLException {
		PullStatement.Format format = statement.getFormat();
		String extension;
		switch (format) {
			case CSV:
				extension = "csv";
				break;
			case TXT:
				extension = "txt";
				break;
			case JSON:
				extension = "json";
				break;
			case MD:
				extension = "md";
				break;
			case HTML:
				extension = "html";
				break;
			default:
				throw new IllegalStateException("Unexpected flat-file format: " + format);
		}
		String filePath = settings().getExtractFolderName() + statement.getTargetName() + "." + extension;

		int rowsWritten;
		switch (format) {
			case CSV:
				rowsWritten = new PullToTextExporter().writeFile(filePath, settings().getCsvExportSeparator(), sourceResults);
				break;
			case TXT:
				rowsWritten = new PullToTextExporter().writeFile(filePath, '\t', sourceResults);
				break;
			case JSON:
				rowsWritten = new PullToJsonExporter().writeFile(filePath, sourceResults);
				break;
			case MD:
				rowsWritten = new PullToMarkdownExporter().writeFile(filePath, sourceResults);
				break;
			default:
				rowsWritten = new PullToHtmlExporter().writeFile(filePath, sourceResults);
				break;
		}

		successReport.report(rowsWritten, filePath, filePath);
	}

	/**
	 * {@code OPEN} (docs/PULL_TO_SPREADSHEET.md, "OPEN") - only after a successful export. A failure is a
	 * warning: the export stays successful and the file is kept.
	 */
	private void openExportedFile(String filePath) {
		try {
			fileOpener.open(new File(filePath));
		} catch (IOException e) {
			console().println("The file was exported successfully but could not be opened (" + filePath + "): " + e.getMessage(),
					ShellConsole.MSG_WARN);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// AS H2 target connection
	// ---------------------------------------------------------------------------------------------

	private static final class H2Target {
		final DatabaseDefinition definition;
		final boolean created;

		H2Target(DatabaseDefinition definition, boolean created) {
			this.definition = definition;
			this.created = created;
		}
	}

	/**
	 * {@code <name>} of an {@code AS H2} destination (docs/EXPORT_TO_H2.md, "How AS H2 further evolved"):
	 * an existing non-H2 connection aborts; an existing H2 one is reused; an inactive one aborts (scripts
	 * must not prompt); otherwise a new H2 database is created and registered.
	 */
	private H2Target resolveH2Target(String name) throws BroadSQLException {
		if (vault().contains(name)) {
			DatabaseDefinition existing = vault().getDatabaseConnection(name);
			if (!SpringPropertiesConfig.DBTYPE_H2.equalsIgnoreCase(existing.getDbType())) {
				throw new BroadSQLException(commandName + " aborted: connection '" + name + "' already exists but is type "
						+ existing.getDbType() + ", not H2 - pick a different name for the new H2 database, "
						+ "or remove/rename the existing connection first.");
			}
			return new H2Target(existing, false);
		}
		if (vault().isInactiveConnection(name)) {
			throw new BroadSQLException(commandName + " aborted: an inactive connection already exists for ID '" + name
					+ "'. Run REACTIVATE CONNECTION " + name + " first, or pick a different name for the new H2 database.");
		}
		return new H2Target(createH2Connection(name), true);
	}

	/**
	 * Creates {@code <default export folder>/<name>.mv.db} and registers it as a standalone H2 connection
	 * in the built-in {@code LOCAL} Environment - see {@link CommandPull}'s Javadoc and
	 * docs/CONNECTION_MODEL.md. Its URL adds {@code CASE_INSENSITIVE_IDENTIFIERS=TRUE}.
	 */
	private DatabaseDefinition createH2Connection(String name) throws BroadSQLException {
		String basePath = settings().getExtractFolderName() + name;

		DatabaseDefinition def = new DatabaseDefinition(name);
		def.setDbName(name);
		def.setDbType(SpringPropertiesConfig.DBTYPE_H2);
		def.setUrl("jdbc:h2:" + basePath + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE");
		def.setUserName(CommandPull.DEFAULT_H2_USER);
		def.setUserPassword(CommandPull.DEFAULT_H2_PASSWORD);
		def.setComment("Created automatically by " + commandName);
		def.setEnvironment(vault().resolveLocalEnvironmentId());

		vault().saveDatabaseDefinition(def);
		vault().load();

		DatabaseDefinition reloaded = vault().getDatabaseConnection(name);
		if (reloaded == null) {
			throw new BroadSQLException("Failed to register new H2 connection '" + name + "'");
		}
		return reloaded;
	}

	private static void closeAll(ResultSet results, Statement statement) throws BroadSQLException {
		try {
			if (results != null) {
				results.close();
			}
			if (statement != null) {
				statement.close();
			}
		} catch (SQLException e) {
			throw new BroadSQLException(e);
		}
	}
}
