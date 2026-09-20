package com.upandcoding.broadsql.dao.pull;

/**
 * The result of parsing a {@code PULL ... TO <name>.<table> AS H2} command line (see
 * docs/EXPORT_TO_H2.md) - the only destination kind. Carries just enough to run it: the source form
 * has already been resolved to a plain SQL query, and the destination has already been split into a
 * name and a table name. {@code <name>} may or may not already be a registered connection - that is
 * resolved at execution time ({@code CommandPull}), not here.
 */
public class PullStatement {

	/**
	 * {@code MODE OVERWRITE} (the default) or {@code MODE APPEND KEY(<column>)} - see
	 * docs/EXPORT_TO_H2.md, "Modes". {@code MERGE} is recognized by {@link PullCommandParser} only well
	 * enough to produce an explicit "not implemented yet" error, so it has no value here. Only
	 * {@link Format#H2} destinations can use {@link #APPEND} - a {@link Format#XLSX}/{@link Format#ODS}
	 * destination is always {@link #OVERWRITE}, see docs/PULL_TO_SPREADSHEET.md, "Write mode".
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

	public PullStatement(String sourceQuery, String targetName, String targetTable, Format format, Mode mode, String keyColumn, boolean force) {
		this.sourceQuery = sourceQuery;
		this.targetName = targetName;
		this.targetTable = targetTable;
		this.format = format;
		this.mode = mode;
		this.keyColumn = keyColumn;
		this.force = force;
	}

	/**
	 * The plain SQL query to run against the currently connected source database - already resolved
	 * from whichever source form was typed (bare identifier, {@code /}, or a parenthesized query), and,
	 * for {@link Mode#APPEND}, already extended with the delta filter against {@link #getKeyColumn()}
	 * when the target table already holds rows (see {@code CommandPull}).
	 */
	public String getSourceQuery() {
		return sourceQuery;
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
	 * {@link Mode#OVERWRITE}, always non-null for {@link Mode#APPEND} (the parser requires it).
	 */
	public String getKeyColumn() {
		return keyColumn;
	}

	/**
	 * Whether the optional {@code FORCE} keyword followed {@code KEY(<column>)} - see
	 * docs/EXPORT_TO_H2.md, "APPEND KEY(...) opened up to joined queries", and
	 * {@link PullToH2Exporter#checkKeyNullability}. Always {@code false} for {@link Mode#OVERWRITE}.
	 */
	public boolean isForce() {
		return force;
	}
}
