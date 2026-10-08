package com.upandcoding.broadsql.controller.shell.commands.core.export;

import java.io.File;
import java.io.IOException;

/**
 * Abstraction over "ask the operating system to open a file with its registered/default application" -
 * used by {@code PULL ... AS XLSX/ODS OPEN} (see docs/PULL_TO_SPREADSHEET.md, "OPEN"). Kept as a seam
 * separate from {@link CommandPull} purely so tests can substitute a fake instead of launching a real
 * desktop application - production code always uses {@link DesktopFileOpener#INSTANCE}.
 */
public interface FileOpener {

	/**
	 * @throws IOException if the file could not be opened - an unavailable desktop environment, no
	 *                      registered application for the file's extension, or any other OS-level
	 *                      failure. Never thrown for a missing/unreadable file check - that is the
	 *                      underlying {@code Desktop.open} call's own responsibility.
	 */
	void open(File file) throws IOException;
}
