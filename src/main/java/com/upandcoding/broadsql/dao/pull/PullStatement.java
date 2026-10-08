package com.upandcoding.broadsql.dao.pull;

/**
 * The result of parsing a {@code PULL ... TO <name>.<table> AS H2} command line (see
 * docs/EXPORT_TO_H2.md) - the only destination kind. Carries just enough to run it: the source form
 * has already been resolved to a plain SQL query, and the destination has already been split into a
 * name and a table name. {@code <name>} may or may not already be a registered connection - that is
 * resolved at execution time ({@code CommandPull}), not here.
 *
 * <p>SPRINT 2309T (#161/#163): this is the one export request shared by {@code DUMP} (canonical) and
 * {@code PULL} (compatibility alias) - source ({@link #getSourceKind()}), destination
 * ({@link #getTargetName()}/{@link #getTargetTable()}) and format ({@link #getFormat()}). Both commands
 * hand it to the same execution pipeline ({@code ExportPipeline}); no command-per-source or
 * command-per-format class exists.
 */
public class PullStatement {

	/**
	 * Where the rows come from (SPRINT 2309T). {@link #TABLE} and {@link #QUERY} carry a SQL query to run
	 * ({@link #getSourceQuery()}); the other three do not.
	 */
	public enum SourceKind {
		/** A bare table/view name, run as {@code SELECT * FROM <name>}. */
		TABLE,
		/** A parenthesized query, {@code (SELECT ...)}. */
		QUERY,
		/** {@code /} (DUMP and PULL): the last tabular result already displayed, exported without re-running anything. */
		LAST_RESULT,
		/** {@code LIB <script>}: the final tabular result of a Scripts Library script, captured while it runs. */
		LIBRARY_SCRIPT,
		/** {@code API RESULT}: the last {@code RUN} result held in memory. */
		API_RESULT
	}

	/**
	 * {@code MODE OVERWRITE} (the default) or {@code MODE APPEND KEY(<column>)} - see
	 * docs/EXPORT_TO_H2.md, "Modes". {@code MERGE} is recognized by {@link PullCommandParser} only well
	 * enough to produce an explicit "not implemented yet" error, so it has no value here.
	 * {@link Format#H2}, {@link Format#XLSX} and {@link Format#ODS} destinations can use {@link #APPEND}
	 * (SPRINT 1005C, #202); {@code KEY(...)} is H2-only. The flat-file family is always {@link #OVERWRITE}.
	 */
	public enum Mode {
		OVERWRITE, APPEND
	}

	/**
	 * The destination kind named by {@code AS <format>}. {@link #H2} is handled by
	 * {@link PullToH2Exporter}; {@link #XLSX}/{@link #ODS} are handled by the classes in
	 * {@link com.upandcoding.broadsql.dao.pull.spreadsheet} (see docs/PULL_TO_SPREADSHEET.md, "Relationship
	 * to PULL ... AS H2"); {@link #CSV}/{@link #TXT}/{@link #JSON}/{@link #MD}/{@link #HTML} are the
	 * "flat file" family - each handled by its own standalone exporter
	 * ({@code com.upandcoding.broadsql.dao.pull.text.PullToTextExporter},
	 * {@code com.upandcoding.broadsql.dao.pull.json.PullToJsonExporter},
	 * {@code com.upandcoding.broadsql.dao.pull.markdown.PullToMarkdownExporter},
	 * {@code com.upandcoding.broadsql.dao.pull.html.PullToHtmlExporter} - see docs/PULL_TO_TEXT.md) - every
	 * destination kind shares this one grammar and nothing else.
	 */
	public enum Format {
		H2, XLSX, ODS, CSV, TXT, JSON, MD, HTML
	}

	private final String sourceQuery;
	private final String targetName;
	private final String targetTable;
	private final Format format;
	private final Mode mode;
	private final String keyColumn;
	private final boolean force;
	private final boolean open;
	private final boolean apiResultSource;
	private SourceKind sourceKind;
	private String libraryScript;

