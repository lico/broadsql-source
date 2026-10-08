package com.upandcoding.broadsql.controller.shell.reader;

import java.util.concurrent.TimeUnit;

import org.jline.utils.InfoCmp.Capability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.completion.PendingStatementBufferHolder;
import com.upandcoding.broadsql.controller.shell.reader.TestJLineConsoleLineReaderShortcutKeys.KeySession;

/**
 * GitHub #207: a TAB in pasted SQL, or a TAB typed as indentation, is whitespace; it never runs completion against
 * an empty prefix, which used to insert the first candidate (for example {@code ALL}) into the user's SQL.
 *
 * <p>Every keystroke goes through JLine's real key dispatch ({@code createForTestingWithRealKeyBindings}): a
 * {@code DumbTerminal} inserts a Tab byte literally and could never show the defect. Each test runs on the
 * {@code windows-vtp} terminal type (what JLine's Windows terminal emulates) and on {@code xterm-256color}.
 *
 * <p>Two paste paths are covered. A terminal with bracketed paste wraps the pasted text in {@code ESC[200~} ...
 * {@code ESC[201~}, and JLine inserts it into the buffer literally ({@link #BRACKETED_PASTE_START}). A terminal
 * without it (the Windows console delivers a paste as ordinary key events) sends the same characters as typing:
 * every line break submits a line, and each continuation line starts with its TAB, which the indentation rule
 * keeps as whitespace.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class TestJLineConsoleLineReaderTabAndPaste {

	private static final String BRACKETED_PASTE_START = "\u001b[200~";
	private static final String BRACKETED_PASTE_END = "\u001b[201~";

	/** The issue's script, with real TAB characters. */
	private static final String SCRIPT = "SELECT A,\n\tB,\n\tC\nFROM XYZ\nWHERE 1=1\n\tAND WHATEVER;";

	@AfterEach
	void clearPendingStatement() {
		PendingStatementBufferHolder.clear();
	}

	private static KeySession session(String terminalType) throws Exception {
		return new KeySession(terminalType, new CommandList());
	}

	// ---------------------------------------------------------------- pasted text

	@ParameterizedTest
	@ValueSource(strings = { "windows-vtp", "xterm-256color" })
	void aBracketedPasteIsInsertedLiterallyWithItsTabsAndLineBreaks(String terminalType) throws Exception {
		try (KeySession s = session(terminalType)) {
			s.keys(BRACKETED_PASTE_START + SCRIPT + BRACKETED_PASTE_END).keys("\r");
			Assertions.assertEquals(SCRIPT, s.readLine());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "windows-vtp", "xterm-256color" })
	void tabsBetweenTokensOfABracketedPasteStayTabs(String terminalType) throws Exception {
		String pasted = "SELECT\tID,\tNAME\tFROM\tT\tWHERE\t1=1\tAND\tSTATUS\t=\t1;";
		try (KeySession s = session(terminalType)) {
			s.keys(BRACKETED_PASTE_START + pasted + BRACKETED_PASTE_END).keys("\r");
			Assertions.assertEquals(pasted, s.readLine());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "windows-vtp", "xterm-256color" })
	void aBracketedPasteWithCarriageReturnLineBreaksKeepsItsLines(String terminalType) throws Exception {
		try (KeySession s = session(terminalType)) {
			s.keys(BRACKETED_PASTE_START + SCRIPT.replace("\n", "\r\n") + BRACKETED_PASTE_END).keys("\r");
			// JLine turns each pasted \r into \n: a CRLF line break becomes an empty line, harmless in SQL.
			Assertions.assertEquals(SCRIPT, s.readLine().replace("\n\n", "\n"));
		}
	}

	/**
	 * A paste the terminal does not mark (the Windows console): each line is submitted on its own, as BroadSQL's
	 * interpreter reads a multi-line statement, with the earlier lines pending. No line may gain a candidate.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "windows-vtp", "xterm-256color" })
	void anUnmarkedPasteKeepsEveryLineExactlyAsPasted(String terminalType) throws Exception {
		String[] lines = SCRIPT.split("\n");
		try (KeySession s = session(terminalType)) {
			s.keys(SCRIPT.replace("\n", "\r") + "\r");
			StringBuilder pending = new StringBuilder();
			for (String expected : lines) {
				PendingStatementBufferHolder.set(pending.toString());
				String line = s.reader().readCommandLine("SQL> ", pending.length() > 0);
				Assertions.assertEquals(expected, line);
				pending.append(line).append('\n');
			}
		}
	}

	// ---------------------------------------------------------------- physical TAB

	@ParameterizedTest
	@ValueSource(strings = { "windows-vtp", "xterm-256color" })
	void tabAtTheBeginningOfAContinuationLineIsIndentationNotALL(String terminalType) throws Exception {
		PendingStatementBufferHolder.set("SELECT A,\n");
		try (KeySession s = session(terminalType)) {
			s.keys("\tB,\r");
			Assertions.assertEquals("\tB,", s.reader().readCommandLine("SQL> ", true));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "windows-vtp", "xterm-256color" })
	void tabOnAnEmptyPromptIsIndentation(String terminalType) throws Exception {
		try (KeySession s = session(terminalType)) {
			s.keys("\tAND STATUS = 1\r");
			Assertions.assertEquals("\tAND STATUS = 1", s.readLine());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "windows-vtp", "xterm-256color" })
	void repeatedTabsInAnIndentationPositionAreAllKept(String terminalType) throws Exception {
		PendingStatementBufferHolder.set("SELECT A\nFROM XYZ\nWHERE 1=1\n");
		try (KeySession s = session(terminalType)) {
			s.keys("  \t\tAND X = 1;\r");
			Assertions.assertEquals("  \t\tAND X = 1;", s.reader().readCommandLine("SQL> ", true));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "windows-vtp", "xterm-256color" })
	void tabBeforeExistingTextAtTheStartOfTheLineIndentsIt(String terminalType) throws Exception {
		try (KeySession s = session(terminalType)) {
			s.keys("AND X = 1");
			s.keys(Capability.key_home).keys("\t\r");
			Assertions.assertEquals("\tAND X = 1", s.readLine());
		}
	}

	/** Mid-line, an empty prefix lists the candidates (on screen) and leaves the line as typed: no candidate is chosen for the user. */
	@ParameterizedTest
	@ValueSource(strings = { "windows-vtp", "xterm-256color" })
	void tabAfterASpaceMidLineListsButInsertsNothing(String terminalType) throws Exception {
		PendingStatementBufferHolder.set("SELECT A,\n");
		try (KeySession s = session(terminalType)) {
			s.keys("B, \t").pause(200).keys("C\r");
			Assertions.assertEquals("B, C", s.reader().readCommandLine("SQL> ", true));
			Assertions.assertTrue(s.screen().contains("ALL"), "the candidates are listed on screen: " + s.screen());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "windows-vtp", "xterm-256color" })
	void aNonEmptyPrefixStillCompletes(String terminalType) throws Exception {
		try (KeySession s = session(terminalType)) {
			s.keys("SEL\t\r");
			Assertions.assertEquals("SELECT ", s.readLine());
		}
		try (KeySession s = session(terminalType)) {
			s.keys("sel\t\r");
			Assertions.assertEquals("SELECT ", s.readLine());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "windows-vtp", "xterm-256color" })
	void aNonEmptyPrefixOnAnIndentedContinuationLineStillCompletes(String terminalType) throws Exception {
		PendingStatementBufferHolder.set("SELECT A\n");
		try (KeySession s = session(terminalType)) {
			s.keys("\tfr\tXYZ;\r");
			Assertions.assertEquals("\tFROM XYZ;", s.reader().readCommandLine("SQL> ", true));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "windows-vtp", "xterm-256color" })
	void theCompletionMenuStillCyclesWithTabAndShiftTab(String terminalType) throws Exception {
		try (KeySession s = new KeySession(terminalType, showEndpointCommands())) {
			s.keys("show end\t\t\r\r");
			Assertions.assertEquals("show ENDPOINTS ", s.readLine());
		}
		try (KeySession s = new KeySession(terminalType, showEndpointCommands())) {
			s.keys("show end\t\t").keys(Capability.key_btab).keys("\r\r");
			Assertions.assertEquals("show ENDPOINT ", s.readLine());
		}
	}

	private static CommandList showEndpointCommands() {
		CommandList commandList = new CommandList();
		for (com.upandcoding.broadsql.controller.shell.commands.Command command : new com.upandcoding.broadsql.controller.shell.commands.Command[] {
				new com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowEndpoint(),
				new com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowEndpoints() }) {
			for (String keyword : command.getKeywords()) {
				commandList.put(keyword, command);
			}
		}
		return commandList;
	}
}
