package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * A source of values for BroadSQL's {@code <@...>} pattern (e.g. {@code WHERE id IN <@file>},
 * {@code IN <@clipboard>}) - see docs/TODO.md, "Patterns and SQL productivity". Every implementation
 * turns whatever it reads from (a file, the clipboard, a CSV/Excel column) into a flat, ordered list
 * of trimmed, non-blank values; {@link ListSourceResolver} decides which implementation a given
 * {@code <@...>} token maps to, and {@link SqlListLiteral} turns the result into the actual SQL text
 * that replaces the token.
 *
 * <p>Order and duplicates are preserved exactly as read - no implementation deduplicates - matching
 * {@code <@file>}'s original behavior, which nothing here changes observably.
 */
public interface ListSource {

	/**
	 * @return the source's values, trimmed, with blank entries already dropped - never {@code null},
	 *         possibly empty (an empty list is a valid result; {@link SqlListLiteral} is what decides
	 *         whether an empty list is an error)
	 */
	List<String> values() throws BroadSQLException;
}
