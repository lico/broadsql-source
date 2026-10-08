package com.upandcoding.broadsql.dao.pull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptArguments;

/**
 * Parses a {@code PULL} command line into a {@link PullStatement}, per the grammar in
 * docs/EXPORT_TO_H2.md:
 *
 * <pre>
 * PULL &lt;source&gt; TO &lt;name&gt;[.&lt;table&gt;] [AS H2 [MODE &lt;mode&gt;]]
 * PULL &lt;source&gt; TO &lt;name&gt;[.&lt;tab&gt;]   [AS XLSX | ODS [OPEN]]
 * PULL &lt;source&gt; TO &lt;name&gt;           [AS CSV | TXT | JSON | MD | HTML]
 *
 * &lt;source&gt;      ::= &lt;identifier&gt;             -- shortcut for SELECT * FROM &lt;identifier&gt;
 *                 | "/"                       -- the last result displayed (never re-run)
 *                 | "(" &lt;query&gt; ")"            -- parentheses are MANDATORY here, always
 *                 | "API" "RESULT"             -- the last API execution result held in memory
 * </pre>
 *
 * <p><b>GitHub #152 - both {@code AS <format>} and the destination's {@code .<table/tab>} part are now
 * optional</b> (grammar simplification only - every explicit form above continues to work unchanged):
 * an omitted {@code .<table/tab>} defaults to {@link #DEFAULT_TARGET_TABLE} ({@code DATA}, fixed, not
 * configurable), applied identically regardless of whether {@code AS <format>} was typed or defaulted;
 * an omitted {@code AS <format>} uses the caller-resolved default passed to
 * {@link #parse(String, String, String, String)} (in production, {@code CommandPull} resolves this from
 * the existing {@code DefaultFileFormat} INI setting via {@link com.upandcoding.broadsql.controller.shell.ConsoleSettings#getDefaultFileFormat()}
 * - see that method's Javadoc for the two-argument {@link #parse(String, String)} overload, which keeps
 * {@code AS <format>} mandatory exactly as before, unaffected). Explicit values always win: {@code PULL
 * (...) TO toto.MYSHEET AS ODS} is completely unaffected by either default.
 *
 * <p>The fourth source form, the literal two-token phrase {@code API RESULT} (checked before the
 * generic one-token-identifier branch, which only ever consumes one token and can therefore never
 * collide with it - a real table literally named {@code API} still parses as an ordinary identifier
 * source, since its very next token is never the literal word {@code RESULT} in that case), is the
 * direct sibling of {@code /}: "the last result displayed" becomes "the last API execution result
 * held in memory" (see {@code com.upandcoding.broadsql.dao.LastApiExecutionResultHolder}). Every
 * destination kind below still applies unchanged - {@code AS H2}/{@code AS XLSX}/{@code AS ODS} take
 * a {@code <name>.<table/tab>} destination, the flat-file family a bare {@code <name>} - except that
 * {@code MODE APPEND} is always rejected for this source (there is no source-side SQL shape for
 * {@link PullSourceShapeValidator} to validate a delta filter against): only the default
 * {@code MODE OVERWRITE} applies to an API result.
 *
 * <p>{@code OPEN} (optional, {@code AS XLSX}/{@code AS ODS} only - see docs/PULL_TO_SPREADSHEET.md,
 * "OPEN"): after a successful export, ask the operating system to open the resulting file with its
 * registered/default application. Not supported for any other format.
 *
 * <p>Eight destination kinds share this one grammar: {@code AS H2} (see docs/EXPORT_TO_H2.md),
 * {@code AS XLSX}/{@code AS ODS} (see docs/PULL_TO_SPREADSHEET.md), and the "flat file" family -
 * {@code AS CSV}/{@code AS TXT}/{@code AS JSON}/{@code AS MD}/{@code AS HTML} (see docs/PULL_TO_TEXT.md)
 * - the destination kinds share nothing beyond this grammar, each is executed by its own, independent
 * exporter. This grammar retires the earlier, superseded two-keyword design ({@code AS DB} + a
 * path-accepting {@code AS H2}), see docs/EXPORT_TO_H2.md, "How AS H2 further evolved". {@code AS DB}
 * is recognized just well enough to produce an explicit, no-longer-supported error rather than being
 * silently misinterpreted.
 *
 * <p>{@code AS H2} implements two modes, see docs/EXPORT_TO_H2.md ("Modes"): {@code MODE OVERWRITE}
 * (the default when {@code MODE} is omitted), and {@code MODE APPEND KEY(<column>) [FORCE]} - a single,
 * orderable column is mandatory for {@code APPEND} (there is no keyless, blind-insert variant - see
 * "APPEND redesigned, MERGE deferred" in that doc for why), and the source query's shape is checked by
 * {@link PullSourceShapeValidator} (a {@code JOIN} is allowed; a comma-joined or derived {@code FROM},
 * or any aggregation, is not - see "APPEND KEY(...) opened up to joined queries" in that doc). The
 * optional trailing {@code FORCE} lets {@link PullToH2Exporter#checkKeyNullability} proceed when the
 * key column's nullability could not be verified, rather than refusing outright - see that method's
 * Javadoc. {@code MODE MERGE} is recognized the same way as {@code AS DB} - just well enough for an
 * explicit "not implemented yet" error.
 *
 * <p><b>SPRINT 1005C (#202): plain {@code MODE APPEND}</b> (no {@code KEY}) for {@code AS H2},
 * {@code AS XLSX} and {@code AS ODS}, from any source: the rows of the final result are appended to the
 * existing table or tab when its columns have the same count, names and order as the result (no type
 * comparison); a missing table or tab is created. The SQL is never analyzed, so no shape restriction
 * applies; {@code KEY(...)} keeps everything described above, and stays H2-only. The paragraph below
 * describes the formats as they were before; {@code AS XLSX}/{@code AS ODS} now accept
 * {@code MODE OVERWRITE}/{@code MODE APPEND}, optionally followed by {@code OPEN}. Every other
 * format ({@code AS XLSX}/{@code AS ODS} and the whole flat-file family) does not accept a {@code MODE}
 * clause at all - a full, unconditional overwrite is the only behavior (an erase-and-replace of just the
 * named tab for XLSX/ODS, see docs/PULL_TO_SPREADSHEET.md, "Write mode"; the whole file for the flat-file
 * family, see docs/PULL_TO_TEXT.md, "Write mode" - there is no tab to isolate a partial rewrite to).
 *
 * <p>The flat-file family's destination is a bare {@code <name>}, unlike {@code AS H2}/{@code AS XLSX}/
 * {@code AS ODS}'s {@code <name>.<table/tab>} - a dot in the destination is a parse error for these five
 * formats ({@link #isFlatFileFormat}), see docs/PULL_TO_TEXT.md, "Destination grammar", for why a flat
 * file has no second-part concept to address.
 */
