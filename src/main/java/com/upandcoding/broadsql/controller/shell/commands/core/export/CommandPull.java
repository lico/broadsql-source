package com.upandcoding.broadsql.controller.shell.commands.core.export;


import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.pull.PullCommandParser;
import com.upandcoding.broadsql.dao.pull.PullSourceShapeValidator;
import com.upandcoding.broadsql.dao.pull.PullStatement;
import com.upandcoding.broadsql.dao.pull.PullToH2Exporter;

/**
 * See the [Export guide](../export.md) for workflows, output formats, OPEN behavior and provenance.
 *
 * <p>{@code PULL} is a compatibility alias of {@code DUMP}, the export command: it runs exactly like
 * {@code DUMP} (same sources, optional {@code TO}, formats and defaults; {@code PULL /} exports the result
 * that was displayed, never running the query again), and every earlier {@code PULL} command line keeps
 * working. Only its success message keeps the wording "pulled into". As with {@code DUMP}, the query in
 * parentheses may use {@code ${name}} variables, bound as typed values (with {@code MODE APPEND}, the key
 * filter's value is bound after them), and a script source takes named arguments and is exported only when
 * its final status is {@code SUCCESS}.
 *
 * <p>{@code PULL <source> TO <name>[.<destination>] [AS H2 | XLSX | ODS | CSV | TXT | JSON | MD | HTML]} -
 * copies a query's current results into a table of an H2 database, a tab of an {@code .xlsx}/
 * {@code .ods} file, or a whole flat file ({@code .csv}/{@code .txt}/{@code .json}/{@code .md}/
 * {@code .html}). The eight destination kinds share only this grammar and the command name - see
 * docs/PULL_TO_SPREADSHEET.md, "Relationship to PULL ... AS H2" - each is executed independently by
 * the shared {@link ExportPipeline}. The rest of this Javadoc
 * covers {@code AS H2} only; see docs/PULL_TO_SPREADSHEET.md for {@code AS XLSX}/{@code AS ODS}
 * (destination file resolution, tab erase-and-replace semantics, the shared "QUERIES" info tab) and
 * docs/PULL_TO_TEXT.md for the flat-file family (destination grammar, the CSV separator, JSON's number/
 * date conventions, Markdown's escaping, HTML's fragment shape, why there is no per-file metadata for any
 * of these five formats).
 *
 * <p><b>GitHub #152 - both {@code AS <format>} and the destination's {@code .<table/tab>} part are
 * optional</b> (grammar simplification only, every existing explicit form keeps working unchanged - see
 * {@link com.upandcoding.broadsql.dao.pull.PullCommandParser} for the full grammar and rationale):
 * {@code PULL (...) TO toto;} means {@code PULL (...) TO toto.DATA AS <configured DefaultFileFormat>;} -
 * an omitted {@code .<table/tab>} always defaults to {@code DATA} (fixed, not configurable), and an
 * omitted {@code AS <format>} uses the {@code DefaultFileFormat} INI setting, like {@code DUMP}
 * ({@link com.upandcoding.broadsql.controller.shell.ConsoleSettings#getDefaultFileFormat()}). Unlike
 * {@code DUMP <table>} alone, a <i>present but unrecognized</i> {@code DefaultFileFormat} value refuses the implicit-
 * format form outright with a configuration error naming the setting, rather than silently substituting
 * ODS - an absent key is unaffected and still silently resolves to ODS, so this never blocks a PULL that
 * gives {@code AS <format>} explicitly.
 *
 * <p>{@code AS H2} has two modes: {@code MODE OVERWRITE} (the default), which drops and recreates the
 * table from scratch every run, and {@code MODE APPEND KEY(<column>) [FORCE]}, which never drops or
 * updates anything - it creates the table on its first run, and on every later run inserts only the rows
 * whose key column is greater than the current maximum in the target table (computed fresh each time, not
 * tracked as separate state), so a plain re-run picks up exactly the rows added at the source since the
 * last pull. See docs/EXPORT_TO_H2.md, "Modes", for the full design discussion; the source query's shape is checked by
 * {@link PullSourceShapeValidator} (a {@code JOIN} is allowed, a comma-joined/derived {@code FROM} or any
 * aggregation is not), and the key column's {@code NULL}-safety is checked at runtime by
 * {@link PullToH2Exporter#checkKeyNullability} - see docs/EXPORT_TO_H2.md, "APPEND KEY(...) opened up to
 * joined queries", for the full reasoning, including the empirically confirmed limitation that this check
 * cannot be trusted for an outer join on every JDBC driver.
 *
 * <p>{@code MODE APPEND} without {@code KEY}, for {@code AS H2}, {@code AS XLSX} and {@code AS ODS} and every
 * source, adds every row of the result to the existing table or worksheet when it has the same number of columns,
 * with the same names, in the same order as the result; types are not compared and the query is not examined. A
 * missing destination is created; a different column structure is refused before anything is written. See
 * {@code DUMP}.
 *
 * <p>The Connections Definition File ({@code $CDF}), or any connection that opens the same database file, is
 * refused as an {@code AS H2} destination.
 *
 * <p>All four source forms are supported: a bare table/view name (a shortcut for
 * {@code SELECT * FROM <name>}), {@code /} for the last result displayed (never re-run), a parenthesized
 * query - parentheses are mandatory for this form, e.g. {@code PULL (SELECT ...) TO ...} - and, since
 * the XT02 overnight batch's sub-sprint 7 ("API result export and local snapshot"), the literal
 * two-token phrase {@code API RESULT} for the last {@code RUN} result held in memory
 * (see {@link com.upandcoding.broadsql.dao.LastApiExecutionResultHolder}) - the direct sibling of {@code /}
 * for API results rather than SQL results. {@code MODE APPEND} is rejected for this fourth source (no
 * source-side SQL shape to validate a delta filter against); {@code AS JSON} for this source exports the
 * <i>flattened tabular</i> shape (its columns), not the original raw response body - see
 * {@link ExportPipeline}.
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
 * includes {@code CASE_INSENSITIVE_IDENTIFIERS=TRUE} (see {@code ExportPipeline#createH2Connection}), so a table or
 * column can be referenced later with any casing, quoted or not - existing connections are never
 * altered to add this, so they keep behaving exactly as they do today. Registered as a <b>standalone</b>
 * connection - no Database Group ({@code INSTANCE_ID} left {@code null}) - in the built-in {@code LOCAL}
 * Environment (see {@link com.upandcoding.broadsql.dao.DatabaseDefinitionsVault#resolveLocalEnvironmentId}):
 * a local extract copied from a source is not itself an environment of that source's logical database,
 * so it must never be attributed to the source's (or any other) Database Group. See
 * {@code ExportPipeline#createH2Connection} and docs/CONNECTION_MODEL.md.</li>
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

	/**
	 * The {@code OPEN} keyword's implementation seam - real production code never touches this, but a
	 * test substitutes a fake here instead of letting a real desktop application launch (see
	 * {@link FileOpener}, {@link DesktopFileOpener}).
	 */
	FileOpener fileOpener = DesktopFileOpener.INSTANCE;

	public CommandPull() {
		super("PULL");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String afterKeyword = textAfterKeyword(query);

		// GitHub #152: AS <format> is optional - the INI-configured DefaultFileFormat is resolved up front,
		// and only consulted when the command line omits AS. A key that is present but holds an
		// unrecognized value refuses the implicit-format form outright rather than silently substituting ODS.
		ExportPipeline.DefaultFormat defaultFormat = ExportPipeline.defaultFormat(consoleSettings, "PULL");
		String defaultFormatToken = defaultFormat.token();
		String defaultFormatUnavailableReason = defaultFormat.unavailableReason();

		PullStatement statement = PullCommandParser.parse(PullCommandParser.Grammar.pull(ExportPipeline.defaultResultName(consoleSettings)),
				afterKeyword, defaultFormatToken, defaultFormatUnavailableReason);

		// SPRINT 2309T (#163): PULL is the compatibility spelling of DUMP - same grammar, same request, same
		// pipeline, same meaning of "/" (the displayed result, never re-run); only its "pulled into" wording
		// is its own.
		new ExportPipeline(this, "PULL", "pulled into", fileOpener).execute(statement);
	}

	@Override
	public String getDescription() {
		return "Compatibility alias of DUMP: exports a query's results to a file or an H2 database";
	}

	@Override
	public String getDetailedDescription() {
		return "PULL is kept so existing scripts keep running; DUMP is the export command to use and document. PULL runs "
				+ "exactly like DUMP (same sources, optional TO, formats, defaults and files, see HELP DUMP): PULL / exports the "
				+ "displayed result and never runs the query again. AS H2 reuses or creates the named H2 connection; MODE OVERWRITE (the default) recreates "
				+ "the table, MODE APPEND KEY(<column>) [FORCE] inserts only rows whose key is greater than the target's current "
				+ "maximum, MODE APPEND (no KEY) adds every row when the table has the same columns, names and order as the result, "
				+ "and every run is recorded in BROADSQL_PULL_AUDIT. AS XLSX and AS ODS replace one worksheet (or append to it with "
				+ "MODE APPEND) and keep a QUERIES worksheet; they accept a trailing OPEN to open the file afterwards. The Connections "
				+ "Definition File ($CDF) cannot be a destination.";
	}

	@Override
	public String getArguments() {
		return "<source> [TO <destination>] [AS <format>] [MODE OVERWRITE | MODE APPEND | MODE APPEND KEY(<column>) [FORCE]] [OPEN], with the "
				+ "sources, destinations and formats of DUMP";
	}

	@Override
	public String getExamples() {
		return "PULL CUSTOMER TO WORKCOPY.CUSTOMER AS H2;\n\tPULL CUSTOMER TO WORKCOPY.CUSTOMER AS H2 MODE APPEND KEY(ID);\n\t"
				+ "PULL (SELECT * FROM NEW_ORDERS) TO WORKCOPY.ORDERS AS H2 MODE APPEND;\n\t"
				+ "PULL (SELECT ID, NAME FROM CUSTOMER WHERE COUNTRY = 'FR') TO REPORT.FRENCH_CUSTOMERS AS XLSX OPEN;\n\t"
				+ "PULL CUSTOMER TO REPORT_CUSTOMERS AS CSV;\n\tPULL API RESULT TO API_USERS AS JSON;\n\t"
				+ "PULL (SELECT * FROM ORDERS WHERE CUSTOMER_ID = ${id}) TO WORKCOPY.ORDERS AS H2 MODE APPEND KEY(ID);";
	}
}
