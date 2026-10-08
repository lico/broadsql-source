package com.upandcoding.broadsql.controller.shell.reader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Buffer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.completion.BroadSqlCommandCompletionProvider;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEngine;
import com.upandcoding.broadsql.controller.shell.completion.EntityCompletionService;
import com.upandcoding.broadsql.controller.shell.completion.JdbcMetadataCompletionCacheHolder;
import com.upandcoding.broadsql.controller.shell.completion.JdbcMetadataCompletionProvider;
import com.upandcoding.broadsql.controller.shell.completion.PendingStatementBufferHolder;
import com.upandcoding.broadsql.controller.shell.completion.SqlKeywordCompletionProvider;
import com.upandcoding.broadsql.controller.shell.style.StyleRole;
import com.upandcoding.broadsql.controller.shell.style.SyntaxHighlighter;
import com.upandcoding.broadsql.controller.shell.style.TerminalStyle;
import com.upandcoding.broadsql.dao.DatabaseConnection;

/**
 * The JLine-backed {@link ConsoleLineReader} - SPRINT XT02A (URL-Native API Execution), section 12/14:
 * tab completion ({@link BroadSqlJLineCompleter}), Up/Down history, and reverse incremental search
 * (JLine's own built-in Ctrl-R binding - nothing bespoke needed here).
 *
 * <p><b>Persistent, per-OS-user history</b> (SPRINT XT02B, section 14): when a non-null history file
 * path is passed to {@link #create}, it is used as JLine's {@link LineReader#HISTORY_FILE} - see
 * {@code ConsoleSettings#resolveJLineHistoryFilePath()} for exactly where that path defaults to and how
 * an override is resolved. <b>The required fallback chain is: history file unavailable/unwritable/
 * unreadable -&gt; emit one concise warning -&gt; JLine stays fully active -&gt; history simply falls
 * back to in-memory {@link DefaultHistory} for that session</b> - this must never fall all the way back
 * to {@link BasicLineReader}; completion, Up/Down, and Ctrl-R all keep working regardless of whether the
 * persistent file could be used. This is handled at every point persistence can fail: a directory-
 * creation failure, a failure while JLine loads an existing but corrupted/unreadable history file, and a
 * failure while saving history during {@link #close}. Regardless of persistence, this class stores
 * exactly what {@link #readLine} was given back verbatim - it never substitutes a session {@code VAR}, a
 * persisted {@code CONFIG API} value, or a resolved {@code ${ENV:...}} reference into what gets recorded
 * (section 14's "must not be rewritten... just because :id resolved to 123" applies here identically to
 * secrets: nothing in this class ever sees a resolved value in the first place, only the literal line
 * the user typed) - and {@link #readPassword} never reaches this history at all, regardless of whether
 * it is in-memory or file-backed.
 */
public final class JLineConsoleLineReader implements ConsoleLineReader {

	private static final Logger log = LoggerFactory.getLogger(JLineConsoleLineReader.class);

	// SPRINT XT02B, section 14: a bounded size, applied whether or not persistence is active.
	private static final int HISTORY_SIZE = 1000;
	private static final int HISTORY_FILE_SIZE = 2000;

	/** SPRINT 2209C: the JLine widget bound to Esc - see {@link #cancelInput()}. */
	static final String CANCEL_INPUT_WIDGET = "broadsql-cancel-input";
	/** The widget {@link #offerInput} runs to place offered text on the input line. */
	static final String OFFER_INPUT_WIDGET = "broadsql-offer-input";
	/** GitHub #207: the widget bound to TAB - see {@link #tabKey()}. */
	static final String TAB_WIDGET = "broadsql-tab";

	/**
	 * SPRINT 2209C: how long JLine waits after an Esc byte for the rest of a longer key sequence before
	 * treating it as the Esc key alone ({@link LineReader#AMBIGUOUS_BINDING}; JLine's default is 1000 ms).
	 * A terminal sends every multi-byte key (arrows, Home, End, Delete, F-keys, Alt+key) as one burst -
	 * JLine's own Windows terminal writes the whole sequence into its input pipe in a single call - so the
	 * next byte is already there when JLine looks and the sequence decodes normally; only a real,
	 * standalone Esc ever waits the full timeout. At 1000 ms, Esc felt late and any key typed within that
	 * second was merged with it into an Alt+key command (Esc then {@code d} deleted a word); 100 ms keeps
	 * Esc immediate while leaving a wide margin over a sequence's own inter-byte delay.
	 */
	static final long ESC_AMBIGUITY_TIMEOUT_MS = 100L;