	public PullStatement(String sourceQuery, String targetName, String targetTable, Format format, Mode mode, String keyColumn, boolean force, boolean open) {
		this(sourceQuery, targetName, targetTable, format, mode, keyColumn, force, open, false);
	}

	/**
	 * @param apiResultSource whether the source form was the fourth grammar form, {@code API RESULT} -
	 *                        see {@link #isApiResultSource()}. When {@code true}, {@code sourceQuery} is
	 *                        {@code null}: there is no SQL to run, the source is
	 *                        {@code LastApiExecutionResultHolder}'s current snapshot instead.
	 */
	public PullStatement(String sourceQuery, String targetName, String targetTable, Format format, Mode mode, String keyColumn, boolean force, boolean open,
			boolean apiResultSource) {
		this.sourceQuery = sourceQuery;
		this.targetName = targetName;
		this.targetTable = targetTable;
		this.format = format;
		this.mode = mode;
		this.keyColumn = keyColumn;
		this.force = force;
		this.open = open;
		this.apiResultSource = apiResultSource;
		this.sourceKind = apiResultSource ? SourceKind.API_RESULT : SourceKind.QUERY;
	}

	/**
	 * SPRINT 2309T: the full form, naming the source kind explicitly. {@code libraryScript} is the
	 * Scripts Library reference as typed, only for {@link SourceKind#LIBRARY_SCRIPT}.
	 */
	public PullStatement(SourceKind sourceKind, String sourceQuery, String libraryScript, String targetName, String targetTable, Format format, Mode mode,
			String keyColumn, boolean force, boolean open) {
		this(sourceQuery, targetName, targetTable, format, mode, keyColumn, force, open, sourceKind == SourceKind.API_RESULT);
		this.sourceKind = sourceKind;
		this.libraryScript = libraryScript;
	}

	private String sourceTable;
	private String scriptArguments = "";

	/**
	 * SPRINT 0110A: a {@link SourceKind#LIBRARY_SCRIPT} source's named arguments ({@code name=value ...}) as typed
	 * between the script reference and {@code TO}/{@code AS}; evaluated when the script runs.
	 */
	public PullStatement withScriptArguments(String scriptArguments) {
		this.scriptArguments = scriptArguments == null ? "" : scriptArguments;
		return this;
	}

	/** The script source's named arguments as typed; empty when none. */
	public String getScriptArguments() {
		return scriptArguments;
	}

	/**
	 * A {@link SourceKind#TABLE} source: the table as the user named it (quotes removed), resolved against the
	 * connected database only when the export runs, where {@link #withSourceQuery} replaces the provisional
	 * {@code SELECT * FROM <typed name>} with a query on the resolved, quoted table.
	 */
	public PullStatement withSourceTable(String sourceTable) {
		this.sourceTable = sourceTable;
		return this;
	}

	/** The table as named by the user for a {@link SourceKind#TABLE} source; {@code null} otherwise. */
	public String getSourceTable() {
		return sourceTable;
	}

	/** The same request reading {@code sourceQuery} instead (the resolved query of a table source). */
	public PullStatement withSourceQuery(String sourceQuery) {
		PullStatement copy = new PullStatement(sourceKind, sourceQuery, libraryScript, targetName, targetTable, format, mode, keyColumn, force, open);
		copy.sourceTable = sourceTable;
		copy.scriptArguments = scriptArguments;
		return copy;
	}

	/** Where the rows come from - see {@link SourceKind}. */
	public SourceKind getSourceKind() {
		return sourceKind;
	}

	/** The Scripts Library reference as typed after {@code LIB}; {@code null} for every other source kind. */
	public String getLibraryScript() {
		return libraryScript;
	}

	/**
	 * The plain SQL query to run against the currently connected source database - already resolved
	 * from whichever source form was typed (bare identifier, {@code /}, or a parenthesized query), and,
	 * for {@link Mode#APPEND}, already extended with the delta filter against {@link #getKeyColumn()}
	 * when the target table already holds rows (see {@code CommandPull}). {@code null} when
	 * {@link #isApiResultSource()} is {@code true} - that fourth source form has no SQL to run at all.
	 */
	public String getSourceQuery() {
		return sourceQuery;
	}

