package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.util.ArrayList;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * {@code <@clipboard>}: takes the values currently on the system clipboard, one per line - the
 * "copy one column from Excel/Calc, use it in SQL immediately" workflow (docs/TODO.md, "Spreadsheet/
 * clipboard data as query input"). Verified empirically against the real Windows clipboard
 * ({@code java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()}, non-headless) before this class
 * was written.
 *
 * <p><b>Format</b>: the clipboard is read as plain text ({@link DataFlavor#stringFlavor}) and split on
 * {@code \r\n}, {@code \r} or {@code \n} - covers a plain text-editor copy and an Excel/Calc single-
 * column copy alike. Each line is trimmed; a blank line is silently dropped (same convention as
 * {@link FileListSource}).
 *
 * <p><b>Multiple columns</b>: if any non-blank line contains a tab character (Excel/Calc's column
 * separator when more than one column is copied together), this fails clearly rather than silently
 * using only the first column or concatenating columns - v1 is single-column only (docs/TODO.md, same
 * section: "does BroadSQL take only the first column... this needs its own grammar" was left open;
 * failing clearly was chosen over guessing).
 *
 * <p><b>Empty/non-text clipboard</b>: produces an empty list, not an error - {@link SqlListLiteral} is
 * what turns "the source resolved to zero values" into a clear error, uniformly across every source
 * (so a `<@clipboard>` and a `<@file>` that both end up empty fail the same recognizable way).
 */
public class ClipboardListSource implements ListSource {

	@Override
	public List<String> values() throws BroadSQLException {
		String text = ClipboardAccess.readText();

		List<String> values = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return values;
		}
		for (String line : text.split("\r\n|\r|\n", -1)) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (trimmed.indexOf('\t') >= 0) {
				throw new BroadSQLException("Clipboard contains multiple columns. <@clipboard> expects a single column.");
			}
			values.add(trimmed);
		}
		return values;
	}
}