	private final Terminal terminal;
	private final LineReader lineReader;
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private final AtomicBoolean inputCancelled = new AtomicBoolean(false);
	/** True only while {@link #readCommandLine} waits: {@link #offerInput} never places text into any other read (a password, a confirmation). */
	private volatile boolean awaitingCommand;
	/** Whether the command being read continues a statement not yet ended with {@code ;} (cleared by Esc, which abandons it). */
	private volatile boolean statementPending;
	/** Hand-off between {@link #offerInput} and {@link #placeOfferedInput}: both run on the offering thread, under JLine's own lock. */
	private String offeredInput;
	private InputOffer offerResult;

	private JLineConsoleLineReader(Terminal terminal, LineReader lineReader) {
		this.terminal = terminal;
		this.lineReader = lineReader;
		installKeyboardContract();
	}

	/**
	 * SPRINT 2209C (GitHub #150): BroadSQL's small set of overrides on top of JLine's own active keymap
	 * ({@link LineReader#MAIN}, which JLine links to its Emacs keymap for any non-dumb terminal) - every
	 * other key keeps JLine's standard binding.
	 * <ul>
	 * <li><b>Esc</b> cancels the whole input ({@link #cancelInput()}). Before this, Esc had no binding of
	 * its own in JLine's Emacs keymap - it only exists there as the prefix of Alt+key and cursor-key
	 * sequences - so JLine kept waiting for a following key and merged Esc into it: Esc then Enter
	 * inserted a raw carriage return instead of submitting, Esc then a letter ran an Alt+letter
	 * command, and the typed text stayed. Binding Esc alone makes it an ambiguous prefix, which JLine's
	 * {@code BindingReader} resolves with {@link #ESC_AMBIGUITY_TIMEOUT_MS}: Esc followed by the rest of
	 * a known sequence (e.g. {@code ESC O A}, Up) is that key; Esc followed by nothing is Esc.</li>
	 * <li><b>Esc Esc</b> (JLine: complete-word, which Tab already does) also cancels, so a quick double
	 * press does what a single one does.</li>
	 * <li><b>Esc in the completion menu</b>: JLine's menu keymap is consulted first while a candidate
	 * menu is open and, having only arrow-key sequences under Esc, would otherwise wait for another key.
	 * Binding Esc there makes the menu close at once; JLine then replays Esc against the main keymap,
	 * which cancels the line.</li>
	 * <li><b>Ctrl+U</b> deletes from the cursor back to the beginning of the line (JLine's standard
	 * {@code backward-kill-line} widget), the #150 contract, instead of JLine's Emacs default of deleting
	 * the whole line.</li>
	 * <li><b>Tab</b> (GitHub #207) runs {@link #tabKey()}: completion only when there is a word to complete.</li>
	 * </ul>
	 * During Ctrl+R history search, JLine ends the search on Esc and replays it, so Esc cancels there too.
	 * While the completion menu is open, JLine's menu keymap still owns Tab and Shift+Tab (cycling).
	 */
	private void installKeyboardContract() {
		lineReader.getWidgets().put(CANCEL_INPUT_WIDGET, this::cancelInput);
		lineReader.getWidgets().put(OFFER_INPUT_WIDGET, this::placeOfferedInput);
		lineReader.getWidgets().put(TAB_WIDGET, this::tabKey);
		Reference cancelInput = new Reference(CANCEL_INPUT_WIDGET);
		KeyMap<Binding> main = lineReader.getKeyMaps().get(LineReader.MAIN);
		main.bind(cancelInput, KeyMap.esc());
		main.bind(cancelInput, KeyMap.esc() + KeyMap.esc());
		main.bind(new Reference(LineReader.BACKWARD_KILL_LINE), KeyMap.ctrl('U'));
		main.bind(new Reference(TAB_WIDGET), KeyMap.ctrl('I'));
		lineReader.getKeyMaps().get(LineReader.MENU).bind(cancelInput, KeyMap.esc());
	}

