package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Shared read/write access to the system clipboard for {@link ClipboardListSource} (input) and
 * {@code CommandCopyResult} (output) - factored out because both need the exact same retry behavior,
 * not because either side is complex on its own.
 *
 * <p><b>Retries on {@link IllegalStateException}</b> ("cannot open system clipboard"): confirmed
 * empirically while testing this feature - on Windows, the clipboard can be transiently locked by
 * another process (a clipboard manager, another application's own copy/paste) for a short time, and
 * {@code java.awt.datatransfer.Clipboard} surfaces that as this exception rather than waiting for the
 * lock to clear. A handful of quick retries makes both {@code <@clipboard>} and {@code COPY RESULT}
 * robust against that, instead of failing on what is normally a sub-100ms transient condition.
 */
public final class ClipboardAccess {

	private static final int MAX_ATTEMPTS = 5;
	private static final long RETRY_DELAY_MS = 50;

	private ClipboardAccess() {
	}

	/** @return the clipboard's text content, or {@code null} if it holds no text (e.g. a copied file/image) */
	public static String readText() throws BroadSQLException {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
				return (String) clipboard.getData(DataFlavor.stringFlavor);
			} catch (HeadlessException he) {
				throw headlessError();
			} catch (UnsupportedFlavorException ufe) {
				return null;
			} catch (IOException e) {
				throw new BroadSQLException("Could not read the system clipboard: " + e.getLocalizedMessage());
			} catch (IllegalStateException ise) {
				if (attempt == MAX_ATTEMPTS) {
					throw new BroadSQLException("Could not read the system clipboard - it appears to be locked by another "
							+ "application (a clipboard manager, another program's own copy/paste): " + ise.getLocalizedMessage());
				}
				sleep();
			}
		}
		throw new IllegalStateException("unreachable");
	}

	public static void writeText(String text) throws BroadSQLException {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
				clipboard.setContents(new StringSelection(text), null);
				return;
			} catch (HeadlessException he) {
				throw headlessError();
			} catch (IllegalStateException ise) {
				if (attempt == MAX_ATTEMPTS) {
					throw new BroadSQLException("Could not write to the system clipboard - it appears to be locked by another "
							+ "application (a clipboard manager, another program's own copy/paste): " + ise.getLocalizedMessage());
				}
				sleep();
			}
		}
	}

	private static BroadSQLException headlessError() {
		return new BroadSQLException("The system clipboard is not available: no graphical environment access to it in this session.");
	}

	private static void sleep() {
		try {
			Thread.sleep(RETRY_DELAY_MS);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
	}
}
