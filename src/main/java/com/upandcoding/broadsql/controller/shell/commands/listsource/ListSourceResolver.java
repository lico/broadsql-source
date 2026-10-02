package com.upandcoding.broadsql.controller.shell.commands.listsource;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Decides which {@link ListSource} a {@code <@...>} token names: {@code clipboard} (case-insensitive,
 * it is a fixed keyword, not a path) for {@link ClipboardListSource}, a {@code csv:...}/{@code excel:...}/
 * {@code last:...} prefix for {@link CsvColumnListSource}/{@link ExcelColumnListSource}/
 * {@link LastResultListSource}, and anything else - the original, unprefixed case - for
 * {@link FileListSource}, exactly as {@code <@fileName>} always meant.
 *
 * <p>No Windows file path can accidentally match the {@code csv:}/{@code excel:}/{@code last:}
 * prefixes: a drive letter is always exactly one character before its colon ({@code C:\...}), never the
 * four/five/six characters {@code csv}/{@code excel}/{@code last}.
 */
public final class ListSourceResolver {

	private ListSourceResolver() {
	}

	public static ListSource resolve(String token) throws BroadSQLException {
		String trimmed = token.trim();
		if ("clipboard".equalsIgnoreCase(trimmed)) {
			return new ClipboardListSource();
		}
		if (startsWithIgnoreCase(trimmed, "csv:")) {
			return CsvColumnListSource.parse(trimmed.substring("csv:".length()));
		}
		if (startsWithIgnoreCase(trimmed, "excel:")) {
			return ExcelColumnListSource.parse(trimmed.substring("excel:".length()));
		}
		if (startsWithIgnoreCase(trimmed, "last:")) {
			return LastResultListSource.parse(trimmed.substring("last:".length()));
		}
		return new FileListSource(trimmed);
	}

	private static boolean startsWithIgnoreCase(String value, String prefix) {
		return value.regionMatches(true, 0, prefix, 0, prefix.length());
	}
}
