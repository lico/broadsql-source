package com.upandcoding.broadsql.controller.shell.reader;

/**
 * The line-reading strategy behind {@link com.upandcoding.broadsql.controller.shell.output.ShellConsole}
 * - SPRINT XT02A (URL-Native API Execution), section 12.2's target architecture:
 * <pre>
 * ShellConsole -&gt; ConsoleLineReader -&gt; BasicLineReader | JLineReader -&gt; CompletionService -&gt; ApiCatalogService
 * </pre>
 * Exactly two implementations: {@link BasicLineReader} (this project's original, unchanged
 * {@code java.io.Console}-based behavior - always available, always correct, used whenever
 * {@code activatejline=OFF} or JLine failed to initialize) and {@code JLineConsoleLineReader}
 * (history/completion, used only when {@code activatejline=ON} and initialization succeeded).
 * <b>Command execution semantics must be identical regardless of which one is active</b> - this
 * interface deliberately exposes nothing beyond "read a line with this prompt", so no command can
 * ever branch on which reader produced its input.
 */
public interface ConsoleLineReader {

	/**
	 * @param prompt the prompt to display, or {@code ""} to read without one (matches
	 *               {@link com.upandcoding.broadsql.controller.shell.output.ShellConsole}'s existing
	 *               {@code displayPrompt} distinction)
	 * @return the line read, or {@code null} at end of input (matches {@code java.io.Console#readLine()}'s own contract)
	 */
	String readLine(String prompt);

	/**
	 * Reads the next line at BroadSQL's command prompt: {@code CommandInterpreter}'s input loop, the only place
	 * that reads commands. Identical to {@link #readLine} except that, while it waits, the reader knows it is
	 * waiting for a command, which is the only time {@link #offerInput} places anything.
	 *
	 * @param statementPending whether earlier lines of a statement not yet ended with {@code ;} are waiting
	 */
	default String readCommandLine(String prompt, boolean statementPending) {
		return readLine(prompt);
	}

	/**
	 * Places {@code text} on the input line of a command prompt that is waiting in {@link #readCommandLine},
	 * without submitting it: the user reviews it and presses Enter. Called from another thread (the BroadSQL
	 * Editor's Send to CLI). Refused, with the reason, when the console is not waiting for a command, when a
	 * statement is pending, or when the input line already holds text: typed input is never replaced.
	 */
	default InputOffer offerInput(String text) {
		return InputOffer.UNSUPPORTED;
	}

	/**
	 * Reads a password: never echoed, and never recorded in this reader's history (in-memory or
	 * persistent) - SPRINT XT02B, section 1.1. Returns {@code char[]}, not {@code String}, matching
	 * {@code java.io.Console#readPassword}'s own convention (every caller already converts to
	 * {@code String} exactly once, at the {@code ShellConsole} boundary, and discards the array).
	 *
	 * @param prompt the prompt to display
	 * @return the password read, or {@code null} at end of input
	 */
	char[] readPassword(String prompt);

	/**
	 * SPRINT 2209C (GitHub #150): whether the user pressed Esc (cancel the whole input) during the most
	 * recent {@link #readLine}, clearing that indication. Only {@code CommandInterpreter}'s input loop asks,
	 * so it can also drop the lines it has been accumulating for an unterminated statement; the line
	 * returned already excludes the cancelled text, so no command ever needs this.
	 * {@link BasicLineReader} has no such key handling: {@code false}.
	 */
	default boolean consumeInputCancellation() {
		return false;
	}

	/** Releases any resources (terminal, history file handle) - a no-op for {@link BasicLineReader}. */
	default void close() {
	}
}