public final class PullCommandParser {

	/**
	 * GitHub #152: the sheet/table part of the destination when the user omits it - {@code PULL (...) TO
	 * toto AS XLSX} means {@code PULL (...) TO toto.DATA AS XLSX}. Fixed, not configurable as part of
	 * this sprint (see the sprint instructions, "Do not make the default sheet configurable").
	 */
	static final String DEFAULT_TARGET_TABLE = "DATA";

	private static final int MAX_NAME_LENGTH = 15; // CDF CONNECTIONS.ID is VARCHAR_IGNORECASE(15)

	private static final Pattern ILLEGAL_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1F]");

	private static final Set<String> RESERVED_WINDOWS_NAMES = new HashSet<>(Arrays.asList(
			"CON", "PRN", "AUX", "NUL",
			"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
			"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"));

	private PullCommandParser() {
	}

	/**
	 * The "flat file" family (docs/PULL_TO_TEXT.md): a bare {@code <name>} destination, no dot, no
	 * {@code MODE} clause, always a full overwrite - as opposed to {@code AS H2}/{@code AS XLSX}/
	 * {@code AS ODS}'s {@code <name>.<table/tab>} destination.
	 */
	private static boolean isFlatFileFormat(PullStatement.Format format) {
		return format == PullStatement.Format.CSV || format == PullStatement.Format.TXT
				|| format == PullStatement.Format.JSON || format == PullStatement.Format.MD || format == PullStatement.Format.HTML;
	}

	/**
	 * @param afterKeyword the command line with the leading {@code PULL} keyword already stripped
	 * @param lastSqlQuery no longer used (SPRINT 2309T: {@code /} is the last displayed result, never the
	 *                     last query text re-run); kept so existing callers compile unchanged
	 * @throws BroadSQLException on any grammar violation or explicitly unsupported clause - the
	 *                           message is meant to be shown to the user as-is
	 */
	public static PullStatement parse(String afterKeyword, String lastSqlQuery) throws BroadSQLException {
		return parse(afterKeyword, lastSqlQuery, null, null);
	}

	/**
	 * GitHub #152: same as {@link #parse(String, String)}, additionally accepting a caller-resolved
	 * default format for when {@code AS <format>} is omitted. The {@code PULL} spelling of the grammar,
	 * with {@code results} as the default destination name.
	 *
	 * @param defaultFormatToken             the format to use when {@code AS <format>} is omitted (e.g.
	 *                                        {@code "XLSX"}); {@code null} when no usable default exists
	 * @param defaultFormatUnavailableReason the exact error to throw when {@code AS} is omitted and
	 *                                        {@code defaultFormatToken} is {@code null}; when this is also
	 *                                        {@code null}, the generic "AS <format> is required" message
	 */
	public static PullStatement parse(String afterKeyword, String lastSqlQuery, String defaultFormatToken, String defaultFormatUnavailableReason)
			throws BroadSQLException {
		return parse(Grammar.pull("results"), afterKeyword, defaultFormatToken, defaultFormatUnavailableReason);
	}

	/**
	 * SPRINT 2309T (#161/#163): the two command-line spellings of the one export grammar. {@code DUMP} is
	 * canonical and {@code PULL} its compatibility alias: both accept exactly the same sources, optional
	 * {@code TO}, formats and clauses, with the same meaning - in particular {@code /} is always the last
	 * displayed result, never the last query run again. The spelling only changes the command name used in
	 * messages. {@code defaultResultName} names the destination of {@code /}, a query or {@code API RESULT}
	 * when {@code TO} is omitted.
	 */
	public static final class Grammar {
		final String commandName;
		final String defaultResultName;

		private Grammar(String commandName, String defaultResultName) {
			this.commandName = commandName;
			this.defaultResultName = defaultResultName;
		}

		public static Grammar pull(String defaultResultName) {
			return new Grammar("PULL", defaultResultName);
		}

		public static Grammar dump(String defaultResultName) {
			return new Grammar("DUMP", defaultResultName);
		}
	}

	/** SPRINT 2309T: same as {@link #parse(String, String, String, String)}, for either spelling of the grammar. */
	public static PullStatement parse(Grammar grammar, String afterKeyword, String defaultFormatToken, String defaultFormatUnavailableReason)
			throws BroadSQLException {
		String cmd = grammar.commandName;
		String rest = StringUtils.trimToEmpty(afterKeyword);
		if (rest.isEmpty()) {
			throw new BroadSQLException(cmd + " requires a source, e.g. " + cmd + " CUSTOMER TO CUSTOMERS AS CSV");
		}

		ParsedSource parsedSource = parseSource(grammar, rest);
		rest = parsedSource.remainder;

		String destinationToken = null;
		if ("TO".equalsIgnoreCase(nextToken(rest))) {
			rest = consumeKeyword(rest, "TO", cmd + " requires TO <destination> after the source");
			destinationToken = nextToken(rest);
			if (destinationToken == null) {
				throw new BroadSQLException(cmd + " requires a destination after TO, e.g. WORKCOPY.TOTO");
			}
			rest = StringUtils.trimToEmpty(StringUtils.removeStart(rest, destinationToken));
		}
		// else: no TO - the destination name is derived from the source below, once the format is known.

		String asRequiredMessage = "AS <format> is required after the destination, e.g. AS H2, AS XLSX, AS ODS, AS CSV, or AS TXT";

		String formatToken;
		boolean formatIsExplicit = !rest.isEmpty() && "AS".equalsIgnoreCase(nextToken(rest));
		if (formatIsExplicit) {
			rest = consumeKeyword(rest, "AS", asRequiredMessage);
			formatToken = nextToken(rest);
			if (formatToken == null) {
				throw new BroadSQLException("AS requires a format, e.g. AS H2");
			}
			rest = StringUtils.trimToEmpty(StringUtils.removeStart(rest, formatToken));
		} else if (defaultFormatToken != null) {
			// GitHub #152: AS <format> omitted entirely - use the caller-resolved default (the configured
			// DefaultFileFormat, or an already-caller-validated equivalent) exactly as if it had been typed.
			formatToken = defaultFormatToken;
		} else {
			throw new BroadSQLException(defaultFormatUnavailableReason != null ? defaultFormatUnavailableReason : asRequiredMessage);
		}

		if ("DB".equalsIgnoreCase(formatToken)) {
			throw new BroadSQLException("AS DB is no longer supported - PULL now uses a single database destination kind, AS H2, "
					+ "which reuses an existing H2 connection or creates one automatically if none exists.");
		}
		PullStatement.Format format;
		if ("H2".equalsIgnoreCase(formatToken)) {
			format = PullStatement.Format.H2;
		} else if ("XLSX".equalsIgnoreCase(formatToken)) {
			format = PullStatement.Format.XLSX;
		} else if ("ODS".equalsIgnoreCase(formatToken)) {
			format = PullStatement.Format.ODS;
		} else if ("CSV".equalsIgnoreCase(formatToken)) {
			format = PullStatement.Format.CSV;
		} else if ("TXT".equalsIgnoreCase(formatToken) || "TEXT".equalsIgnoreCase(formatToken)) {
			// SPRINT 2309T (#165): TEXT is the canonical name of the fixed UTF-8 tab-separated format; TXT
			// stays accepted for existing scripts and for the DefaultFileFormat INI value.
			format = PullStatement.Format.TXT;
		} else if ("JSON".equalsIgnoreCase(formatToken)) {
			format = PullStatement.Format.JSON;
		} else if ("MD".equalsIgnoreCase(formatToken)) {
			format = PullStatement.Format.MD;
		} else if ("HTML".equalsIgnoreCase(formatToken)) {
			format = PullStatement.Format.HTML;
		} else {
			throw new BroadSQLException("AS " + formatToken + " is not supported - use AS CSV, AS TEXT, AS JSON, AS XLSX, AS ODS, AS H2, AS MD, or AS HTML");
		}

		PullStatement.Mode mode = PullStatement.Mode.OVERWRITE;
		String keyColumn = null;
		boolean force = false;
		boolean open = false;
		boolean opensAllowed = format == PullStatement.Format.XLSX || format == PullStatement.Format.ODS;
		// SPRINT 1005C (#202): AS H2, AS XLSX and AS ODS take a MODE clause; KEY(...) stays H2-only
		boolean modeAllowed = format == PullStatement.Format.H2 || opensAllowed;

		if (!rest.isEmpty() && !(opensAllowed && "OPEN".equalsIgnoreCase(nextToken(rest)))) {
			String clauseHint = opensAllowed ? "MODE clause and OPEN" : "MODE clause";
			rest = consumeKeyword(rest, "MODE", "Unexpected text after AS " + formatToken + ": '" + rest + "' (only an optional " + clauseHint + " is supported)");

			String modeToken = nextToken(rest);
			if (modeToken == null) {
				throw new BroadSQLException("MODE requires a value, e.g. MODE OVERWRITE or MODE APPEND");
			}
			rest = StringUtils.trimToEmpty(StringUtils.removeStart(rest, modeToken));

			if (!modeAllowed) {
				throw new BroadSQLException("MODE is not supported for AS " + formatToken + " - " + cmd + " always fully overwrites the "
						+ "destination file; drop the MODE clause.");
			}

			if ("MERGE".equalsIgnoreCase(modeToken)) {
				throw new BroadSQLException("MODE MERGE is not implemented yet - only MODE OVERWRITE (the default) and MODE APPEND are currently supported");
			} else if ("APPEND".equalsIgnoreCase(modeToken)) {
				mode = PullStatement.Mode.APPEND;
				if (StringUtils.upperCase(rest).startsWith("KEY")) {
					if (format != PullStatement.Format.H2) {
						throw new BroadSQLException("MODE APPEND KEY(...) is only supported for AS H2 - AS " + formatToken
								+ " supports MODE APPEND (no KEY) and MODE OVERWRITE.");
					}
					AppendKeyClause appendKey = parseAppendKey(rest);
					keyColumn = appendKey.keyColumn;
					force = appendKey.force;
					rest = "";
				}
			} else if (!"OVERWRITE".equalsIgnoreCase(modeToken)) {
				throw new BroadSQLException("Unknown MODE '" + modeToken + "' - only OVERWRITE, APPEND and APPEND KEY(<column>) are currently supported");
			}
		}

		if (!rest.isEmpty() && opensAllowed && "OPEN".equalsIgnoreCase(nextToken(rest))) {
			rest = StringUtils.trimToEmpty(StringUtils.removeStart(rest, nextToken(rest)));
			open = true;
		}
		if (!rest.isEmpty()) {
			throw new BroadSQLException("Unexpected text after AS " + formatToken + (open ? " OPEN" : "") + ": '" + rest + "'");
		}

		String targetName;
		String targetTable;

		if (destinationToken == null) {
			// SPRINT 2309T: no TO. No new naming rule: the destination is named after what is
			// being dumped (like DUMP <table> always was), in the default export folder, sheet DATA.
			if (format == PullStatement.Format.H2) {
				throw new BroadSQLException("AS H2 requires TO <connection>.<table> - " + cmd + " never creates or reuses an H2 connection it was not named.");
			}
			targetName = parsedSource.defaultName;
			targetTable = isFlatFileFormat(format) ? null : DEFAULT_TARGET_TABLE;
			validateFileName(targetName, formatToken);

		} else if (isFlatFileFormat(format)) {
			if (destinationToken.contains(".")) {
				throw new BroadSQLException("AS " + formatToken + " destination must be a plain file name with no dot, e.g. WORKCOPY - "
						+ "a " + formatToken + " file has no tab/table to address after a dot, unlike AS H2/XLSX/ODS.");
			}
			targetName = destinationToken;
			targetTable = null;
			validateFileName(targetName, formatToken);

		} else {
			if (destinationToken.contains(".")) {
				targetName = StringUtils.substringBefore(destinationToken, ".");
				targetTable = StringUtils.substringAfter(destinationToken, ".");
				if (StringUtils.isBlank(targetName) || StringUtils.isBlank(targetTable)) {
					throw new BroadSQLException("AS " + formatToken + " destination must be of the form <name>.<" + (format == PullStatement.Format.H2 ? "table" : "tab") + ">, e.g. WORKCOPY.TOTO");
				}
			} else {
				// GitHub #152: the sheet/table part becomes optional, defaulting to DATA - PULL (...) TO
				// toto AS XLSX means PULL (...) TO toto.DATA AS XLSX. Applies identically whether AS was
				// typed or defaulted above; validation right below applies to this defaulted name exactly
				// as it would to an explicitly typed one.
				targetName = destinationToken;
				targetTable = DEFAULT_TARGET_TABLE;
			}

			if (format == PullStatement.Format.H2) {
				validateH2Name(targetName);
				if (PullAuditTable.isReservedName(targetTable)) {
					throw new BroadSQLException(PullAuditTable.TABLE_NAME + " is reserved by BroadSQL for PULL metadata - choose a different destination table.");
				}
			} else {
				validateFileName(targetName, formatToken);
				validateSheetName(targetTable);
			}
		}

		// SPRINT 1005C (#202): a plain MODE APPEND (no KEY) reads only the final result's columns, so it accepts
		// every source and never looks at the SQL; only APPEND KEY(...) filters, and therefore checks, the query.
		if (mode == PullStatement.Mode.APPEND && keyColumn != null) {
			if (parsedSource.apiResultSource) {
				throw new BroadSQLException("MODE APPEND KEY(...) is not supported for " + cmd + " API RESULT - an API result has no source-side "
						+ "SQL shape to validate a delta filter against. Use MODE APPEND (no KEY) or the default MODE OVERWRITE instead.");
			}
			if (parsedSource.sourceQuery == null) {
				throw new BroadSQLException("MODE APPEND KEY(...) is not supported for this " + cmd + " source - it has no SQL query to filter the "
						+ "delta with. Use MODE APPEND (no KEY), the default MODE OVERWRITE, or " + cmd + " (<query>) TO ... MODE APPEND KEY(<column>).");
			}
			PullSourceShapeValidator.validateEligible(parsedSource.sourceQuery);
		}

		return new PullStatement(parsedSource.kind, parsedSource.sourceQuery, parsedSource.libraryScript, targetName, targetTable, format, mode,
				keyColumn, force, open).withSourceTable(parsedSource.sourceTable).withScriptArguments(parsedSource.scriptArguments);
	}

	private static final class AppendKeyClause {
		final String keyColumn;
		final boolean force;

		AppendKeyClause(String keyColumn, boolean force) {
			this.keyColumn = keyColumn;
			this.force = force;
		}
	}

	/**
	 * Parses the mandatory {@code KEY(<column>)} clause following {@code MODE APPEND}, and the optional
	 * {@code FORCE} keyword after it - a single key column only in this version (composite keys are a
	 * {@code MODE MERGE} concept, not extended to {@code APPEND} here - see docs/EXPORT_TO_H2.md,
	 * "Modes"). {@code FORCE} lets {@link PullToH2Exporter#checkKeyNullability} proceed when the key
	 * column's nullability could not be verified rather than refusing outright - see that method's
	 * Javadoc and docs/EXPORT_TO_H2.md, "APPEND KEY(...) opened up to joined queries".
	 */
	private static AppendKeyClause parseAppendKey(String rest) throws BroadSQLException {
		String trimmedRest = StringUtils.trimToEmpty(rest);
		if (!StringUtils.upperCase(trimmedRest).startsWith("KEY")) {
			throw new BroadSQLException("MODE APPEND requires KEY(<column>), e.g. MODE APPEND KEY(ID) - PULL never inserts "
					+ "rows without checking for duplicates against a key. Use MODE OVERWRITE for a full refresh instead.");
		}
		String afterKey = StringUtils.trimToEmpty(trimmedRest.substring(3));
		if (!afterKey.startsWith("(")) {
			throw new BroadSQLException("MODE APPEND KEY must be followed by a parenthesized column name, e.g. KEY(ID)");
		}
		int closingIndex = afterKey.indexOf(')');
		if (closingIndex < 0) {
			throw new BroadSQLException("Unbalanced parentheses in MODE APPEND KEY(...)");
		}
		String inside = afterKey.substring(1, closingIndex).trim();
		if (inside.isEmpty()) {
			throw new BroadSQLException("MODE APPEND KEY(...) requires a column name, e.g. KEY(ID)");
		}
		if (inside.contains(",")) {
			throw new BroadSQLException("MODE APPEND supports a single KEY column only (got '" + inside + "') - "
					+ "composite keys are not supported by APPEND KEY in this version.");
		}
		String trailing = StringUtils.trimToEmpty(afterKey.substring(closingIndex + 1));
		if (trailing.isEmpty()) {
			return new AppendKeyClause(inside, false);
		}
		if ("FORCE".equalsIgnoreCase(trailing)) {
			return new AppendKeyClause(inside, true);
		}
		throw new BroadSQLException("Unexpected text after MODE APPEND KEY(...): '" + trailing + "' (only an optional FORCE is supported)");
	}

	/**
	 * Validates {@code name} against the rules agreed for {@code AS H2} (docs/EXPORT_TO_H2.md, "How AS
	 * H2 further evolved"): since a fresh name becomes a literal {@code <name>.mv.db} file, it must
	 * avoid every character Windows forbids in a file name and every reserved Windows device name -
	 * and, discovered empirically against the CDF schema (archives/dbTestScript.sql,
	 * {@code CONNECTIONS.ID VARCHAR_IGNORECASE(15)}), it must fit the 15-character connection ID column,
	 * a constraint not part of the original discussion but enforced here regardless.
	 */
	private static void validateH2Name(String name) throws BroadSQLException {
		if (ILLEGAL_NAME_CHARS.matcher(name).find()) {
			throw new BroadSQLException("Invalid name '" + name + "' for AS H2 - it becomes a database file name, "
					+ "so it cannot contain any of: \\ / : * ? \" < > | or control characters.");
		}
		if (RESERVED_WINDOWS_NAMES.contains(name.toUpperCase())) {
			throw new BroadSQLException("Invalid name '" + name + "' for AS H2 - '" + name.toUpperCase()
					+ "' is a reserved Windows device name and cannot be used as a database name.");
		}
		if (name.length() > MAX_NAME_LENGTH) {
			throw new BroadSQLException("Invalid name '" + name + "' for AS H2 - database names are limited to "
					+ MAX_NAME_LENGTH + " characters (got " + name.length() + ").");
		}
	}

	/**
	 * Validates {@code name} for {@code AS XLSX}/{@code AS ODS}/{@code AS CSV}/{@code AS TXT}
	 * (docs/PULL_TO_SPREADSHEET.md, "File resolution"; docs/PULL_TO_TEXT.md, "File resolution"): since it
	 * becomes a literal {@code <name>.<extension>} file name resolved directly into the default export
	 * folder (never a path - there is no connections vault involved for any of these four destinations,
	 * unlike {@code AS H2}), the same Windows file-name rules as {@link #validateH2Name} apply, minus the
	 * CDF-specific 15-character cap, which has no equivalent here.
	 */
	private static void validateFileName(String name, String formatToken) throws BroadSQLException {
		if (ILLEGAL_NAME_CHARS.matcher(name).find()) {
			throw new BroadSQLException("Invalid name '" + name + "' for AS " + formatToken + " - it becomes a file name, "
					+ "so it cannot contain any of: \\ / : * ? \" < > | or control characters.");
		}
		if (RESERVED_WINDOWS_NAMES.contains(name.toUpperCase())) {
			throw new BroadSQLException("Invalid name '" + name + "' for AS " + formatToken + " - '" + name.toUpperCase()
					+ "' is a reserved Windows device name and cannot be used as a file name.");
		}
	}

	private static final int MAX_SHEET_NAME_LENGTH = 31; // Excel's sheet-name limit, the stricter of the two formats
	private static final Pattern ILLEGAL_SHEET_NAME_CHARS = Pattern.compile("[\\\\/:?*\\[\\]]");

	/**
	 * Validates {@code name} as the tab to erase-and-replace for {@code AS XLSX}/{@code AS ODS}
	 * (docs/PULL_TO_SPREADSHEET.md, "Existing tab with the same name"): unlike {@code EXPORT}/
	 * {@code DUMP}'s {@code SpreadsheetSheetName.sanitize()}, which silently coerces an unusable guessed
	 * name, PULL rejects an invalid tab name outright with a clear error - the name was typed explicitly
	 * here, so silently renaming it would be a surprise, not a convenience. Excel's rules are used for
	 * both formats (the stricter of the two), so a name valid here is valid in either file. {@code QUERIES}
	 * is reserved for the info tab (docs/PULL_TO_SPREADSHEET.md, "Info tab") and cannot be used as a data
	 * tab name.
	 */
	private static void validateSheetName(String name) throws BroadSQLException {
		if (ILLEGAL_SHEET_NAME_CHARS.matcher(name).find()) {
			throw new BroadSQLException("Invalid tab name '" + name + "' for AS XLSX/ODS - it cannot contain any of: \\ / : ? * [ ]");
		}
		if (name.length() > MAX_SHEET_NAME_LENGTH) {
			throw new BroadSQLException("Invalid tab name '" + name + "' for AS XLSX/ODS - tab names are limited to "
					+ MAX_SHEET_NAME_LENGTH + " characters (got " + name.length() + ").");
		}
		if ("QUERIES".equalsIgnoreCase(name)) {
			throw new BroadSQLException("Invalid tab name 'QUERIES' for AS XLSX/ODS - that name is reserved for the "
					+ "per-file query log tab (see docs/PULL_TO_SPREADSHEET.md, \"Info tab\").");
		}
	}

	private static final class ParsedSource {
		final PullStatement.SourceKind kind;
		final String sourceQuery;
		final String libraryScript;
		final String remainder;
		final boolean apiResultSource;
		/** The destination name DUMP uses when {@code TO} is omitted. */
		final String defaultName;
		/** A table source: the table as named, quotes removed; {@code null} for every other source. */
		String sourceTable;
		/** SPRINT 0110A: a script source's named arguments, as typed. */
		String scriptArguments = "";

		ParsedSource(PullStatement.SourceKind kind, String sourceQuery, String libraryScript, String remainder, String defaultName) {
			this.kind = kind;
			this.sourceQuery = sourceQuery;
			this.libraryScript = libraryScript;
			this.remainder = remainder;
			this.apiResultSource = kind == PullStatement.SourceKind.API_RESULT;
			this.defaultName = defaultName;
		}
	}

	private static ParsedSource parseSource(Grammar grammar, String rest) throws BroadSQLException {
		String cmd = grammar.commandName;
		ParsedSource apiResultSource = tryParseApiResultSource(rest, grammar);
		if (apiResultSource != null) {
			return apiResultSource;
		}

		ParsedSource librarySource = tryParseLibrarySource(rest, cmd);
		if (librarySource != null) {
			return librarySource;
		}

		if (rest.startsWith("(")) {
			int closingIndex = findMatchingParen(rest, 0);
			if (closingIndex < 0) {
				throw new BroadSQLException("Unbalanced parentheses in " + cmd + " source query");
			}
			String innerQuery = rest.substring(1, closingIndex).trim();
			if (innerQuery.isEmpty()) {
				throw new BroadSQLException(cmd + " source query cannot be empty");
			}
			String remainder = StringUtils.trimToEmpty(rest.substring(closingIndex + 1));
			return new ParsedSource(PullStatement.SourceKind.QUERY, innerQuery, null, remainder, grammar.defaultResultName);
		}

		if (rest.equals("/") || rest.startsWith("/ ") || rest.startsWith("/\t")) {
			String remainder = StringUtils.trimToEmpty(rest.substring(1));
			// Resolved against the last displayed result at execution time, not here: nothing is re-run.
			return new ParsedSource(PullStatement.SourceKind.LAST_RESULT, null, null, remainder, grammar.defaultResultName);
		}

		String token = nextTableToken(rest, cmd);
		if ("SELECT".equalsIgnoreCase(token)) {
			throw new BroadSQLException("A bare, unparenthesized query is not accepted by " + cmd + " - wrap it in parentheses: " + cmd + " (" + rest + ") TO ...");
		}
		String remainder = StringUtils.trimToEmpty(rest.substring(token.length()));
		// The table as named, quotes removed: resolved against the connected database when the export runs
		// (ExportPipeline), which then reads the resolved table by its quoted identity. The query below is
		// provisional, used only to check the APPEND KEY source shape.
		String table = token.replace("\"", "");
		ParsedSource source = new ParsedSource(PullStatement.SourceKind.TABLE, "SELECT * FROM " + token, null, remainder, table);
		source.sourceTable = table;
		return source;
	}

	/**
	 * The table name that starts {@code rest}: up to the first whitespace outside double quotes, so a quoted
	 * name containing spaces ({@code "Sales Data"}, {@code PUBLIC."Sales Data"}) is one token.
	 */
	private static String nextTableToken(String rest, String cmd) throws BroadSQLException {
		boolean quoted = false;
		for (int i = 0; i < rest.length(); i++) {
			char c = rest.charAt(i);
			if (c == '"') {
				quoted = !quoted;
			} else if (!quoted && Character.isWhitespace(c)) {
				return rest.substring(0, i);
			}
		}
		if (quoted) {
			throw new BroadSQLException("Unbalanced quotes in the " + cmd + " table name: " + rest);
		}
		return rest;
	}

	/**
	 * SPRINT 2309T (#163): {@code LIB <script>} - the final tabular result of a Scripts Library script.
	 * {@code <script>} is taken as {@code LIB RUN} takes it (one word, or a double-quoted name containing
	 * spaces); resolving it is the Scripts Library's own job at execution time. Returns {@code null} when
	 * the first word is not {@code LIB}, or when nothing but {@code TO}/{@code AS} (or nothing at all)
	 * follows it - then {@code LIB} is simply a table name, as it always could be.
	 */
	private static ParsedSource tryParseLibrarySource(String rest, String cmd) throws BroadSQLException {
		if (rest.startsWith(SCRIPT_PREFIX)) {
			return parseLibraryScript(rest.substring(SCRIPT_PREFIX.length()), cmd, cmd + " " + SCRIPT_PREFIX);
		}
		String firstToken = nextToken(rest);
		if (firstToken == null || !"LIB".equalsIgnoreCase(firstToken)) {
			return null;
		}
		String afterLib = StringUtils.trimToEmpty(rest.substring(firstToken.length()));
		String scriptToken = nextToken(afterLib);
		if (scriptToken == null || "TO".equalsIgnoreCase(scriptToken) || "AS".equalsIgnoreCase(scriptToken)) {
			return null;
		}
		return parseLibraryScript(afterLib, cmd, cmd + " LIB ");
	}

	/**
	 * The {@code @} form of the Scripts Library source: {@code DUMP @QR10.sql} is {@code DUMP LIB QR10.sql},
	 * written the way a Script is run at the prompt. It produces exactly the same {@code LIBRARY_SCRIPT} source,
	 * so resolution (a Scripts Library reference, as {@code DUMP LIB} and {@code LIB RUN} take it), execution
	 * and export are shared. No table name can start with {@code @}, so the form is never ambiguous.
	 */
	private static final String SCRIPT_PREFIX = "@";

	/**
	 * The script reference that follows {@code LIB} or {@code @}: one word, or a double-quoted name containing
	 * spaces; {@code form} is how the source was written, for error messages.
	 */
	private static ParsedSource parseLibraryScript(String afterLib, String cmd, String form) throws BroadSQLException {
		String scriptToken = nextToken(afterLib);
		if (scriptToken == null || afterLib.isEmpty() || Character.isWhitespace(afterLib.charAt(0))) {
			throw new BroadSQLException(form.trim() + " requires a Scripts Library script, e.g. " + form + "sales.sql");
		}
		String script;
		String remainder;
		if (afterLib.startsWith("\"")) {
			int closing = afterLib.indexOf('"', 1);
			if (closing < 0) {
				throw new BroadSQLException("Unbalanced quotes in " + form.trim() + " script name");
			}
			script = afterLib.substring(1, closing);
			remainder = StringUtils.trimToEmpty(afterLib.substring(closing + 1));
		} else {
			script = scriptToken;
			remainder = StringUtils.trimToEmpty(afterLib.substring(scriptToken.length()));
		}
		if (StringUtils.isBlank(script)) {
			throw new BroadSQLException(form.trim() + " requires a Scripts Library script, e.g. " + form + "sales.sql");
		}
		String baseName = script.replace('\\', '/');
		baseName = baseName.substring(baseName.lastIndexOf('/') + 1);
		if (baseName.lastIndexOf('.') > 0) {
			baseName = baseName.substring(0, baseName.lastIndexOf('.'));
		}
		// SPRINT 0110A: named script arguments come right after the reference, before TO/AS (spec section 17.1)
		String[] arguments = ScriptArguments.splitBeforeToOrAs(remainder, cmd);
		ParsedSource source = new ParsedSource(PullStatement.SourceKind.LIBRARY_SCRIPT, null, script, arguments[1], baseName);
		source.scriptArguments = arguments[0];
		return source;
	}

	/**
	 * Recognizes the fourth source form, the literal two-token phrase {@code API RESULT} - see the class
	 * Javadoc. Returns {@code null} (never throws) when the two tokens don't match, so the caller falls
	 * through to the other source forms - in particular, a real single-token table literally named
	 * {@code API} still parses as an ordinary identifier source, since its next token is essentially
	 * never the literal word {@code RESULT} in that case (e.g. {@code PULL API TO ...}: the first token
	 * is {@code API}, but the second is {@code TO}, so this returns {@code null} and the identifier
	 * branch below builds {@code SELECT * FROM API} exactly as it always has).
	 */
	private static ParsedSource tryParseApiResultSource(String rest, Grammar grammar) {
		String firstToken = nextToken(rest);
		if (firstToken == null || !"API".equalsIgnoreCase(firstToken)) {
			return null;
		}
		String afterFirst = StringUtils.trimToEmpty(StringUtils.removeStart(rest, firstToken));
		String secondToken = nextToken(afterFirst);
		if (secondToken == null || !"RESULT".equalsIgnoreCase(secondToken)) {
			return null;
		}
		String remainder = StringUtils.trimToEmpty(StringUtils.removeStart(afterFirst, secondToken));
		return new ParsedSource(PullStatement.SourceKind.API_RESULT, null, null, remainder, grammar.defaultResultName);
	}

	/**
	 * Finds the index of the {@code )} matching the {@code (} at {@code openIndex}, skipping over
	 * parentheses inside single-quoted string literals (a doubled {@code ''} - the SQL-standard
	 * escaped quote - toggles the in-literal state twice, which correctly leaves it unchanged).
	 */
	private static int findMatchingParen(String s, int openIndex) {
		int depth = 0;
		boolean inLiteral = false;
		for (int i = openIndex; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\'') {
				inLiteral = !inLiteral;
			} else if (!inLiteral) {
				if (c == '(') {
					depth++;
				} else if (c == ')') {
					depth--;
					if (depth == 0) {
						return i;
					}
				}
			}
		}
		return -1;
	}

	/** Returns the next whitespace-delimited token, or {@code null} if {@code s} is blank. */
	private static String nextToken(String s) {
		String trimmed = StringUtils.trimToEmpty(s);
		if (trimmed.isEmpty()) {
			return null;
		}
		int spaceIndex = indexOfWhitespace(trimmed);
		return spaceIndex < 0 ? trimmed : trimmed.substring(0, spaceIndex);
	}

	private static int indexOfWhitespace(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (Character.isWhitespace(s.charAt(i))) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Requires {@code rest} to start with {@code keyword} (case-insensitive) as a whole token, and
	 * returns what follows it, trimmed.
	 */
	private static String consumeKeyword(String rest, String keyword, String errorIfMissing) throws BroadSQLException {
		String token = nextToken(rest);
		if (token == null || !token.equalsIgnoreCase(keyword)) {
			throw new BroadSQLException(errorIfMissing);
		}
		return StringUtils.trimToEmpty(StringUtils.removeStart(rest, token));
	}
}
