package com.upandcoding.broadsql.controller.shell.commands.core.export;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;

/**
 * Real {@link FileOpener}: {@code java.awt.Desktop}, the standard Java abstraction over the OS's
 * registered/default application for a file - not Excel specifically, and not a Windows-only
 * {@code cmd /c start} shell-out (see docs/PULL_TO_SPREADSHEET.md, "OPEN"). Degrades to a clear
 * {@link IOException} rather than throwing an unchecked error when desktop integration is unavailable
 * (a headless environment, some Linux/server sessions, or a desktop environment without a registered
 * handler for the file's extension).
 */
public final class DesktopFileOpener implements FileOpener {

	public static final DesktopFileOpener INSTANCE = new DesktopFileOpener();

	private DesktopFileOpener() {
	}

	@Override
	public void open(File file) throws IOException {
		if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
			throw new IOException("no desktop integration available in this environment");
		}
		Desktop.getDesktop().open(file);
	}
}
