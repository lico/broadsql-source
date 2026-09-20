package com.upandcoding.broadsql.controller.shell.commands.core.export;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.pull.PullCommandParser;
import com.upandcoding.broadsql.dao.pull.PullSourceShapeValidator;
import com.upandcoding.broadsql.dao.pull.PullStatement;
import com.upandcoding.broadsql.dao.pull.PullToH2Exporter;
import com.upandcoding.broadsql.dao.pull.spreadsheet.PullToOdsExporter;
import com.upandcoding.broadsql.dao.pull.spreadsheet.PullToXlsxExporter;
import com.upandcoding.broadsql.dao.pull.text.PullToTextExporter;
import com.upandcoding.broadsql.dao.pull.json.PullToJsonExporter;
import com.upandcoding.broadsql.dao.pull.markdown.PullToMarkdownExporter;
import com.upandcoding.broadsql.dao.pull.html.PullToHtmlExporter;

/**
 * {@code PULL <source> TO <name>[.<destination>] AS H2 | XLSX | ODS | CSV | TXT | JSON | MD | HTML} -
 * copies a query's current results into a table of an H2 database, a tab of an {@code .xlsx}/
 * {@code .ods} file, or a whole flat file ({@code .csv}/{@code .txt}/{@code .json}/{@code .md}/
 * {@code .html}). The eight destination kinds share only this grammar and the command name - see
 * docs/PULL_TO_SPREADSHEET.md, "Relationship to PULL ... AS H2" - each is executed independently by
 * {@link #executeToH2}/{@link #executeToSpreadsheet}/{@link #executeToFlatFile}. The rest of this Javadoc
 * covers {@code AS H2} only; see docs/PULL_TO_SPREADSHEET.md for {@code AS XLSX}/{@code AS ODS}
 * (destination file resolution, tab erase-and-replace semantics, the shared "QUERIES" info tab) and
 * docs/PULL_TO_TEXT.md for the flat-file family (destination grammar, the CSV separator, JSON's number/
 * date conventions, Markdown's escaping, HTML's fragment shape, why there is no per-file metadata for any
 * of these five formats).
 *
 * <p>{@code AS H2} has two modes: {@code MODE OVERWRITE} (the default), which drops and recreates the
 * table from scratch every run, and {@code MODE APPEND KEY(<column>) [FORCE]}, which never drops or
 * updates anything - it creates the table on its first run, and on every later run inserts only the rows
 * whose key column is greater than the current maximum in the target table (computed fresh each time, not
 * tracked as separate state), so a plain re-run picks up exactly the rows added at the source since the
 * last pull. See docs/EXPORT_TO_H2.md, "Modes", for the full design discussion, including why there is
 * no keyless/blind-insert {@code APPEND}; the source query's shape is checked by
 * {@link PullSourceShapeValidator} (a {@code JOIN} is allowed, a comma-joined/derived {@code FROM} or any
 * aggregation is not), and the key column's {@code NULL}-safety is checked at runtime by
 * {@link PullToH2Exporter#checkKeyNullability} - see docs/EXPORT_TO_H2.md, "APPEND KEY(...) opened up to
 * joined queries", for the full reasoning, including the empirically confirmed limitation that this check
 * cannot be trusted for an outer join on every JDBC driver.
 *
 * <p>All three source forms are supported: a bare table/view name (a shortcut for
 * {@code SELECT * FROM <name>}), {@code /} for the last query held in memory, or a parenthesized
 * query - parentheses are mandatory for this form, e.g. {@code PULL (SELECT ...) TO ...}.
 *
 * <p>{@code <name>} (the part of the destination before the dot) is looked up in the CDF connections
 * vault. Exactly one of three things happens, see docs/EXPORT_TO_H2.md ("How AS H2 further evolved")
 * for the full design discussion:
 * <ul>
 * <li>A connection named {@code <name>} exists and is <b>not</b> H2 - the {@code PULL} is aborted with
 * an explicit error rather than touching it.</li>
 * <li>A connection named {@code <name>} exists and <b>is</b> H2 - it is reused directly, with whatever
 * credentials the CDF already holds for it, same as any normal {@code CONNECT}.</li>
 * <li>No connection named {@code <name>} exists - BroadSQL creates
 * {@code <default export folder>/<name>.mv.db}, registers it as a new H2 connection named
 * {@code <name>} (same persistence call {@code ADD CONNECTION} uses -
 * {@link com.upandcoding.broadsql.dao.DatabaseDefinitionsVault#saveDatabaseDefinition}, no extra master
 * password prompt needed since the vault is already unlocked for the session), with fixed default
 * credentials {@code sa} / {@code clipper8AD} - not real protection (identical on every install,
 * recoverable from the shipped classes), only there because BroadSQL itself needs a user name and
 * password to reopen the connection later - then proceeds exactly as the case above. Its URL also
 * includes {@code CASE_INSENSITIVE_IDENTIFIERS=TRUE} (see {@link #createH2Connection}), so a table or
 * column can be referenced later with any casing, quoted or not - existing connections are never
 * altered to add this, so they keep behaving exactly as they do today. Registered as a <b>standalone</b>
 * connection - no Database Group ({@code INSTANCE_ID} left {@code null}) - in the built-in {@code LOCAL}
 * Environment (see {@link com.upandcoding.broadsql.dao.DatabaseDefinitionsVault#resolveLocalEnvironmentId}):
 * a local extract copied from a source is not itself an environment of that source's logical database,
 * so it must never be attributed to the source's (or any other) Database Group. See
 * {@link #createH2Connection} and docs/CONNECTION_MODEL.md.</li>
 * </ul>
 *
 * <p>This retires the earlier, superseded two-keyword design ({@code AS DB} for an existing
 * connection, a path-accepting {@code AS H2} for an ephemeral file) - the choice between them
 * depended on hidden state (did a connection already exist under that name?) rather than on anything
 * about what the user was trying to do. {@code <name>} is a plain identifier now: no path, no further
 * dot, none of the characters Windows forbids in a file name, not a reserved Windows device name, and
 * at most 15 characters (the CDF's connection ID column width) - all validated by
 * {@link PullCommandParser} before this command ever runs.
 *
 * <p>In both the reuse and create cases, the target table does not need to exist beforehand: its
 * columns and types are derived entirely from the query's own result set - a column aliased with
 * {@code AS} in the source query keeps that alias as its name in the target table.
 *
 * <p>{@code MODE MERGE} is recognized by the command's grammar but not implemented yet - using it
 * fails with an explicit error rather than being silently ignored or misinterpreted.
 *
 * <p>Known limitation: unlike other long-running commands, CTRL+C does not currently cancel a PULL in
 * progress.
 */