	/**
	 * GitHub #207: the Tab key. JLine's own binding ({@code expand-or-complete}) completes whatever the cursor is
	 * on, including an empty word, and with {@code MENU_COMPLETE} it then inserts the first candidate: a Tab typed
	 * as indentation, or a Tab inside SQL pasted into a terminal that does not mark pastes (the Windows console
	 * delivers a paste as ordinary key events), turned {@code \tB,} into {@code ALL B,}. Now:
	 * <ul>
	 * <li><b>indentation</b> - only whitespace between the start of the cursor's line and the cursor: a literal Tab
	 * is inserted, as in a text editor;</li>
	 * <li><b>empty word elsewhere</b> (after a space, e.g. {@code DUMP <TAB>}): the candidates are listed and
	 * nothing is inserted, so no candidate is ever chosen for the user;</li>
	 * <li><b>a word is being typed</b> ({@code sel<TAB>}): JLine's standard completion, unchanged.</li>
	 * </ul>
	 * A paste marked by the terminal (bracketed paste, which JLine enables by default) never reaches this widget:
	 * JLine's {@code begin-paste} inserts the whole pasted text literally.
	 */
	private boolean tabKey() {
		Buffer buffer = lineReader.getBuffer();
		String beforeCursor = buffer.substring(0, buffer.cursor());
		String lineBeforeCursor = beforeCursor.substring(beforeCursor.lastIndexOf('\n') + 1);
		if (lineBeforeCursor.isBlank()) {
			buffer.write('\t');
			return true;
		}
		if (wordBeforeCursorIsEmpty(buffer.toString(), buffer.cursor())) {
			return runJLineWidget(LineReader.LIST_CHOICES);
		}
		return runJLineWidget(LineReader.EXPAND_OR_COMPLETE);
	}

	/** Runs one of JLine's built-in widgets and keeps its result, so a Tab with no candidate still beeps as before. */
	private boolean runJLineWidget(String name) {
		return lineReader.getWidgets().get(name).apply();
	}

	/** Whether the word the cursor is in has nothing before the cursor, as JLine's completion parser splits words (quotes included). */
	private boolean wordBeforeCursorIsEmpty(String line, int cursor) {
		try {
			ParsedLine parsed = lineReader.getParser().parse(line, cursor, Parser.ParseContext.COMPLETE);
			return parsed.wordCursor() == 0;
		} catch (RuntimeException e) {
			return false; // let JLine's own completion decide, as before
		}
	}

	/**
	 * The Esc widget: empties JLine's whole buffer (every line of it, wherever the cursor is), so the
	 * prompt is redrawn empty in place - JLine redisplays after every widget - and nothing is submitted
	 * or added to history. It also returns history navigation to the newest entry, as a fresh prompt
	 * would, and forgets the statement {@code CommandInterpreter} has been accumulating over earlier
	 * lines: at once for TAB completion ({@link PendingStatementBufferHolder}), and for the interpreter
	 * itself through {@link #consumeInputCancellation()} when the next line is returned. JLine's stored
	 * history entries are never modified: editing a recalled entry only ever changed the buffer.
	 */
	private boolean cancelInput() {
		lineReader.getBuffer().clear();
		lineReader.getHistory().moveToEnd();
		PendingStatementBufferHolder.clear();
		inputCancelled.set(true);
		statementPending = false;
		return true;
	}

	@Override
	public String readCommandLine(String prompt, boolean statementPending) {
		this.statementPending = statementPending;
		awaitingCommand = true;
		try {
			return readLine(prompt);
		} finally {
			awaitingCommand = false;
		}
	}

	/**
	 * The BroadSQL Editor's Send to CLI: runs {@link #placeOfferedInput} as a JLine widget from the calling thread.
	 * JLine allows that while a line is being read: {@code LineReaderImpl} releases its lock while it blocks on the
	 * next key and {@code callWidget} takes it, so the widget edits the buffer exactly as a key binding would, and
	 * the redisplay it asks for draws the prompt with the text. Nothing is submitted: the line is returned only
	 * when the user presses Enter. When no line is being read at that instant, {@code callWidget} throws
	 * {@link IllegalStateException}, reported as {@link InputOffer#NOT_AT_PROMPT}.
	 */
	@Override
	public synchronized InputOffer offerInput(String text) {
		if (!awaitingCommand) {
			return InputOffer.NOT_AT_PROMPT;
		}
		if (statementPending) {
			return InputOffer.STATEMENT_PENDING;
		}
		offeredInput = text;
		offerResult = InputOffer.NOT_AT_PROMPT;
		try {
			lineReader.callWidget(OFFER_INPUT_WIDGET);
			return offerResult;
		} catch (IllegalStateException notReading) {
			return InputOffer.NOT_AT_PROMPT;
		} finally {
			offeredInput = null;
		}
	}

	/** The offer widget: writes the offered text into an empty buffer, never over typed text, then redraws the prompt. */
	private boolean placeOfferedInput() {
		if (offeredInput == null) {
			return true;
		}
		if (lineReader.getBuffer().length() > 0) {
			offerResult = InputOffer.INPUT_NOT_EMPTY;
			return true;
		}
		lineReader.getBuffer().write(offeredInput);
		lineReader.callWidget(LineReader.REDISPLAY);
		offerResult = InputOffer.PLACED;
		return true;
	}

