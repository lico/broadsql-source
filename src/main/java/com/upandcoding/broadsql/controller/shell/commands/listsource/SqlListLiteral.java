package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Renders a {@link ListSource}'s values as the parenthesized, comma-joined SQL literal that replaces a
 * {@code <@...>} token - e.g. {@code ('A','B','C')} - shared by every source so the two correctness bugs
 * found while auditing the original {@code <@file>}-only implementation (see docs/TODO.md, "Patterns and
 * SQL productivity") are fixed once, for all of them, rather than separately (or not at all) per source:
 *
 * <ul>
 * <li><b>Embedded single quotes are now escaped</b> (doubled, the standard SQL-92 way) - the original
 * implementation did not, so a value like {@code O'BRIEN} silently produced the broken, unparseable
 * literal {@code 'O'BRIEN'}. Nothing could have depended on that broken output being preserved.</li>
 * <li><b>An empty list is now a clear, explicit error</b> instead of silently generating {@code IN ()},
 * which some databases reject outright and others accept as a (silently) always-false condition - either
 * way a behavior change from what the query's author intended, not a real "zero matches" answer.</li>
 * </ul>
 *
 * <p><b>Every value is always a quoted string literal</b>, never emitted as an unquoted number - kept
 * exactly as {@code <@file>} always worked, deliberately not "improved" with numeric auto-detection: a
 * leading-zero value ({@code 00123}) or a value that merely looks numeric but is actually a business
 * identifier is exactly the kind of surprising, silent reinterpretation the feature brief asked to avoid
 * ("prefer predictable behavior over clever inference"). This applies uniformly to every source (file,
 * clipboard, CSV column, Excel column) so they all behave the same way.
 */
public final class SqlListLiteral {

	private SqlListLiteral() {
	}

	/**
	 * @param values             the source's values, already trimmed with blanks dropped (see
	 *                           {@link ListSource#values()})
	 * @param sourceDescription  identifies the source in the error message when {@code values} is empty -
	 *                           e.g. {@code "<@clipboard>"} or {@code "File c:\temp\ids.txt"}
	 * @return {@code (val1,val2,...)}, each value single-quoted with embedded quotes doubled
	 * @throws BroadSQLException if {@code values} is empty
	 */
	public static String render(List<String> values, String sourceDescription) throws BroadSQLException {
		if (values.isEmpty()) {
			throw new BroadSQLException(sourceDescription + " contains no usable values - refusing to generate "
					+ "'IN ()', which would silently change the query's meaning instead of matching the intended rows.");
		}
		StringBuilder sb = new StringBuilder("(");
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				sb.append(",");
			}
			sb.append("'").append(values.get(i).replace("'", "''")).append("'");
		}
		sb.append(")");
		return sb.toString();
	}
}