public class CommandPull extends Command {

	/**
	 * Fixed default credentials for every H2 connection created by this command. Not a real secret -
	 * identical across every BroadSQL install and recoverable from the shipped classes - only there so
	 * a freshly created database is never left with a blank password, and BroadSQL itself (which
	 * requires a user name and password to open a connection) can reopen it later.
	 */
	static final String DEFAULT_H2_USER = "sa";
	static final String DEFAULT_H2_PASSWORD = "clipper8AD";

	public CommandPull() {
		super("PULL");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String afterKeyword = StringUtils.trimToEmpty(StringUtils.removeStartIgnoreCase(query.trim(), getKeywords()[0]));
		PullStatement statement = PullCommandParser.parse(afterKeyword, this.lastSQLQuery);

		if (statement.getFormat() == PullStatement.Format.H2) {
			executeToH2(statement);
		} else if (statement.getFormat() == PullStatement.Format.XLSX || statement.getFormat() == PullStatement.Format.ODS) {
			executeToSpreadsheet(statement);
		} else {
			executeToFlatFile(statement);
		}
	}

	private void executeToH2(PullStatement statement) throws BroadSQLException {
		String name = statement.getTargetName();
		DatabaseDefinition targetDef;
		boolean created;

		if (getDatabaseConnectionsVault().contains(name)) {
			targetDef = getDatabaseConnectionsVault().getDatabaseConnection(name);
			if (!SpringPropertiesConfig.DBTYPE_H2.equalsIgnoreCase(targetDef.getDbType())) {
				throw new BroadSQLException("PULL aborted: connection '" + name + "' already exists but is type "
						+ targetDef.getDbType() + ", not H2 - pick a different name for the new H2 database, "
						+ "or remove/rename the existing connection first.");
			}
			created = false;
		} else if (getDatabaseConnectionsVault().isInactiveConnection(name)) {
			// Unlike ADD CONNECTION's interactive wizard, PULL is often run from scripts - refuse outright
			// rather than prompting mid-PULL. REACTIVATE CONNECTION <name> first, then re-run PULL.
			throw new BroadSQLException("PULL aborted: an inactive connection already exists for ID '" + name
					+ "'. Run REACTIVATE CONNECTION " + name + " first, or pick a different name for the new H2 database.");
		} else {
			targetDef = createH2Connection(name);
			created = true;
		}

		PullToH2Exporter exporter = new PullToH2Exporter();
		Statement sourceStatement = null;
		PreparedStatement sourcePreparedStatement = null;
		ResultSet sourceResults = null;
		try {
			Connection sourceConnection = sqlDatabase.getDirectConnection();
			int rowsWritten;
			String resultDescription;

			if (statement.getMode() == PullStatement.Mode.OVERWRITE) {
				sourceStatement = sourceConnection.createStatement();
				sourceResults = sourceStatement.executeQuery(statement.getSourceQuery());
				rowsWritten = exporter.overwrite(targetDef, statement.getTargetTable(), sourceResults);
				resultDescription = "table dropped and recreated";

			} else {
				boolean tableExisted = exporter.tableExists(targetDef, statement.getTargetTable());
				Object watermark = tableExisted ? exporter.computeWatermark(targetDef, statement.getTargetTable(), statement.getKeyColumn()) : null;

				if (watermark != null) {
					String filteredSql = PullSourceShapeValidator.appendKeyFilter(statement.getSourceQuery(), statement.getKeyColumn());
					sourcePreparedStatement = sourceConnection.prepareStatement(filteredSql);
					sourcePreparedStatement.setObject(1, watermark);
					sourceResults = sourcePreparedStatement.executeQuery();
				} else {
					sourceStatement = sourceConnection.createStatement();
					sourceResults = sourceStatement.executeQuery(statement.getSourceQuery());
				}

				exporter.checkKeyType(sourceResults.getMetaData(), statement.getKeyColumn());

				if (PullSourceShapeValidator.usesOuterJoin(statement.getSourceQuery())) {
					// Unconditional - printed regardless of what checkKeyNullability concludes below, since
					// it was verified empirically (docs/EXPORT_TO_H2.md) that an outer join's unmatched-side
					// key column can pass that check with a confidently wrong "safe" answer.
					console.println("NOTE: this PULL source uses an outer join (LEFT/RIGHT/FULL) - BroadSQL cannot always "
							+ "verify whether KEY column '" + statement.getKeyColumn() + "' can be NULL for an unmatched row on "
							+ "every database driver. If it can, that row will be silently and permanently skipped by future PULLs.");
				}
				String nullabilityWarning = exporter.checkKeyNullability(sourceResults.getMetaData(), statement.getKeyColumn(), statement.isForce());
				if (nullabilityWarning != null) {
					console.println(nullabilityWarning);
				}

				rowsWritten = exporter.append(targetDef, statement.getTargetTable(), statement.getKeyColumn(), sourceResults, tableExisted);
				resultDescription = tableExisted ? "appended - delta since the last pull" : "table created";
			}

			if (created) {
				console.println("New H2 connection '" + name + "' created (" + targetDef.getUrl() + "), environment '"
						+ targetDef.getEnvironment() + "' (standalone, no Database Group - change via CONFIG if needed).");
			}
			console.println(rowsWritten + " row(s) pulled into " + name + "." + statement.getTargetTable()
					+ " (" + resultDescription + ").");
			console.println("");

		} catch (SQLException e) {
			throw new BroadSQLException(e);
		} finally {
			try {
				if (sourceResults != null) {
					sourceResults.close();
				}
				if (sourceStatement != null) {
					sourceStatement.close();
				}
				if (sourcePreparedStatement != null) {
					sourcePreparedStatement.close();
				}
			} catch (SQLException e) {
				throw new BroadSQLException(e);
			}
		}
	}