	@Override
	public boolean consumeInputCancellation() {
		return inputCancelled.getAndSet(false);
	}

	/**
	 * Builds a real, system-attached JLine reader for interactive production use.
	 *
	 * @param historyFilePath where to persist history across sessions (see
	 *                        {@code ConsoleSettings#resolveJLineHistoryFilePath()}), or {@code null} for
	 *                        in-memory-only history (also the automatic fallback if this path turns out
	 *                        to be unusable - see this class's own javadoc)
	 * @param commandList     SPRINT 0917-02 - the live command registry, for BroadSQL command/subcommand
	 *                        completion; {@code null} disables that provider only (API completion above
	 *                        is unaffected)
	 * @param sqlDatabase     SPRINT 0917-02 - the live connection, for SQL-keyword and JDBC-metadata
	 *                        completion; {@code null} disables metadata completion only
	 * @throws IOException if the terminal cannot be created - the caller (BroadSQL.main) is
	 *                      responsible for the single warning + fallback to {@link BasicLineReader}
	 *                      (section 12.1: "If JLine initialization fails despite ON, emit one clear
	 *                      warning and fall back to the basic reader"). A history-specific failure never
	 *                      throws from here - see {@link #buildLineReader}.
	 */
	public static JLineConsoleLineReader create(ApiCatalogService catalog, Supplier<List<String>> commandKeywordsSupplier, Path historyFilePath,
			CommandList commandList, DatabaseConnection sqlDatabase) throws IOException {
		Terminal terminal = TerminalBuilder.builder().system(true).build();
		return new JLineConsoleLineReader(terminal, buildLineReader(terminal, catalog, commandKeywordsSupplier, historyFilePath, commandList, sqlDatabase));
	}

	/**
	 * Test seam - an explicit, non-system terminal (a dumb terminal over piped streams) so this class
	 * is exercisable without a real interactive TTY.
	 *
	 * <p><b>Deliberately constructs {@link org.jline.terminal.impl.DumbTerminal} directly, bypassing
	 * {@code TerminalBuilder} entirely</b> (SPRINT XT02A corrective pass - a real, diagnosed problem,
	 * not a style preference): {@code TerminalBuilder.builder().streams(in, out).system(false).dumb(true).build()}
	 * looks like it should build a dumb terminal immediately, but {@code dumb(true)} only means "allow
	 * dumb as a fallback" - the builder still runs its normal detection chain first (including an
	 * exec-based probe, e.g. an {@code stty}-equivalent, for terminal dimensions), and only falls back
	 * to dumb once that finishes. Standalone, that probe resolves in well under a second; specifically
	 * under this project's Maven Surefire configuration - never reproduced outside it, including a
	 * standalone {@code java} process running the identical code - that same probe was measured taking
	 * ~20-30 seconds per test (reproducibly, across every test in the class, confirmed by isolating
	 * flag-by-flag: {@code jna(false)}, {@code jansi(false)}, {@code nativeSignals(false)}, each alone,
	 * changed nothing; only skipping detection entirely fixes it). Root cause not fully isolated beyond
	 * that (most likely an external helper process inheriting a stdin pipe from Surefire's forked
	 * process that never closes, so the probe waits out an internal timeout rather than hanging
	 * forever) - documented as a known, worked-around environment quirk rather than left as an
	 * unexplained "just because" workaround. Constructing {@link org.jline.terminal.impl.DumbTerminal}
	 * directly skips the whole detection chain, taking under 200ms for this class's entire test suite.
	 */
	public static JLineConsoleLineReader createForTesting(InputStream in, OutputStream out, ApiCatalogService catalog, Supplier<List<String>> commandKeywordsSupplier)
			throws IOException {
		return createForTesting(in, out, catalog, commandKeywordsSupplier, null, null, null);
	}

	/** Same test seam as the no-history-path overload, but exercising the persistent-history path too. */
	public static JLineConsoleLineReader createForTesting(InputStream in, OutputStream out, ApiCatalogService catalog, Supplier<List<String>> commandKeywordsSupplier,
			Path historyFilePath) throws IOException {
		return createForTesting(in, out, catalog, commandKeywordsSupplier, historyFilePath, null, null);
	}

