package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.lang.reflect.InvocationTargetException;

import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryService;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibrarySession;

/**
 * Lazily creates, and thereafter always reuses, the single Script Library workspace for this BroadSQL
 * process (binding decision - "one persistent Script Library workspace per BroadSQL process"). Shared
 * by every editor command ({@code EDIT}, {@code LIB EDIT}) via
 * {@code ScriptLibraryWorkspaceLauncher} - deliberately a dedicated holder class, not a bare
 * {@code static} field duplicated independently on either command.
 *
 * <p>Must only ever be touched on the Swing event dispatch thread - see
 * {@link ScriptLibraryWorkspaceLauncher}, which marshals every call onto it before reaching this class.
 */
public final class ScriptLibraryWorkspaceHolder {

	private static final Logger log = LoggerFactory.getLogger(ScriptLibraryWorkspaceHolder.class);

	private static ScriptLibraryFrame frame;

	private ScriptLibraryWorkspaceHolder() {
	}

	public static synchronized ScriptLibraryFrame frame(ConsoleSettings consoleSettings) {
		if (frame == null) {
			ScriptLibraryService service = new ScriptLibraryService(consoleSettings);
			ScriptLibrarySession session = new ScriptLibrarySession();
			frame = new ScriptLibraryFrame(service, session);
			frame.initApp();
		}
		return frame;
	}

	/** {@code null} until the workspace has actually been opened once in this process - never eagerly creates it. */
	public static synchronized ScriptLibraryFrame frameIfCreated() {
		return frame;
	}

	/**
	 * Called once by {@code BroadSQL.main} as part of normal application shutdown (after the command
	 * interpreter's read loop returns, before the session/console themselves close) - a no-op if the
	 * Script Library was never opened this process. See
	 * {@link ScriptLibraryFrame#shutdownProtectDirtyBuffersThenDispose()} for exactly why this call is
	 * required, not optional, once the workspace has been created: without it, a hidden-but-never-
	 * disposed frame would keep the whole JVM alive indefinitely after {@code EXIT}.
	 */
	public static void shutdown() {
		ScriptLibraryFrame existing;
		synchronized (ScriptLibraryWorkspaceHolder.class) {
			existing = frame;
		}
		if (existing == null) {
			return;
		}
		if (SwingUtilities.isEventDispatchThread()) {
			existing.shutdownProtectDirtyBuffersThenDispose();
			return;
		}
		try {
			SwingUtilities.invokeAndWait(existing::shutdownProtectDirtyBuffersThenDispose);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (InvocationTargetException e) {
			log.error("BroadSQL Editor shutdown cleanup failed: {}", e.getCause() != null ? e.getCause().getLocalizedMessage() : e.getLocalizedMessage());
		}
	}
}