	/**
	 * {@code PULL <source> TO <name>.<tab> AS XLSX/ODS} - see docs/PULL_TO_SPREADSHEET.md. Unlike
	 * {@link #executeToH2}, {@code <name>} is resolved directly to a file in the default export folder
	 * ({@code <default export folder>/<name>.xlsx} or {@code .ods}) rather than looked up in the CDF
	 * connections vault - there is no persistent "connection" concept for a plain file
	 * (docs/PULL_TO_SPREADSHEET.md, "File resolution"). The source query is run the same way as for
	 * {@code AS H2} (a dedicated connection from {@code sqlDatabase}, so the source stays open while the
	 * target file is read/written), then handed to {@link PullToXlsxExporter}/{@link PullToOdsExporter},
	 * which own the whole erase-and-replace-the-tab, upsert-the-info-tab logic.
	 */
	private void executeToSpreadsheet(PullStatement statement) throws BroadSQLException {
		String extension = statement.getFormat() == PullStatement.Format.XLSX ? "xlsx" : "ods";
		String filePath = consoleSettings.getExtractFolderName() + statement.getTargetName() + "." + extension;

		Statement sourceStatement = null;
		ResultSet sourceResults = null;
		try {
			Connection sourceConnection = sqlDatabase.getDirectConnection();
			sourceStatement = sourceConnection.createStatement();
			sourceResults = sourceStatement.executeQuery(statement.getSourceQuery());
			String sourceConnectionId = sqlDatabase.getPlatform() != null ? sqlDatabase.getPlatform().getId() : null;

			int rowsWritten = statement.getFormat() == PullStatement.Format.XLSX
					? new PullToXlsxExporter().writeTab(filePath, statement.getTargetTable(), statement.getSourceQuery(), sourceConnectionId, sourceResults)
					: new PullToOdsExporter().writeTab(filePath, statement.getTargetTable(), statement.getSourceQuery(), sourceConnectionId, sourceResults);

			console.println(rowsWritten + " row(s) pulled into tab '" + statement.getTargetTable() + "' of " + filePath + ".");
			console.println("");

		} catch (SQLException e) {
			throw new BroadSQLException(e);
		} finally {
			try {
				if (sourceResults != null) {
					sourceResults.close();
				}
				if (sourceStatement != null) {
					sourceStatement.close();
				}
			} catch (SQLException e) {
				throw new BroadSQLException(e);
			}
		}
	}