	/** Same test seam, additionally exercising SPRINT 0917-02's BroadSQL-command/SQL-keyword/JDBC-metadata completion. */
	public static JLineConsoleLineReader createForTesting(InputStream in, OutputStream out, ApiCatalogService catalog, Supplier<List<String>> commandKeywordsSupplier,
			Path historyFilePath, CommandList commandList, DatabaseConnection sqlDatabase) throws IOException {
		Terminal terminal = new org.jline.terminal.impl.DumbTerminal("test", Terminal.TYPE_DUMB, in, out, java.nio.charset.StandardCharsets.UTF_8);
		return new JLineConsoleLineReader(terminal, buildLineReader(terminal, catalog, commandKeywordsSupplier, historyFilePath, commandList, sqlDatabase));
	}

	/**
	 * SPRINT 0917-02 runtime defect fix - a test seam distinct from {@link #createForTesting}: that one
	 * uses a {@link org.jline.terminal.impl.DumbTerminal}, which (confirmed empirically while diagnosing
	 * the "TAB completion does not work in the real console" defect) never processes key bindings at
	 * all - a Tab byte is inserted into the line as a literal character, so a {@code DumbTerminal}-backed
	 * {@link LineReader#readLine} can never exercise real TAB-triggers-completion behavior, only a direct
	 * {@code Completer#complete} call can (which every completion test before this fix already did,
	 * which is exactly why none of them caught this class of bug).
	 *
	 * <p>Builds a real, ANSI/key-binding-capable {@link Terminal} over the given streams instead
	 * ({@code TerminalBuilder...streams(in, out).system(false)}, deliberately without {@code .dumb(true)}
	 * - see {@link #createForTesting(InputStream, OutputStream, ApiCatalogService, Supplier)}'s own
	 * javadoc for why a {@code dumb(true)} fallback specifically is what triggers the slow exec-based
	 * detection probe under Surefire; requesting explicit streams with no dumb fallback skips that
	 * detection chain entirely and returns a usable {@link org.jline.terminal.impl.ExternalTerminal}
	 * immediately), so a test using this seam can actually drive a real keystroke sequence (e.g.
	 * {@code "sel\t"}) through JLine's own {@link LineReader#readLine} and observe the real result -
	 * exactly what a physical terminal session does, and what no other test in this codebase does.
	 */
	public static JLineConsoleLineReader createForTestingWithRealKeyBindings(InputStream in, OutputStream out, ApiCatalogService catalog,
			Supplier<List<String>> commandKeywordsSupplier, CommandList commandList, DatabaseConnection sqlDatabase) throws IOException {
		return createForTestingWithRealKeyBindings(in, out, catalog, commandKeywordsSupplier, commandList, sqlDatabase, null);
	}

	/**
	 * SPRINT 2209C - same real key-binding seam, with an explicit terminal type. JLine binds arrow/Home/
	 * End/Delete keys from the terminal type's own key capabilities, and on Windows its JNI terminal
	 * ({@code AbstractWindowsTerminal}) translates each physical key into exactly those capability
	 * strings; passing {@code "windows-vtp"} here therefore makes a test send the same byte sequences a
	 * real Windows console session produces. {@code null} keeps JLine's own default type.
	 *
	 * <p>With an explicit type, the {@link org.jline.terminal.impl.ExternalTerminal} that
	 * {@code TerminalBuilder} would build over these streams is constructed directly: every
	 * {@code TerminalBuilder.build()} first probes each terminal provider, and the exec provider's probe
	 * spawns a process that was observed blocking for minutes under Surefire (same quirk as
	 * {@link #createForTesting(InputStream, OutputStream, ApiCatalogService, Supplier)} describes).
	 */
	public static JLineConsoleLineReader createForTestingWithRealKeyBindings(InputStream in, OutputStream out, ApiCatalogService catalog,
			Supplier<List<String>> commandKeywordsSupplier, CommandList commandList, DatabaseConnection sqlDatabase, String terminalType) throws IOException {
		Terminal terminal = terminalType == null
				? TerminalBuilder.builder().streams(in, out).system(false).build()
				: new org.jline.terminal.impl.ExternalTerminal("test", terminalType, in, out, java.nio.charset.StandardCharsets.UTF_8);
		return new JLineConsoleLineReader(terminal, buildLineReader(terminal, catalog, commandKeywordsSupplier, null, commandList, sqlDatabase));
	}