	/**
	 * Whether the source was written as the literal two-token phrase {@code API RESULT} - the fourth
	 * {@code PULL} source form, the direct sibling of {@code /} ("the last result displayed"): "the
	 * last API execution result held in memory" (see
	 * {@code com.upandcoding.broadsql.dao.LastApiExecutionResultHolder}). {@code CommandPull} materializes
	 * that result into a throwaway H2 database ({@code com.upandcoding.broadsql.dao.api.tabular.ApiResultMaterializer})
	 * to obtain a {@code ResultSet} instead of running {@link #getSourceQuery()} against the connected
	 * session database - every downstream exporter is otherwise unchanged. {@link Mode#APPEND} is
	 * rejected for this source by {@link PullCommandParser} (no source-side SQL shape to validate a
	 * delta filter against).
	 */
	public boolean isApiResultSource() {
		return apiResultSource;
	}

	/**
	 * The destination's file/database name - for {@link Format#H2} the part of the destination before the
	 * dot (e.g. {@code WORKCOPY} in {@code WORKCOPY.TOTO}); for {@link Format#XLSX}/{@link Format#ODS}
	 * likewise the part before the dot; for the flat-file family ({@link Format#CSV}/{@link Format#TXT}/
	 * {@link Format#JSON}/{@link Format#MD}/{@link Format#HTML}) the whole destination token (there is no
	 * dot - see {@link #getTargetTable()}). Already validated as a legal database/file name by
	 * {@link PullCommandParser}, but not yet resolved against the CDF vault for {@link Format#H2}.
	 */
	public String getTargetName() {
		return targetName;
	}

	/**
	 * The table to (re)create in the target database (the part of the destination after the dot, e.g.
	 * {@code TOTO} in {@code WORKCOPY.TOTO}) for a {@link Format#H2} destination, or the tab name to
	 * erase and replace (the part of the destination after the dot) for a {@link Format#XLSX}/
	 * {@link Format#ODS} destination. {@code null} for the flat-file family ({@link Format#CSV}/
	 * {@link Format#TXT}/{@link Format#JSON}/{@link Format#MD}/{@link Format#HTML}), which have no tab
	 * concept - the destination there is a bare file name, no dot allowed (see docs/PULL_TO_TEXT.md).
	 */
	public String getTargetTable() {
		return targetTable;
	}

	/** The destination kind named by {@code AS <format>}. */
	public Format getFormat() {
		return format;
	}

	public Mode getMode() {
		return mode;
	}

	/**
	 * The single column named in {@code KEY(<column>)}, exactly as typed - {@code null} for
	 * {@link Mode#OVERWRITE} and for a plain {@link Mode#APPEND} (SPRINT 1005C, #202: no {@code KEY}).
	 */
	public String getKeyColumn() {
		return keyColumn;
	}

	/**
	 * SPRINT 1005C (#202): {@code MODE APPEND} without {@code KEY(...)} - the result's rows are appended to an
	 * existing destination whose columns match the result's by count, name and order, with no SQL analysis.
	 */
	public boolean isPlainAppend() {
		return mode == Mode.APPEND && keyColumn == null;
	}

	/**
	 * Whether the optional {@code FORCE} keyword followed {@code KEY(<column>)} - see
	 * docs/EXPORT_TO_H2.md, "APPEND KEY(...) opened up to joined queries", and
	 * {@link PullToH2Exporter#checkKeyNullability}. Always {@code false} for {@link Mode#OVERWRITE}.
	 */
	public boolean isForce() {
		return force;
	}

	/**
	 * Whether the optional trailing {@code OPEN} keyword was given - see
	 * docs/PULL_TO_SPREADSHEET.md, "OPEN". Only meaningful for {@link Format#XLSX}/{@link Format#ODS}
	 * (the parser rejects {@code OPEN} for every other format); always {@code false} otherwise.
	 */
	public boolean isOpen() {
		return open;
	}
}