	/**
	 * {@code PULL <source> TO <name> AS CSV/TXT/JSON/MD/HTML} - see docs/PULL_TO_TEXT.md. Unlike
	 * {@link #executeToSpreadsheet}, {@code <name>} is a bare file name (no dot, no tab/table part - a
	 * flat file has nothing to address after one). Resolves to
	 * {@code <default export folder>/<name>.<extension>}, always a full overwrite - there is no
	 * multi-tab, no info-tab tracking, and no {@code MODE} clause for any of these five formats
	 * (docs/PULL_TO_TEXT.md, "Write mode", "CSV/TXT/JSON/MD/HTML metadata"). {@code AS CSV} uses
	 * {@code consoleSettings.getCsvSeparator()} (semicolon if {@code CsvSeparator} is absent from the
	 * INI file); {@code AS TXT} always uses a literal tab - neither honors {@code SET SEP}
	 * (docs/PULL_TO_TEXT.md, "CSV separator"). {@code AS JSON}/{@code AS MD}/{@code AS HTML} have no
	 * separator concept at all - each writes its own fixed shape, see their respective exporters.
	 */
	private void executeToFlatFile(PullStatement statement) throws BroadSQLException {
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
				throw new IllegalStateException("Unexpected flat-file PULL format: " + format);
		}
		String filePath = consoleSettings.getExtractFolderName() + statement.getTargetName() + "." + extension;