	/**
	 * SPRINT XT02B, section 14: {@code historyFilePath} is attempted best-effort - any failure (creating
	 * its parent directory, or JLine failing to load an existing-but-corrupted file once attached) is
	 * caught here and reduced to plain in-memory history, with one concise warning logged; this method
	 * itself never throws for a history-related reason, and JLine is always returned fully functional.
	 *
	 * <p>SPRINT 0917-02 runtime defect fix: both {@link LineReaderBuilder#build()} calls below set
	 * {@code LineReader.Option.CASE_INSENSITIVE}. Without it, JLine's own internal completion re-match
	 * (independent of, and downstream from, {@link BroadSqlJLineCompleter}/{@code CompletionEngine},
	 * which already match case-insensitively and return correct candidates) silently discards any
	 * candidate that is not an exact-case prefix match of the typed text - so {@code sel<TAB>} produced
	 * the right {@code SELECT} candidate internally but JLine never inserted it, because {@code "SELECT"}
	 * does not literally start with {@code "sel"}. Confirmed empirically: identical candidates/typing
	 * completed correctly only when the typed prefix already matched the candidate's case (e.g.
	 * {@code SEL<TAB>}), and completed correctly for every case once this option was set. This also
	 * fixes the same latent gap in the pre-existing SPRINT XT02A API completion (e.g. an endpoint alias
	 * typed in lowercase), which shares this same {@link LineReaderBuilder} and was never covered by a
	 * test exercising JLine's actual key-binding dispatch (every existing completion test calls
	 * {@code Completer#complete} directly, bypassing JLine's own re-match step entirely).
	 *
	 * <p>UX follow-up (SPRINT 0917-02) - both builders also set {@code LineReader.Option.MENU_COMPLETE}:
	 * without it, an ambiguous TAB left the user with a static candidate list they had to keep typing
	 * against to disambiguate. Confirmed empirically (against the real key-binding path, not by reading
	 * JLine's source) that this option makes the first TAB on an ambiguous prefix enter JLine's own
	 * menu-selection mode directly - inserting the first candidate, highlighted - with every further TAB
	 * cycling forward and Shift-TAB cycling backward (JLine's own default keymap binding, nothing added
	 * here), each replacing exactly the ambiguous token. A unique candidate is unaffected - this option
	 * only changes what happens once there is more than one match. See
	 * {@code TestJLineConsoleLineReaderMenuCompletion} for the full investigation and regression coverage.
	 *
	 * <p>GitHub #156 fix: both builders also set {@code LineReader.Option.DISABLE_EVENT_EXPANSION}. JLine
	 * enables bash-style history event expansion ({@code !}/{@code ^}) by default; as part of that, its
	 * {@code LineReaderImpl.expandEvents} treats a bare backslash as a generic escape character and
	 * strips it from the accepted line regardless of the next character, confirmed empirically with a
	 * throwaway {@code JLineConsoleLineReader.createForTestingWithRealKeyBindings} probe: typing
	 * {@code <@c:\temp\toto.sql>} through the real key-binding path returned {@code <@c:temptoto.sql>}
	 * (every backslash silently removed, corrupting the Windows path) before this option was set, and the
	 * exact literal input after. This is the root cause of GitHub #156, not anything in
	 * {@code CommandUtils#substituteMacros}, {@code ListSourceResolver}, or {@code FileListSource}, none
	 * of which do any escape processing of their own - the corruption already happened one layer up, in
	 * JLine itself, before {@code readLine} ever returns. BroadSQL has no bash-style history-reference
	 * syntax, so disabling this JLine feature has no product-visible effect beyond stopping it from
	 * mangling literal backslashes. {@code BasicLineReader} (the {@code activatejline=OFF} path) was
	 * never affected: it calls {@link java.io.Console#readLine()} directly, which does no such expansion.
	 */
	private static LineReader buildLineReader(Terminal terminal, ApiCatalogService catalog, Supplier<List<String>> commandKeywordsSupplier, Path historyFilePath,
			CommandList commandList, DatabaseConnection sqlDatabase) {
		Path usableHistoryFilePath = null;
		if (historyFilePath != null) {
			try {
				Path parent = historyFilePath.toAbsolutePath().getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				usableHistoryFilePath = historyFilePath;
			} catch (IOException | RuntimeException e) {
				log.warn("JLine persistent history unavailable ({}); continuing with in-memory history only.", e.getLocalizedMessage());
			}
		}

		BroadSqlJLineCompleter completer = buildCompleter(catalog, commandKeywordsSupplier, commandList, sqlDatabase);
		// SPRINT 2409K: theme-driven input highlighting; plain JLine highlighting whenever styling is off
		SyntaxHighlighter highlighter = new SyntaxHighlighter(SyntaxHighlighter.keywordsOf(commandList, ConsoleSettings.defaultCommandName));

		if (usableHistoryFilePath != null) {
			try {
				return LineReaderBuilder.builder()
						.terminal(terminal)
						.completer(completer)
						.parser(newParser())
						.highlighter(highlighter)
						.option(LineReader.Option.CASE_INSENSITIVE, true)
						.option(LineReader.Option.MENU_COMPLETE, true)
						.option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
						.variable(LineReader.AMBIGUOUS_BINDING, ESC_AMBIGUITY_TIMEOUT_MS)
						.variable(LineReader.HISTORY_FILE, usableHistoryFilePath)
						.variable(LineReader.HISTORY_SIZE, HISTORY_SIZE)
						.variable(LineReader.HISTORY_FILE_SIZE, HISTORY_FILE_SIZE)
						.history(new DefaultHistory())
						.build();
			} catch (RuntimeException e) {
				// e.g. the file exists but JLine could not read/parse it - fall through to the plain,
				// in-memory build below rather than letting the whole reader fail to construct.
				log.warn("JLine persistent history file could not be loaded ({}); continuing with in-memory history only.", e.getLocalizedMessage());
			}
		}

		return LineReaderBuilder.builder()
				.terminal(terminal)
				.completer(completer)
				.parser(newParser())
				.highlighter(highlighter)
				.option(LineReader.Option.CASE_INSENSITIVE, true)
				.option(LineReader.Option.MENU_COMPLETE, true)
				.option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
				.variable(LineReader.AMBIGUOUS_BINDING, ESC_AMBIGUITY_TIMEOUT_MS)
				.variable(LineReader.HISTORY_SIZE, HISTORY_SIZE)
				.history(new DefaultHistory())
				.build();
	}

