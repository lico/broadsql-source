package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.awt.GraphicsEnvironment;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Exercises {@link ClipboardListSource} against the real system clipboard - empirically confirmed
 * working in this project's dev environment (non-headless, real Windows desktop session) before this
 * class was written. Skipped (not failed) in a genuinely headless environment (e.g. some CI runners),
 * via {@link Assumptions#assumeFalse}, since there is no clipboard to test against there.
 */
class TestClipboardListSource {

	@BeforeEach
	void requiresAUsableSystemClipboard() {
		Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no system clipboard available in a headless environment");
		// Beyond headless: the OS clipboard itself can be transiently or persistently locked by something
		// external to this session (a clipboard manager, cloud clipboard sync...) - ClipboardAccess already
		// retries a genuinely transient lock; if it still fails after that, skip rather than fail these
		// tests for a condition outside BroadSQL's own code.
		try {
			ClipboardAccess.writeText("BROADSQL_TEST_CLIPBOARD_PROBE");
		} catch (BroadSQLException e) {
			Assumptions.abort("system clipboard not currently usable in this environment: " + e.getMessage());
		}
	}

	private void setClipboardText(String text) throws BroadSQLException {
		ClipboardAccess.writeText(text);
	}

	@Test
	void readsASingleColumnCopiedFromExcel() throws BroadSQLException {
		setClipboardText("C123\r\nC456\r\nC789\r\n");

		Assertions.assertEquals(List.of("C123", "C456", "C789"), new ClipboardListSource().values());
	}

	@Test
	void ignoresBlankRows() throws BroadSQLException {
		setClipboardText("A\r\n\r\nB\r\n   \r\nC");

		Assertions.assertEquals(List.of("A", "B", "C"), new ClipboardListSource().values());
	}

	@Test
	void handlesPlainLfAndNoTrailingNewline() throws BroadSQLException {
		setClipboardText("A\nB\nC");

		Assertions.assertEquals(List.of("A", "B", "C"), new ClipboardListSource().values());
	}

	@Test
	void emptyClipboardProducesAnEmptyList() throws BroadSQLException {
		setClipboardText("");

		Assertions.assertTrue(new ClipboardListSource().values().isEmpty());
	}

	@Test
	void rejectsMultipleTabSeparatedColumnsWithAClearError() throws BroadSQLException {
		setClipboardText("A\tB\nC\tD\n");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> new ClipboardListSource().values());
		Assertions.assertTrue(ex.getMessage().contains("multiple columns"), "got: " + ex.getMessage());
	}

	@Test
	void readsLeadingZeroValuesAsPlainTextUnchanged() throws BroadSQLException {
		setClipboardText("001\n002\n003");

		Assertions.assertEquals(List.of("001", "002", "003"), new ClipboardListSource().values());
	}

	@Test
	void readsUnicodeValues() throws BroadSQLException {
		setClipboardText("Müller\nÖzgür\n日本語");

		Assertions.assertEquals(List.of("Müller", "Özgür", "日本語"), new ClipboardListSource().values());
	}

	@Test
	void readsALargeList() throws BroadSQLException {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			sb.append("ID").append(i).append('\n');
		}
		setClipboardText(sb.toString());

		List<String> values = new ClipboardListSource().values();
		Assertions.assertEquals(5000, values.size());
		Assertions.assertEquals("ID0", values.get(0));
		Assertions.assertEquals("ID4999", values.get(4999));
	}
}