		Statement sourceStatement = null;
		ResultSet sourceResults = null;
		try {
			Connection sourceConnection = sqlDatabase.getDirectConnection();
			sourceStatement = sourceConnection.createStatement();
			sourceResults = sourceStatement.executeQuery(statement.getSourceQuery());

			int rowsWritten;
			switch (format) {
				case CSV:
					rowsWritten = new PullToTextExporter().writeFile(filePath, consoleSettings.getCsvSeparator(), sourceResults);
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
				case HTML:
					rowsWritten = new PullToHtmlExporter().writeFile(filePath, sourceResults);
					break;
				default:
					throw new IllegalStateException("Unexpected flat-file PULL format: " + format);
			}

			console.println(rowsWritten + " row(s) pulled into " + filePath + ".");
			console.println("");

		} catch (SQLException e) {
			throw new BroadSQLException(e);
		} finally {
			try {
				if (sourceResults != null) {
					sourceResults.close();
				}
				if (sourceStatement != null) {
					sourceStatement.close();
				}
			} catch (SQLException e) {
				throw new BroadSQLException(e);
			}
		}
	}

	/**
	 * Creates {@code <default export folder>/<name>.mv.db}, registers it as a new H2 connection named
	 * {@code name} in the CDF vault, and returns the connection as reloaded from the vault - which,
	 * unlike the object built here, has its JDBC driver class populated (joined from the CDF's
	 * {@code TYPE} table by {@code DatabaseDefinitionsVault.load()}, not a column of {@code CONNECTIONS}
	 * itself, so it cannot be set directly on the object passed to
	 * {@code saveDatabaseDefinition()}).
	 *
	 * <p>The stored URL includes {@code CASE_INSENSITIVE_IDENTIFIERS=TRUE} (verified empirically
	 * against H2 2.3.232 - docs/EXPORT_TO_H2.md, "How AS H2 further evolved"): a table/column is still
	 * created and displayed exactly as named (the existing quoting in {@link PullToH2Exporter} is
	 * unchanged), but can then be referenced with any casing, quoted or not - avoiding the "table
	 * created as 'country', not found as COUNTRY" trap a quoted, non-uppercase identifier would
	 * otherwise cause. This is a per-connection setting, not a property of the physical file, so it
	 * only ever applies to connections created here - an existing connection's stored URL is never
	 * touched, so it keeps behaving exactly as it does today.
	 *
	 * <p>Registered as a <b>standalone</b> connection - {@code INSTANCE_ID} (Database Group) is left
	 * {@code null} - in the built-in {@code LOCAL} Environment: a local extract copied from a source is
	 * not itself an environment of that source's (or any) logical database, so PULL never attributes it
	 * to a Database Group, and never derives its Environment from the source connection or from the
	 * {@code DefaultEnvironment} INI setting (see docs/CONNECTION_MODEL.md). {@code ENVIRONMENT_ID} is
	 * resolved via {@link com.upandcoding.broadsql.dao.DatabaseDefinitionsVault#resolveLocalEnvironmentId()}
	 * rather than a hardcoded {@code "LOCAL"} literal, so it always matches whatever casing the CDF's
	 * built-in LOCAL Environment row actually has on disk (see that method's Javadoc). Both fields can be
	 * changed afterward for this connection from {@code CONFIG}.
	 */
	private DatabaseDefinition createH2Connection(String name) throws BroadSQLException {
		String basePath = consoleSettings.getExtractFolderName() + name;

		DatabaseDefinition def = new DatabaseDefinition(name);
		def.setDbName(name);
		def.setDbType(SpringPropertiesConfig.DBTYPE_H2);
		def.setUrl("jdbc:h2:" + basePath + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE");
		def.setUserName(DEFAULT_H2_USER);
		def.setUserPassword(DEFAULT_H2_PASSWORD);
		def.setComment("Created automatically by PULL");
		def.setEnvironment(getDatabaseConnectionsVault().resolveLocalEnvironmentId());

		getDatabaseConnectionsVault().saveDatabaseDefinition(def);
		getDatabaseConnectionsVault().load();

		DatabaseDefinition reloaded = getDatabaseConnectionsVault().getDatabaseConnection(name);
		if (reloaded == null) {
			throw new BroadSQLException("Failed to register new H2 connection '" + name + "'");
		}
		return reloaded;
	}

	@Override
	public String getDescription() {
		return ("Copies a query's current results into a table of an H2 database (AS H2), a tab of an Excel/ODS file "
				+ "(AS XLSX/AS ODS), or a whole flat file (AS CSV/AS TXT/AS JSON/AS MD/AS HTML)");
	}

	@Override
	public String getDetailedDescription() {
		return (getDescription() + ". AS H2 reuses an existing "
				+ "connection by name, or creates and registers one automatically; MODE OVERWRITE (the default) drops and "
				+ "recreates the table each run, MODE APPEND KEY(<column>) only inserts rows newer than the target's current "
				+ "maximum key value - the source query may use JOIN, but BroadSQL cannot always verify an outer join's key is "
				+ "never NULL, so add FORCE to proceed past that check when needed (MODE MERGE not implemented yet). AS XLSX/AS "
				+ "ODS always erase and replace the named tab, "
				+ "leaving every other tab in the file untouched, and keep a 'QUERIES' info tab listing each tab's query, date, "
				+ "source connection, and row count. Every flat-file format (CSV/TXT/JSON/MD/HTML) always fully overwrites the "
				+ "whole file - no MODE clause, no per-file metadata, since a flat file has no second tab to hold it; AS CSV uses "
				+ "CsvSeparator from the INI file (semicolon if absent), AS TXT always uses a tab (neither honors SET SEP); "
				+ "AS JSON writes a single array of objects (numbers written exactly, ISO-8601 dates); AS MD writes a "
				+ "GitHub-Flavored-Markdown table; AS HTML writes a standalone <table> fragment meant to be pasted into an "
				+ "email/wiki page. This whole command is the recommended, unified replacement for EXPORT/DUMP's file output for "
				+ "a one-shot query/table extraction (EXPORT/DUMP remain fully supported, unchanged).");
	}

	@Override
	public String getArguments() {
		return "<source> TO <name>.<destination> AS H2 [MODE OVERWRITE | MODE APPEND KEY(<column>) [FORCE]] | AS XLSX | AS ODS | "
				+ "<source> TO <name> AS CSV | AS TXT | AS JSON | AS MD | AS HTML - source is a bare table/view name, '/' for the "
				+ "last query held in memory, or a parenthesized query, e.g. (SELECT ...) - parentheses are mandatory for a "
				+ "literal query. For AS H2, <name> is a plain identifier (no path, at most 15 characters): if it already names a "
				+ "registered H2 connection it is reused, otherwise a new H2 database is created in the default export folder "
				+ "and registered under that name; <destination> is the table to (re)create. MODE APPEND KEY(<column>) requires "
				+ "a single numeric or date/time column; the source query may use JOIN (any kind), but not a comma-joined or "
				+ "derived FROM, and not GROUP BY/HAVING/ORDER BY/UNION/LIMIT/OFFSET/FETCH. BroadSQL rejects the KEY column if "
				+ "it cannot verify it is never NULL for this query; add FORCE to proceed anyway when you are certain it is safe "
				+ "(e.g. a key that is genuinely never NULL, but the driver could not confirm it - a confirmed-nullable key is "
				+ "always rejected, FORCE or not). For AS XLSX/AS ODS, <name> is a plain file name (no path) resolved directly to <name>.xlsx/.ods in "
				+ "the default export folder - no MODE clause; <destination> is the tab to erase and replace (at most 31 "
				+ "characters, none of \\ / : ? * [ ], and not 'QUERIES', reserved for the info tab). For AS CSV/AS TXT/AS JSON/"
				+ "AS MD/AS HTML, <name> is a plain file name with no dot at all (no <destination> part - a flat file has "
				+ "nothing to address after one), resolved directly to <name>.<csv|txt|json|md|html> in the default export "
				+ "folder - no MODE clause, always a full overwrite; BLOB/CLOB/binary/structural columns are rejected before "
				+ "the file is touched, for every format.";
	}

	@Override
	public String getExamples() {
		return "PULL CUSTOMER TO WORKCOPY.CUSTOMER AS H2;\n\tPULL (SELECT ID, NAME FROM CUSTOMER WHERE COUNTRY = 'FR') TO WORKCOPY.FRENCH_CUSTOMERS AS H2;\n\t"
				+ "PULL / TO WORKCOPY.LASTRESULT AS H2;\n\tPULL CUSTOMER TO WORKCOPY.CUSTOMER AS H2 MODE APPEND KEY(ID);\n\t"
				+ "PULL (SELECT C.ID AS CUSTOMER_ID, O.ID AS ORDER_ID, O.TOTAL FROM CUSTOMER C JOIN ORDERS O ON O.CUSTOMER_ID = C.ID) "
				+ "TO WORKCOPY.CUST_ORDERS AS H2 MODE APPEND KEY(ORDER_ID);\n\t"
				+ "PULL CUSTOMER TO REPORT.CUSTOMERS AS XLSX;\n\tPULL (SELECT ID, NAME FROM CUSTOMER WHERE COUNTRY = 'FR') TO REPORT.FRENCH_CUSTOMERS AS ODS;\n\t"
				+ "PULL CUSTOMER TO REPORT_CUSTOMERS AS CSV;\n\tPULL (SELECT ID, NAME FROM CUSTOMER WHERE COUNTRY = 'FR') TO FRENCH_CUSTOMERS AS TXT;\n\t"
				+ "PULL CUSTOMER TO REPORT_CUSTOMERS AS JSON;\n\tPULL CUSTOMER TO REPORT_CUSTOMERS AS MD;\n\tPULL CUSTOMER TO REPORT_CUSTOMERS AS HTML;";
	}
}