	/**
	 * SPRINT 2409K: JLine's {@link DefaultParser} without escape characters. By default it treats a backslash
	 * as an escape while splitting the line into words, so a typed Windows path {@code C:\temp\a} reached every
	 * completer as {@code C:tempa}, and it escaped every inserted candidate, so a completed path came out with
	 * doubled backslashes ({@code C:\\temp\\}) - both verified with a DefaultParser probe (JLine 3.26.3).
	 * BroadSQL's own grammar has no backslash escapes ({@code CommandUtils.getArgumentsFromQuery} only knows
	 * {@code "} quoting), so the parser now matches it. Quotes and whitespace word splitting are unchanged.
	 * The accepted line itself was already returned verbatim (see {@code DISABLE_EVENT_EXPANSION} above).
	 */
	static DefaultParser newParser() {
		DefaultParser parser = new DefaultParser();
		parser.setEscapeChars(new char[0]);
		return parser;
	}

	/** SPRINT 2409K: the terminal this reader runs on, whose reported capabilities decide {@code color=AUTO}. */
	public Terminal getTerminal() {
		return terminal;
	}

	/** SPRINT 2409K: the terminal's current width in columns (live: a resize is seen at the next call), 0 when unknown. */
	public int getTerminalWidth() {
		try {
			return Math.max(0, terminal.getWidth());
		} catch (RuntimeException e) {
			return 0;
		}
	}

	/**
	 * SPRINT 2409K: applies the theme's {@link StyleRole#COMPLETION} style to JLine's completion menu. Does
	 * nothing when the style does not highlight input (theme {@code none}, styling off): JLine's own menu
	 * colors then stay exactly as before theming existed.
	 */
	public void applyStyle(TerminalStyle style) {
		if (style == null || !style.highlightsInput()) {
			return;
		}
		String completion = style.getTheme().spec(StyleRole.COMPLETION);
		if (completion != null) {
			lineReader.setVariable(LineReader.COMPLETION_STYLE_STARTING, completion);
		}
	}

	/** SPRINT 0917-02: {@code generalEngine} is only built when at least one of {@code commandList}/{@code sqlDatabase} is available - a bare {@link BroadSqlJLineCompleter} (API completion only) otherwise, exactly as before this sprint. */
	private static BroadSqlJLineCompleter buildCompleter(ApiCatalogService catalog, Supplier<List<String>> commandKeywordsSupplier, CommandList commandList,
			DatabaseConnection sqlDatabase) {
		if (commandList == null && sqlDatabase == null) {
			return new BroadSqlJLineCompleter(new CompletionService(), catalog, commandKeywordsSupplier);
		}
		CompletionEngine engine = new CompletionEngine(List.of(
				new BroadSqlCommandCompletionProvider(commandList),
				new SqlKeywordCompletionProvider(),
				new JdbcMetadataCompletionProvider(sqlDatabase, JdbcMetadataCompletionCacheHolder.get())));
		EntityCompletionService entityCompletion = commandList == null ? null : EntityCompletionService.standard(commandList, sqlDatabase);
		return new BroadSqlJLineCompleter(new CompletionService(), catalog, commandKeywordsSupplier, engine, sqlDatabase, entityCompletion);
	}

