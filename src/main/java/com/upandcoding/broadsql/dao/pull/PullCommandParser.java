package com.upandcoding.broadsql.dao.pull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Parses a {@code PULL} command line into a {@link PullStatement}, per the grammar in
 * docs/EXPORT_TO_H2.md:
 *
 * <pre>
 * PULL &lt;source&gt; TO &lt;name&gt;.&lt;table&gt; AS H2 [MODE &lt;mode&gt;]
 * PULL &lt;source&gt; TO &lt;name&gt;.&lt;tab&gt;   AS XLSX | ODS
 * PULL &lt;source&gt; TO &lt;name&gt;         AS CSV | TXT | JSON | MD | HTML
 *
 * &lt;source&gt;      ::= &lt;identifier&gt;             -- shortcut for SELECT * FROM &lt;identifier&gt;
 *                 | "/"                       -- the last query held in memory
 *                 | "(" &lt;query&gt; ")"            -- parentheses are MANDATORY here, always
 * </pre>
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
 * explicit "not implemented yet" error. Every other
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
	 * @param lastSqlQuery the last raw SQL query held in memory ({@link com.upandcoding.broadsql.controller.shell.commands.Command#getLastSQLQuery()}),
	 *                     used to resolve a {@code /} source; may be {@code null}
	 * @throws BroadSQLException on any grammar violation or explicitly unsupported clause - the
	 *                           message is meant to be shown to the user as-is
	 */
	public static PullStatement parse(String afterKeyword, String lastSqlQuery) throws BroadSQLException {
		String rest = StringUtils.trimToEmpty(afterKeyword);
		if (rest.isEmpty()) {
			throw new BroadSQLException("PULL requires a source and a destination, e.g. PULL TOTO TO WORKCOPY.TOTO AS H2");
		}

		ParsedSource parsedSource = parseSource(rest, lastSqlQuery);
		rest = parsedSource.remainder;

		rest = consumeKeyword(rest, "TO", "PULL requires TO <destination> after the source");

		String destinationToken = nextToken(rest);
		if (destinationToken == null) {
			throw new BroadSQLException("PULL requires a destination after TO, e.g. WORKCOPY.TOTO");
		}
		rest = StringUtils.trimToEmpty(StringUtils.removeStart(rest, destinationToken));

		String asRequiredMessage = "AS <format> is required after the destination, e.g. AS H2, AS XLSX, AS ODS, AS CSV, or AS TXT";
		if (rest.isEmpty()) {
			throw new BroadSQLException(asRequiredMessage);
		}
		rest = consumeKeyword(rest, "AS", asRequiredMessage);

		String formatToken = nextToken(rest);
		if (formatToken == null) {
			throw new BroadSQLException("AS requires a format, e.g. AS H2");
		}
		rest = StringUtils.trimToEmpty(StringUtils.removeStart(rest, formatToken));

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
		} else if ("TXT".equalsIgnoreCase(formatToken)) {
			format = PullStatement.Format.TXT;
		} else if ("JSON".equalsIgnoreCase(formatToken)) {
			format = PullStatement.Format.JSON;
		} else if ("MD".equalsIgnoreCase(formatToken)) {
			format = PullStatement.Format.MD;
		} else if ("HTML".equalsIgnoreCase(formatToken)) {
			format = PullStatement.Format.HTML;
		} else {
			throw new BroadSQLException("AS " + formatToken + " is not supported - use AS H2, AS XLSX, AS ODS, AS CSV, AS TXT, AS JSON, AS MD, or AS HTML");
		}

		PullStatement.Mode mode = PullStatement.Mode.OVERWRITE;
		String keyColumn = null;
		boolean force = false;

		if (!rest.isEmpty()) {
			rest = consumeKeyword(rest, "MODE", "Unexpected text after AS " + formatToken + ": '" + rest + "' (only an optional MODE clause is supported)");

			String modeToken = nextToken(rest);
			if (modeToken == null) {
				throw new BroadSQLException("MODE requires a value, e.g. MODE OVERWRITE or MODE APPEND KEY(ID)");
			}
			rest = StringUtils.trimToEmpty(StringUtils.removeStart(rest, modeToken));

			if (format != PullStatement.Format.H2) {
				throw new BroadSQLException("MODE is not supported for AS " + formatToken + " - PULL always fully overwrites the "
						+ "destination (see docs/PULL_TO_SPREADSHEET.md/docs/PULL_TO_TEXT.md, \"Write mode\"); drop the MODE clause.");
			}

			if ("MERGE".equalsIgnoreCase(modeToken)) {
				throw new BroadSQLException("MODE MERGE is not implemented yet - only MODE OVERWRITE (the default) and MODE APPEND KEY(<column>) are currently supported");
			} else if ("APPEND".equalsIgnoreCase(modeToken)) {
				mode = PullStatement.Mode.APPEND;
				AppendKeyClause appendKey = parseAppendKey(rest);
				keyColumn = appendKey.keyColumn;
				force = appendKey.force;
				rest = "";
			} else if ("OVERWRITE".equalsIgnoreCase(modeToken)) {
				if (!rest.isEmpty()) {
					throw new BroadSQLException("Unexpected text after MODE OVERWRITE: '" + rest + "'");
				}
			} else {
				throw new BroadSQLException("Unknown MODE '" + modeToken + "' - only OVERWRITE and APPEND KEY(<column>) are currently supported");
			}
		}

		String targetName;
		String targetTable;

		if (isFlatFileFormat(format)) {
			if (destinationToken.contains(".")) {
				throw new BroadSQLException("AS " + formatToken + " destination must be a plain file name with no dot, e.g. WORKCOPY - "
						+ "a " + formatToken + " file has no tab/table to address after a dot, unlike AS H2/XLSX/ODS.");
			}
			targetName = destinationToken;
			targetTable = null;
			validateFileName(targetName, formatToken);

		} else {
			if (!destinationToken.contains(".")) {
				throw new BroadSQLException("AS " + formatToken + " destination must be of the form <name>.<" + (format == PullStatement.Format.H2 ? "table" : "tab") + ">, e.g. WORKCOPY.TOTO");
			}
			targetName = StringUtils.substringBefore(destinationToken, ".");
			targetTable = StringUtils.substringAfter(destinationToken, ".");
			if (StringUtils.isBlank(targetName) || StringUtils.isBlank(targetTable)) {
				throw new BroadSQLException("AS " + formatToken + " destination must be of the form <name>.<" + (format == PullStatement.Format.H2 ? "table" : "tab") + ">, e.g. WORKCOPY.TOTO");
			}

			if (format == PullStatement.Format.H2) {
				validateH2Name(targetName);
			} else {
				validateFileName(targetName, formatToken);
				validateSheetName(targetTable);
			}
		}

		if (mode == PullStatement.Mode.APPEND) {
			PullSourceShapeValidator.validateEligible(parsedSource.sourceQuery);
		}

		return new PullStatement(parsedSource.sourceQuery, targetName, targetTable, format, mode, keyColumn, force);
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
		final String sourceQuery;
		final String remainder;

		ParsedSource(String sourceQuery, String remainder) {
			this.sourceQuery = sourceQuery;
			this.remainder = remainder;
		}
	}

	private static ParsedSource parseSource(String rest, String lastSqlQuery) throws BroadSQLException {
		if (rest.startsWith("(")) {
			int closingIndex = findMatchingParen(rest, 0);
			if (closingIndex < 0) {
				throw new BroadSQLException("Unbalanced parentheses in PULL source query");
			}
			String innerQuery = rest.substring(1, closingIndex).trim();
			if (innerQuery.isEmpty()) {
				throw new BroadSQLException("PULL source query cannot be empty");
			}
			String remainder = StringUtils.trimToEmpty(rest.substring(closingIndex + 1));
			return new ParsedSource(innerQuery, remainder);
		}

		if (rest.equals("/") || rest.startsWith("/ ") || rest.startsWith("/\t")) {
			if (StringUtils.isBlank(lastSqlQuery)) {
				throw new BroadSQLException("No command in memory");
			}
			String remainder = StringUtils.trimToEmpty(rest.substring(1));
			return new ParsedSource(lastSqlQuery, remainder);
		}

		String token = nextToken(rest);
		if ("SELECT".equalsIgnoreCase(token)) {
			throw new BroadSQLException("A bare, unparenthesized query is not accepted by PULL - wrap it in parentheses: PULL (" + rest + ") TO ...");
		}
		String remainder = StringUtils.trimToEmpty(StringUtils.removeStart(rest, token));
		return new ParsedSource("SELECT * FROM " + token, remainder);
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
