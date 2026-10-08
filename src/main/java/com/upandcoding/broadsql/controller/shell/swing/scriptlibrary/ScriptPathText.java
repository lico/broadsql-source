package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.util.function.Function;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.reader.InputOffer;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptResolver;

/**
 * The texts the BroadSQL Editor derives from a Script's place in the Scripts Library: what Copy Path copies and
 * what Send to CLI places on the command prompt. The path is always the Script's <em>current</em> library path
 * (the caller passes the tab's or tree item's path after any rename or move), and the command is the canonical
 * one from {@link ScriptResolver#libraryInvocation}, never a second syntax of the Editor's own.
 *
 * <p>No Swing and no clipboard here, so every rule is testable headlessly; the frame does the clipboard write and
 * shows the message.
 */
final class ScriptPathText {

	private ScriptPathText() {
	}

	/** What Copy Path copies. */
	enum Kind {
		/** {@code reports/QR13.sql}: the path relative to the Scripts Library, as BroadSQL names Scripts everywhere. */
		LIBRARY_PATH("Copy Library Path"),
		/** The absolute filesystem path, for use outside BroadSQL. */
		FULL_PATH("Copy Full Path"),
		/** {@code @reports/QR13.sql;}: the command that runs the Script at the prompt. */
		CLI_COMMAND("Copy CLI Command");

		final String label;

		Kind(String label) {
			this.label = label;
		}
	}

	/**
	 * The text Copy Path puts on the clipboard for {@code libraryPath}.
	 *
	 * @param fullPath the item's absolute path (only read for {@link Kind#FULL_PATH})
	 * @throws BroadSQLException for {@link Kind#CLI_COMMAND} when the path cannot be written as a command argument
	 */
	static String text(Kind kind, String libraryPath, java.nio.file.Path fullPath) throws BroadSQLException {
		return switch (kind) {
			case LIBRARY_PATH -> libraryPath;
			case FULL_PATH -> fullPath.toString();
			case CLI_COMMAND -> ScriptResolver.libraryInvocation(libraryPath);
		};
	}

	/** The outcome of Send to CLI: the command, whether it is now on the prompt, and the status message to show. */
	record SendResult(String command, boolean placed, String message) {
	}

	/**
	 * Sends the command running {@code libraryPath} to the command prompt through {@code prompt} (the console's
	 * {@code offerCommandInput}), which places it on the input line without submitting it. {@code prompt} is
	 * {@code null} when the Editor has no console to talk to.
	 *
	 * @param unsavedChanges whether the Script's tab has unsaved changes: the command runs the saved file, so the
	 *                       message says so
	 */
	static SendResult sendToCli(String libraryPath, boolean unsavedChanges, Function<String, InputOffer> prompt) {
		String command;
		try {
			command = ScriptResolver.libraryInvocation(libraryPath);
		} catch (BroadSQLException e) {
			return new SendResult(null, false, "Not sent: " + e.getLocalizedMessage() + ".");
		}
		InputOffer offer = prompt == null ? InputOffer.UNSUPPORTED : prompt.apply(command);
		String unsavedNote = unsavedChanges ? " It runs the saved file: save first to include your unsaved changes." : "";
		return switch (offer) {
			case PLACED -> new SendResult(command, true, "Sent '" + command + "' to the BroadSQL prompt; press Enter there to run it." + unsavedNote);
			case UNSUPPORTED -> new SendResult(command, false,
					"Not sent: the basic console (activatejline=OFF) cannot receive a command. Use Copy CLI Command and paste it instead.");
			case NOT_AT_PROMPT -> new SendResult(command, false, "Not sent: BroadSQL is not waiting at its prompt (a command is running or it is asking a question).");
			case STATEMENT_PENDING -> new SendResult(command, false,
					"Not sent: the prompt holds an unfinished statement; end it with ; or press Esc there first.");
			case INPUT_NOT_EMPTY -> new SendResult(command, false, "Not sent: the prompt already holds typed text, which is never replaced; clear it first.");
		};
	}
}
