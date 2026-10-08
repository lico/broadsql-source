package com.upandcoding.broadsql.controller.shell.reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jline.keymap.KeyMap;
import org.jline.reader.History;
import org.jline.utils.InfoCmp.Capability;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowEndpoint;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowEndpoints;

/**
 * SPRINT 2209C (GitHub #150) - the interactive console keyboard contract, driven through JLine's real
 * key-dispatch path: bytes written to the terminal's input stream, decoded by JLine's
 * {@code BindingReader} against the active keymap, and observed as the line {@link JLineConsoleLineReader#readLine}
 * finally returns. No test here calls a widget or a buffer method directly.
 *
 * <p>Unless a test says otherwise the terminal type is {@code windows-vtp}: on Windows, JLine's JNI
 * terminal ({@code AbstractWindowsTerminal.processKeyEvent}) turns the Esc key into a single
 * {@code \033} character and every arrow/Home/End/Delete key into that terminal type's capability
 * string, written in one burst. {@link KeySession} writes each key the same way (one write per key),
 * and takes each key's bytes from the terminal's own capabilities rather than hard-coding them.
 *
 * <p>A standalone Esc is only recognized once JLine's ambiguity timeout expires with no further input
 * (see {@code JLineConsoleLineReader#ESC_AMBIGUITY_TIMEOUT_MS}); {@link KeySession#esc()} therefore
 * waits comfortably longer than that before sending the next key, the way a person does.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class TestJLineConsoleLineReaderShortcutKeys {

	private static final String WINDOWS = "windows-vtp";
	private static final String ESC = "\u001b";
	private static final long AFTER_ESC_PAUSE_MS = 400;

	private static String ctrl(char c) {
		return String.valueOf((char) (Character.toUpperCase(c) & 0x1f));
	}

	/** One reader over a pipe, fed by a thread that stays alive (a dead writer breaks {@link PipedInputStream}) until {@link #close()}. */
	static final class KeySession implements AutoCloseable {

		private static final Object STOP = new Object();

		private final PipedOutputStream keyboard = new PipedOutputStream();
		private final ByteArrayOutputStream screen = new ByteArrayOutputStream();
		private final LinkedBlockingQueue<Object> steps = new LinkedBlockingQueue<>();
		private final JLineConsoleLineReader reader;
		private final Thread feeder;

		KeySession(String terminalType, CommandList commandList) throws IOException {
			PipedInputStream in = new PipedInputStream(keyboard, 4096);
			reader = JLineConsoleLineReader.createForTestingWithRealKeyBindings(in, screen, null, null, commandList, null, terminalType);
			feeder = new Thread(this::feed, "key-feeder");
			feeder.setDaemon(true);
			feeder.start();
		}

		KeySession(String terminalType) throws IOException {
			this(terminalType, new CommandList());
		}

		private void feed() {
			try {
				while (true) {
					Object step = steps.take();
					if (step == STOP) {
						return;
					}
					if (step instanceof Long pause) {
						Thread.sleep(pause);
					} else if (step instanceof byte[] bytes) {
						// One byte at a time with a small gap - a slow or split sequence.
						for (byte b : bytes) {
							keyboard.write(b);
							keyboard.flush();
							Thread.sleep(5);
						}
					} else {
						keyboard.write(((String) step).getBytes(StandardCharsets.UTF_8));
						keyboard.flush();
					}
				}
			} catch (InterruptedException | IOException e) {
				// session closed
			}
		}

		/** Types text, or sends one key's complete sequence, in a single write. */
		KeySession keys(String keys) {
			steps.add(keys);
			return this;
		}

		KeySession keys(Capability key) {
			return keys(key(key));
		}

		/** Sends a sequence one byte at a time, 5 ms apart. */
		KeySession slowly(String keys) {
			steps.add(keys.getBytes(StandardCharsets.UTF_8));
			return this;
		}

		KeySession pause(long millis) {
			steps.add(millis);
			return this;
		}

		KeySession esc() {
			return keys(ESC).pause(AFTER_ESC_PAUSE_MS);
		}

		/** The exact byte sequence this terminal type sends for {@code key}. */
		String key(Capability key) {
			String sequence = KeyMap.key(reader.lineReaderForTesting().getTerminal(), key);
			Assertions.assertFalse(sequence == null || sequence.isEmpty(), "terminal type has no " + key);
			return sequence;
		}

		String readLine() {
			return reader.readLine("SQL> ");
		}

		JLineConsoleLineReader reader() {
			return reader;
		}

		List<String> history() {
			List<String> lines = new ArrayList<>();
			for (History.Entry entry : reader.lineReaderForTesting().getHistory()) {
				lines.add(entry.line());
			}
			return lines;
		}

		String screen() {
			return screen.toString(StandardCharsets.UTF_8);
		}

		@Override
		public void close() {
			steps.add(STOP);
			reader.close();
			// Ctrl+C makes JLine interrupt the reading thread and re-assert that flag on exit.
			Thread.interrupted();
		}
	}

	private static String type(String terminalType, java.util.function.Consumer<KeySession> keystrokes) throws IOException {
		try (KeySession session = new KeySession(terminalType)) {
			keystrokes.accept(session);
			return session.readLine();
		}
	}

	private static String type(java.util.function.Consumer<KeySession> keystrokes) throws IOException {
		return type(WINDOWS, keystrokes);
	}

	private static CommandList showEndpointCommands() {
		CommandList commandList = new CommandList();
		for (Command command : new Command[] { new CommandShowEndpoint(), new CommandShowEndpoints() }) {
			for (String keyword : command.getKeywords()) {
				commandList.put(keyword, command);
			}
		}
		return commandList;
	}

	// ---------------------------------------------------------------- Esc: cancel the whole input

	@Test
	void escCancelsASimpleCommandAndNothingIsSubmitted() throws Exception {
		try (KeySession s = new KeySession(WINDOWS)) {
			s.keys("SHOW TABLES").esc().keys("\r");
			Assertions.assertEquals("", s.readLine());
			Assertions.assertEquals(List.of(), s.history(), "cancelled input must not reach history");
		}
	}

	@Test
	void escLeavesAnEmptyBufferSoOnlyWhatIsTypedAfterwardsIsSubmitted() throws Exception {
		Assertions.assertEquals("SHOW CONNECTIONS", type(s -> s.keys("SHOW TABLES").esc().keys("SHOW CONNECTIONS\r")));
	}

	@Test
	void escCancelsPartialSql() throws Exception {
		Assertions.assertEquals("X", type(s -> s.keys("SELECT * FROM CUSTOMER WH").esc().keys("X\r")));
	}

	@Test
	void escWithTheCursorInTheMiddleCancelsTheWholeLineNotJustAroundTheCursor() throws Exception {
		Assertions.assertEquals("X", type(s -> {
			s.keys("ABCDEFGHIJ");
			for (int i = 0; i < 5; i++) {
				s.keys(Capability.key_left);
			}
			s.esc().keys("X\r");
		}));
	}

	@Test
	void aMultiLineJLineBufferIsReallyMultiLine() throws Exception {
		// Control for the next test: Ctrl+V Ctrl+J inserts a literal newline, giving JLine a genuine
		// multi-line buffer (the only way to get one: BroadSQL's parser never asks JLine for continuation lines).
		Assertions.assertEquals("SELECT a,\nFROM t", type(s -> s.keys("SELECT a," + ctrl('V') + "\n" + "FROM t\r")));
	}

	@Test
	void escClearsEveryLineOfAMultiLineJLineBuffer() throws Exception {
		Assertions.assertEquals("X", type(s -> s.keys("SELECT a," + ctrl('V') + "\n" + "       b," + ctrl('V') + "\n" + "FROM t")
				.keys(Capability.key_up) // move onto an earlier row of the same buffer
				.esc().keys("X\r")));
	}

	@Test
	void escAfterHistoryRecallCancelsTheEditAndLeavesTheHistoryEntryUnchanged() throws Exception {
		try (KeySession s = new KeySession(WINDOWS)) {
			s.keys("SHOW TABLES\r");
			Assertions.assertEquals("SHOW TABLES", s.readLine());

			s.keys(Capability.key_up).keys(" MODIFIED").esc().keys("\r");
			Assertions.assertEquals("", s.readLine(), "the recalled, modified command must not be submitted");
			Assertions.assertEquals(List.of("SHOW TABLES"), s.history());

			s.keys(Capability.key_up).keys("\r");
			Assertions.assertEquals("SHOW TABLES", s.readLine(), "the stored entry must be the original one");
		}
	}

	@Test
	void escReturnsHistoryNavigationToTheNewestEntryLikeAFreshPrompt() throws Exception {
		try (KeySession s = new KeySession(WINDOWS)) {
			s.keys("one\r");
			s.readLine();
			s.keys("two\r");
			s.readLine();
			s.keys(Capability.key_up).keys(Capability.key_up).esc().keys(Capability.key_up).keys("\r");
			Assertions.assertEquals("two", s.readLine());
		}
	}

	@Test
	void escAfterAUniqueTabCompletionCancelsTheLine() throws Exception {
		Assertions.assertEquals("X", type(s -> s.keys("sel\t").esc().keys("X\r")));
	}

	@Test
	void escWhileTheCompletionMenuIsOpenCancelsTheLineNotOnlyTheMenu() throws Exception {
		try (KeySession s = new KeySession(WINDOWS, showEndpointCommands())) {
			s.keys("show end\t").pause(200).esc().keys("X\r");
			Assertions.assertEquals("X", s.readLine());
		}
	}

	@Test
	void escDuringCtrlRHistorySearchCancelsTheLine() throws Exception {
		try (KeySession s = new KeySession(WINDOWS)) {
			s.keys("SELECT * FROM CUSTOMER\r");
			s.readLine();
			s.keys(ctrl('R') + "CUST").pause(100).esc().keys("X\r");
			Assertions.assertEquals("X", s.readLine());
		}
	}

	@Test
	void aQuickDoubleEscStillCancels() throws Exception {
		Assertions.assertEquals("X", type(s -> s.keys("SHOW TABLES").keys(ESC + ESC).pause(AFTER_ESC_PAUSE_MS).keys("X\r")));
	}

	@Test
	void escOnAnEmptyPromptIsHarmless() throws Exception {
		Assertions.assertEquals("SHOW TABLES", type(s -> s.esc().keys("SHOW TABLES\r")));
	}

	@Test
	void escIsReportedOnceToTheInterpreterAndOnlyForTheReadItHappenedIn() throws Exception {
		try (KeySession s = new KeySession(WINDOWS)) {
			s.keys("SELECT a,\r");
			s.readLine();
			Assertions.assertFalse(s.reader.consumeInputCancellation(), "no Esc pressed");

			s.keys("FROM t").esc().keys("SHOW TABLES;\r");
			Assertions.assertEquals("SHOW TABLES;", s.readLine());
			Assertions.assertTrue(s.reader.consumeInputCancellation(), "Esc pressed during this read");
			Assertions.assertFalse(s.reader.consumeInputCancellation(), "reported only once");
		}
	}

	@Test
	void escForgetsThePendingStatementTextUsedByTabCompletion() throws Exception {
		try (KeySession s = new KeySession(WINDOWS)) {
			com.upandcoding.broadsql.controller.shell.completion.PendingStatementBufferHolder.set("SELECT a, ");
			try {
				s.keys("FROM t").esc().keys("X\r");
				s.readLine();
				Assertions.assertEquals("", com.upandcoding.broadsql.controller.shell.completion.PendingStatementBufferHolder.get());
			} finally {
				com.upandcoding.broadsql.controller.shell.completion.PendingStatementBufferHolder.clear();
			}
		}
	}

	@Test
	void escIsResolvedWithinJLinesAmbiguityTimeoutNotAtTheNextKey() throws Exception {
		// No key follows Esc until well after the timeout: if Esc were still waiting for a following key
		// (the defect), the "X" would be merged into it instead of being typed on an empty line.
		Assertions.assertTrue(JLineConsoleLineReader.ESC_AMBIGUITY_TIMEOUT_MS < AFTER_ESC_PAUSE_MS);
		Assertions.assertEquals("X", type(s -> s.keys("SHOW TABLES").keys(ESC).pause(JLineConsoleLineReader.ESC_AMBIGUITY_TIMEOUT_MS * 3).keys("X\r")));
	}

	// ------------------------------------------- escape-sequence keys must be unaffected by the Esc binding

	@ParameterizedTest
	@ValueSource(strings = { WINDOWS, "xterm-256color" })
	void leftMovesTheCursorLeft(String terminalType) throws Exception {
		Assertions.assertEquals("abXc", type(terminalType, s -> s.keys("abc").keys(Capability.key_left).keys("X\r")));
	}

	@ParameterizedTest
	@ValueSource(strings = { WINDOWS, "xterm-256color" })
	void rightMovesTheCursorRight(String terminalType) throws Exception {
		Assertions.assertEquals("aXbc", type(terminalType, s -> s.keys("abc").keys(Capability.key_home).keys(Capability.key_right).keys("X\r")));
	}

	@ParameterizedTest
	@ValueSource(strings = { WINDOWS, "xterm-256color" })
	void homeMovesToTheBeginning(String terminalType) throws Exception {
		Assertions.assertEquals("Xabc", type(terminalType, s -> s.keys("abc").keys(Capability.key_home).keys("X\r")));
	}

	@ParameterizedTest
	@ValueSource(strings = { WINDOWS, "xterm-256color" })
	void endMovesToTheEnd(String terminalType) throws Exception {
		Assertions.assertEquals("abcX", type(terminalType, s -> s.keys("abc").keys(Capability.key_home).keys(Capability.key_end).keys("X\r")));
	}

	@ParameterizedTest
	@ValueSource(strings = { WINDOWS, "xterm-256color" })
	void deleteRemovesTheCharacterAtTheCursor(String terminalType) throws Exception {
		Assertions.assertEquals("bc", type(terminalType, s -> s.keys("abc").keys(Capability.key_home).keys(Capability.key_dc).keys("\r")));
	}

	@ParameterizedTest
	@ValueSource(strings = { WINDOWS, "xterm-256color" })
	void backspaceRemovesThePreviousCharacter(String terminalType) throws Exception {
		Assertions.assertEquals("ab", type(terminalType, s -> s.keys("abc").keys(Capability.key_backspace).keys("\r")));
	}

	@ParameterizedTest
	@ValueSource(strings = { WINDOWS, "xterm-256color" })
	void upAndDownNavigateHistory(String terminalType) throws Exception {
		try (KeySession s = new KeySession(terminalType)) {
			s.keys("one\r");
			s.readLine();
			s.keys("two\r");
			s.readLine();

			s.keys(Capability.key_up).keys(Capability.key_up).keys("\r");
			Assertions.assertEquals("one", s.readLine(), "Up twice reaches the older entry");

			s.keys(Capability.key_up).keys(Capability.key_up).keys(Capability.key_up).keys(Capability.key_down).keys("\r");
			Assertions.assertEquals("two", s.readLine(), "Down moves back toward newer entries");

			s.keys(Capability.key_up).keys(Capability.key_down).keys("\r");
			Assertions.assertEquals("", s.readLine(), "Down past the newest entry returns to an empty line");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { WINDOWS, "xterm-256color" })
	void tabStillCompletes(String terminalType) throws Exception {
		Assertions.assertEquals("SELECT ", type(terminalType, s -> s.keys("sel\t\r")));
	}

	@ParameterizedTest
	@ValueSource(strings = { WINDOWS, "xterm-256color" })
	void arrowsWorkImmediatelyAfterAnEscCancel(String terminalType) throws Exception {
		Assertions.assertEquals("aXb", type(terminalType, s -> s.keys("zzz").esc().keys("ab").keys(Capability.key_left).keys("X\r")));
	}

	@ParameterizedTest
	@ValueSource(strings = { WINDOWS, "xterm-256color" })
	void aSequenceArrivingOneByteAtATimeIsStillOneKey(String terminalType) throws Exception {
		Assertions.assertEquals("aXbc", type(terminalType, s -> {
			s.keys("abc");
			s.slowly(s.key(Capability.key_left)).slowly(s.key(Capability.key_left));
			s.keys("X\r");
		}));
	}

	@ParameterizedTest
	@ValueSource(strings = { WINDOWS, "xterm-256color" })
	void upStillRecallsHistoryWhenTheSequenceArrivesByteByByte(String terminalType) throws Exception {
		try (KeySession s = new KeySession(terminalType)) {
			s.keys("SHOW TABLES\r");
			s.readLine();
			s.slowly(s.key(Capability.key_up)).keys("\r");
			Assertions.assertEquals("SHOW TABLES", s.readLine());
		}
	}

	// ------------------------------------------------------------------------ standard Ctrl shortcuts

	@Test
	void ctrlAMovesToTheBeginning() throws Exception {
		Assertions.assertEquals("Xabcdef", type(s -> s.keys("abcdef" + ctrl('A') + "X\r")));
	}

	@Test
	void ctrlEMovesToTheEnd() throws Exception {
		Assertions.assertEquals("abcdefX", type(s -> s.keys("abcdef" + ctrl('A') + ctrl('E') + "X\r")));
	}

	@Test
	void ctrlUDeletesFromTheCursorBackToTheBeginning() throws Exception {
		Assertions.assertEquals("def", type(s -> s.keys("abcdef").keys(Capability.key_left).keys(Capability.key_left).keys(Capability.key_left)
				.keys(ctrl('U') + "\r")));
	}

	@Test
	void ctrlKDeletesFromTheCursorToTheEnd() throws Exception {
		Assertions.assertEquals("ab", type(s -> s.keys("abcdef" + ctrl('A')).keys(Capability.key_right).keys(Capability.key_right)
				.keys(ctrl('K') + "\r")));
	}

	@Test
	void ctrlWDeletesThePreviousWord() throws Exception {
		Assertions.assertEquals("SELECT * FROM ", type(s -> s.keys("SELECT * FROM CUSTOMER" + ctrl('W') + "\r")));
	}

	@Test
	void ctrlLClearsTheScreenAndKeepsTheInput() throws Exception {
		try (KeySession s = new KeySession(WINDOWS)) {
			s.keys("abc" + ctrl('L') + "d\r");
			Assertions.assertEquals("abcd", s.readLine());
			String clear = KeyMap.key(s.reader.lineReaderForTesting().getTerminal(), Capability.clear_screen);
			Assertions.assertTrue(s.screen().contains(clear), "clear_screen was not written to the terminal");
		}
	}

	@Test
	void ctrlRFindsAnEarlierCommandInHistory() throws Exception {
		try (KeySession s = new KeySession(WINDOWS)) {
			for (String line : List.of("SELECT * FROM CUSTOMER", "SHOW TABLES", "DESC ORDERS")) {
				s.keys(line + "\r");
				s.readLine();
			}
			s.keys(ctrl('R') + "CUST").pause(100).keys("\r");
			Assertions.assertEquals("SELECT * FROM CUSTOMER", s.readLine());
		}
	}

	@Test
	void ctrlRAgainFindsAnOlderMatch() throws Exception {
		try (KeySession s = new KeySession(WINDOWS)) {
			for (String line : List.of("SELECT 1 FROM CUSTOMER", "SELECT 2 FROM CUSTOMER", "SHOW TABLES")) {
				s.keys(line + "\r");
				s.readLine();
			}
			s.keys(ctrl('R') + "CUST").pause(100).keys(ctrl('R')).pause(100).keys("\r");
			Assertions.assertEquals("SELECT 1 FROM CUSTOMER", s.readLine());
		}
	}

	@Test
	void endLeavesTheCtrlRMatchOnTheLineForEditing() throws Exception {
		try (KeySession s = new KeySession(WINDOWS)) {
			s.keys("SELECT * FROM CUSTOMER\r");
			s.readLine();
			s.keys(ctrl('R') + "CUST").pause(100).keys(Capability.key_end).keys(" WHERE 1=1\r");
			Assertions.assertEquals("SELECT * FROM CUSTOMER WHERE 1=1", s.readLine());
			Assertions.assertFalse(s.reader.consumeInputCancellation());
		}
	}

	@Test
	void ctrlCWhileEditingIsNotReportedAsACancellationOfTheWholeStatement() throws Exception {
		// Documented behavior: Ctrl+C abandons the current line only; CommandInterpreter keeps earlier lines.
		try (KeySession s = new KeySession(WINDOWS)) {
			s.keys("FROM t" + ctrl('C'));
			Assertions.assertEquals("", s.readLine());
			Assertions.assertFalse(s.reader.consumeInputCancellation());
		}
	}

	@Test
	void ctrlCWhileEditingAbandonsTheLine() throws Exception {
		Assertions.assertEquals("", type(s -> s.keys("SHOW TABLES" + ctrl('C'))));
	}

	@Test
	void ctrlDOnAnEmptyLineIsEndOfInput() throws Exception {
		Assertions.assertNull(type(s -> s.keys(ctrl('D'))));
	}

	@Test
	void ctrlDWithTextDeletesTheCharacterAtTheCursor() throws Exception {
		Assertions.assertEquals("ac", type(s -> s.keys("abc").keys(Capability.key_left).keys(Capability.key_left).keys(ctrl('D') + "\r")));
	}

	// ------------------------------------------------ terminal-dependent keys (as JLine decodes them)

	@Test
	void ctrlLeftAndCtrlRightMoveByWordOnWindows() throws Exception {
		// What AbstractWindowsTerminal sends for Ctrl+Left / Ctrl+Right. JLine's forward-word stops at the
		// start of the next word (as Windows' own console does), not at the end of the current one.
		Assertions.assertEquals("SELECT CUSTOMER XNAME",
				type(s -> s.keys("SELECT CUSTOMER NAME").keys("\u001b[1;5D").keys("\u001b[1;5D").keys("\u001b[1;5C").keys("X\r")));
	}

	@Test
	void ctrlDeleteSentAsCsi3Semicolon5TildeDeletesTheNextWord() throws Exception {
		// xterm-style terminals send ESC[3;5~ for Ctrl+Delete; JLine's Windows terminal sends plain Delete.
		Assertions.assertEquals(" FROM T", type("xterm-256color", s -> s.keys("SELECT FROM T").keys(Capability.key_home).keys("\u001b[3;5~\r")));
	}

	@Test
	void ctrlBackspaceAndShiftBackspaceAreAPlainBackspaceOnWindows() throws Exception {
		// AbstractWindowsTerminal sends key_backspace for VK_BACK whatever Ctrl/Shift state (only Alt differs),
		// so JLine cannot tell these apart from Backspace: one character is deleted.
		Assertions.assertEquals("SELECT CUSTOME", type(s -> s.keys("SELECT CUSTOMER").keys(Capability.key_backspace).keys("\r")));
	}

	@Test
	void ctrlDeleteIsAPlainDeleteOnWindows() throws Exception {
		// AbstractWindowsTerminal sends key_dc for VK_DELETE whatever the modifiers.
		Assertions.assertEquals("ELECT FROM T", type(s -> s.keys("SELECT FROM T").keys(Capability.key_home).keys(Capability.key_dc).keys("\r")));
	}

	@Test
	void shiftTabOutsideTheCompletionMenuChangesNothing() throws Exception {
		Assertions.assertEquals("abc", type(s -> s.keys("abc").keys(Capability.key_btab).keys("\r")));
	}

	@Test
	void altBackspaceDeletesThePreviousWord() throws Exception {
		// What AbstractWindowsTerminal sends for Alt+Backspace.
		Assertions.assertEquals("SELECT ", type(s -> s.keys("SELECT CUSTOMER" + ESC + "\b" + "\r")));
	}
}