	@Override
	public String readLine(String prompt) {
		inputCancelled.set(false);
		try {
			return lineReader.readLine(prompt == null ? "" : prompt);
		} catch (EndOfFileException e) {
			return null; // matches java.io.Console#readLine()'s own end-of-input contract
		} catch (UserInterruptException e) {
			return ""; // Ctrl-C on an empty/in-progress line - let the caller's normal empty-line handling take over
		}
	}

	/**
	 * SPRINT XT02B, section 1.1: never echoed (a fully-hidden mask, {@code (char) 0}, matching
	 * {@code Console.readPassword}'s total hiding, not {@code *} echo), and never recorded in history -
	 * <b>disabled before reading</b>, via JLine's own {@link LineReader#DISABLE_HISTORY} variable, not
	 * removed after the fact (masking a read only affects on-screen echo, not whether JLine still
	 * records it, so relying on "read, then delete the entry" would leave a real, if narrow, window
	 * where the password briefly exists in the in-memory - or, once persistent history is active,
	 * on-disk - history before being removed). The variable's prior value is restored in a
	 * {@code finally}, so a nested/re-entrant call can never leave history permanently disabled.
	 */
	@Override
	public char[] readPassword(String prompt) {
		Object previousDisableHistory = lineReader.getVariable(LineReader.DISABLE_HISTORY);
		lineReader.setVariable(LineReader.DISABLE_HISTORY, Boolean.TRUE);
		try {
			String line = lineReader.readLine(prompt == null ? "" : prompt, (char) 0);
			return line == null ? null : line.toCharArray();
		} catch (EndOfFileException e) {
			return null;
		} catch (UserInterruptException e) {
			return new char[0];
		} finally {
			lineReader.setVariable(LineReader.DISABLE_HISTORY, previousDisableHistory);
		}
	}

	/**
	 * GitHub #196 (found through REPEAT, but true of every running command): the terminal is built with JLine's
	 * default {@code SIG_DFL} INT handler. Each {@link LineReader#readLine} installs its own INT handler and, when it
	 * returns, restores the previous one; restoring {@code SIG_DFL} on a system terminal
	 * ({@code AbstractWindowsTerminal.handle}, {@code PosixSysTerminal.handle}) calls
	 * {@code Signals.registerDefault("INT")}, which puts the JVM's default SIGINT action back. From the first line
	 * read on, CTRL+C during a command therefore ended the JVM, whatever handler {@code CommandInterpreter} had
	 * registered with {@code sun.misc.Signal}. Installing BroadSQL's handler on the terminal itself makes it the
	 * handler {@code readLine} restores: JLine registers its own native SIGINT hook ({@code raise(INT)}), and a
	 * CTRL+C outside {@code readLine} (a console signal, or the CTRL+C key the Windows pump reads) reaches
	 * {@code handler}. During {@code readLine}, JLine's own handler still applies (the line is abandoned).
	 */
	@Override
	public void setInterruptHandler(Runnable handler) {
		terminal.handle(Terminal.Signal.INT, handler == null ? Terminal.SignalHandler.SIG_DFL : signal -> handler.run());
	}

	/** Exposed for tests only: the terminal whose signal handling {@link #setInterruptHandler} configures. */
	Terminal terminalForTesting() {
		return terminal;
	}

	/** Exposed for tests only - lets a test drive completion/history through the exact same {@link LineReader} instance {@link #readLine} uses. */
	LineReader lineReaderForTesting() {
		return lineReader;
	}

	/**
	 * SPRINT XT02B, section 7: explicitly idempotent - {@code BroadSQL.main}'s normal shutdown path and
	 * {@code CommandInterpreter}'s shutdown-hook fallback can both reach this (the hook exists precisely
	 * to cover non-EXIT termination), and once this also saves a persistent history file, closing twice
	 * must never double-save or race, not merely rely on {@code Terminal.close()} happening to tolerate
	 * a second call.
	 */
	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		try {
			lineReader.getHistory().save();
		} catch (IOException e) {
			// Best-effort - a history save failure must never prevent terminal cleanup below (SPRINT
			// XT02B, section 7: "a failure while saving history during shutdown must not prevent
			// terminal cleanup or normal EXIT").
		}
		try {
			terminal.close();
		} catch (IOException e) {
			// Best-effort - nothing meaningful to do if the terminal fails to close during shutdown.
		}
	}
}
