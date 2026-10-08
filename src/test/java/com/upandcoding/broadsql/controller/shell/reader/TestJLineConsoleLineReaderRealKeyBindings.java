package com.upandcoding.broadsql.controller.shell.reader;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.commands.CommandList;

/**
 * Runtime defect fix, SPRINT 0917-02 - regression coverage for "TAB completion does not work at all in
 * the real interactive console" (reported after the sprint's own unit/JDBC-level tests all passed).
 *
 * <p>Root cause, found empirically (not from reading code): every completion test up to this point -
 * including {@code TestBroadSqlJLineCompleter} and {@code TestCompletionService} - calls
 * {@link org.jline.reader.Completer#complete} directly, and {@link TestJLineConsoleLineReader} drives
 * {@link JLineConsoleLineReader#readLine} only over a {@link org.jline.terminal.impl.DumbTerminal},
 * which (confirmed with a throwaway diagnostic program while investigating the report) never processes
 * key bindings at all - a Tab byte lands in the buffer as a literal {@code '\t'} character. No existing
 * test could therefore have exercised "does a real Tab keystroke, dispatched through JLine's own keymap,
 * actually get inserted as a completion" - only whether the completer function returns the right
 * candidates when called directly, which it always did.
 *
 * <p>The actual defect: JLine's own internal completion re-match is case-sensitive by default
 * ({@code LineReader.Option.CASE_INSENSITIVE} is off unless set), so it silently discarded every
 * candidate this codebase produces - always the canonical upper-case form, per section 15 of the sprint
 * spec - whenever the typed prefix was not already that same case (i.e. almost always, since
 * {@code sel<TAB>} is the natural way to type it). {@link BroadSqlJLineCompleter}/{@code CompletionEngine}
 * were never at fault - they always returned the correct candidate; JLine just never applied it.
 * {@code JLineConsoleLineReader.buildLineReader} now sets that option explicitly.
 *
 * <p>Uses {@link JLineConsoleLineReader#createForTestingWithRealKeyBindings} - a real, ANSI/key-binding-
 * capable terminal over streams, not the {@code DumbTerminal} the rest of this test class's siblings use
 * - specifically so a regression here would actually fail if this fix were ever reverted.
 */
class TestJLineConsoleLineReaderRealKeyBindings {

	private String driveKeystrokes(String keystrokes) throws Exception {
		PipedOutputStream keyboardOut = new PipedOutputStream();
		PipedInputStream keyboardIn = new PipedInputStream(keyboardOut);
		ByteArrayOutputStream screen = new ByteArrayOutputStream();

		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTestingWithRealKeyBindings(
				keyboardIn, screen, null, null, new CommandList(), null);
		try {
			byte[] bytes = keystrokes.getBytes(StandardCharsets.UTF_8);
			Thread feeder = new Thread(() -> {
				try {
					for (byte b : bytes) {
						keyboardOut.write(b);
						keyboardOut.flush();
						Thread.sleep(5);
					}
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			feeder.setDaemon(true);
			feeder.start();
			return reader.readLine("test> ");
		} finally {
			reader.close();
		}
	}

	@Test
	void tabCompletesALowercaseSqlKeywordThroughRealJLineKeyDispatch() throws Exception {
		Assertions.assertEquals("SELECT ", driveKeystrokes("sel\t\n"));
	}

	@Test
	void tabCompletesAMixedCaseSecondTokenThroughRealJLineKeyDispatch() throws Exception {
		Assertions.assertEquals("SELECT * FROM ", driveKeystrokes("SELECT * fr\t\n"));
	}

	@Test
	void tabCompletesWhenTypedCaseAlreadyMatchesTheCandidate() throws Exception {
		Assertions.assertEquals("SELECT ", driveKeystrokes("SEL\t\n"));
	}

	@Test
	void noMatchLeavesTheLineUnchangedThroughRealJLineKeyDispatch() throws Exception {
		Assertions.assertEquals("zzzzz", driveKeystrokes("zzzzz\t\n"));
	}
}
