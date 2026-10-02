package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.lang.reflect.InvocationTargetException;

import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryLauncher;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptRunContext;

/**
 * The production {@link ScriptLibraryLauncher}: marshals onto the Swing event dispatch thread (command
 * execution runs on its own dedicated worker thread - see {@code CommandInterpreter.executeCommand} -
 * never the EDT itself) and delegates to {@link ScriptLibraryWorkspaceHolder}'s single per-process
 * frame. Kept separate from the editor commands ({@code EDIT}/{@code LIB EDIT}) so a
 * test can substitute a fake {@link ScriptLibraryLauncher} instead - see that interface's own javadoc.
 */
public final class ScriptLibraryWorkspaceLauncher implements ScriptLibraryLauncher {

	private static final Logger log = LoggerFactory.getLogger(ScriptLibraryWorkspaceLauncher.class);

	private final ConsoleSettings consoleSettings;

	public ScriptLibraryWorkspaceLauncher(ConsoleSettings consoleSettings) {
		this.consoleSettings = consoleSettings;
	}

	@Override
	public void openWorkspace(ScriptRunContext context) {
		runOnEdt(() -> {
			ScriptLibraryFrame frame = ScriptLibraryWorkspaceHolder.frame(consoleSettings);
			frame.setExecutionContext(context);
			frame.openWorkspaceWithNewScript(); // EDIT / LIB EDIT without a name: never an empty editor
		});
	}

	@Override
	public void openAsset(String relativePath, ScriptRunContext context) {
		runOnEdt(() -> {
			ScriptLibraryFrame frame = ScriptLibraryWorkspaceHolder.frame(consoleSettings);
			frame.setExecutionContext(context);
			frame.openOrFocusAsset(relativePath);
		});
	}

	@Override
	public void offerCreate(String requestedName, ScriptRunContext context) {
		runOnEdt(() -> {
			ScriptLibraryFrame frame = ScriptLibraryWorkspaceHolder.frame(consoleSettings);
			frame.setExecutionContext(context);
			frame.offerCreate(requestedName);
		});
	}

	private static void runOnEdt(Runnable action) {
		if (SwingUtilities.isEventDispatchThread()) {
			action.run();
			return;
		}
		try {
			SwingUtilities.invokeAndWait(action);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (InvocationTargetException e) {
			log.error("BroadSQL Editor workspace action failed: {}", e.getCause() != null ? e.getCause().getLocalizedMessage() : e.getLocalizedMessage());
		}
	}
}
