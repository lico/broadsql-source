package com.upandcoding.broadsql.controller.shell.style;

import java.util.List;
import java.util.Map;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * SPRINT 2409K: the highlighter is part of the theme subsystem (it only picks roles and asks the current
 * {@link TerminalStyle} for them); its output always has exactly the buffer's characters.
 */
class TestSyntaxHighlighter {

	private final Map<String, Object> registry = Map.of("SHOW TABLES", "x", "SHOW", "x", "LIB RUN", "x", "@", "x", "_DEFAULT_COMMAND", "x");
	private final SyntaxHighlighter highlighter = new SyntaxHighlighter(SyntaxHighlighter.keywordsOf(registry, "_DEFAULT_COMMAND"));

	@AfterEach
	void reset() {
		TerminalStyleHolder.set(null);
	}

	private static TerminalStyle on() throws Exception {
		return TerminalStyle.resolve(ColorMode.ON, Theme.named(Theme.DEFAULT_DARK), TestTerminalStyle.colorTerminal());
	}

	private static AttributedStyle styleAt(AttributedString s, String text, String fragment) {
		return s.styleAt(text.indexOf(fragment));
	}

	@Test
	void sqlKeywordsLiteralsAndCommentsGetTheirRoles() throws Exception {
		TerminalStyle style = on();
		String sql = "select name, 'it''s' from T where id > 10 -- note";
		AttributedString result = highlighter.highlight(style, sql);
		Assertions.assertEquals(sql, result.toString());
		Assertions.assertEquals(style.attributes(StyleRole.SQL_KEYWORD), styleAt(result, sql, "select"));
		Assertions.assertEquals(style.attributes(StyleRole.SQL_KEYWORD), styleAt(result, sql, "where"));
		Assertions.assertEquals(style.attributes(StyleRole.SQL_LITERAL), styleAt(result, sql, "'it''s'"));
		Assertions.assertEquals(style.attributes(StyleRole.SQL_LITERAL), styleAt(result, sql, "10"));
		Assertions.assertEquals(style.attributes(StyleRole.SQL_COMMENT), styleAt(result, sql, "-- note"));
		Assertions.assertEquals(AttributedStyle.DEFAULT, styleAt(result, sql, "name"));
	}

	@Test
	void broadSqlCommandsComeFromTheRegistryLongestKeywordFirst() throws Exception {
		TerminalStyle style = on();
		String line = "show tables CUST";
		AttributedString result = highlighter.highlight(style, line);
		Assertions.assertEquals(line, result.toString());
		Assertions.assertEquals(style.attributes(StyleRole.COMMAND), styleAt(result, line, "tables"));
		Assertions.assertEquals(style.attributes(StyleRole.COMMAND_ARGUMENT), styleAt(result, line, "CUST"));
		Assertions.assertEquals(line.indexOf(" CUST"), highlighter.commandKeywordLength(line));
		Assertions.assertEquals(1, highlighter.commandKeywordLength("@maintenance/x.bsql"));
		Assertions.assertEquals(0, highlighter.commandKeywordLength("showing"), "a keyword must end at a word boundary");
		Assertions.assertEquals(0, highlighter.commandKeywordLength("SELECT 1"));
	}

	@Test
	void unknownAndVendorSyntaxIsLeftAsTyped() throws Exception {
		String odd = "MERGE INTO t USING (SELECT 1 FROM dual) ON (1=1) /* hint */ WHEN MATCHED THEN UPDATE SET x = :p || $$raw$$ <@ids.txt>";
		AttributedString result = highlighter.highlight(on(), odd);
		Assertions.assertEquals(odd, result.toString());
		String unterminated = "SELECT 'no end /* nor here";
		Assertions.assertEquals(unterminated, highlighter.highlight(on(), unterminated).toString());
	}

	@Test
	void styleOffOrThemeNoneLeavesTheLinePlain() throws Exception {
		TerminalStyleHolder.set(TerminalStyle.resolve(ColorMode.OFF, Theme.named(Theme.DEFAULT_DARK), TestTerminalStyle.colorTerminal()));
		AttributedString off = highlighter.highlight((org.jline.reader.LineReader) null, "SELECT 1");
		Assertions.assertEquals("SELECT 1", off.toString());
		Assertions.assertEquals(AttributedStyle.DEFAULT, off.styleAt(0));

		TerminalStyleHolder.set(TerminalStyle.resolve(ColorMode.ON, Theme.named(Theme.NONE), TestTerminalStyle.colorTerminal()));
		Assertions.assertEquals(AttributedStyle.DEFAULT, highlighter.highlight((org.jline.reader.LineReader) null, "SELECT 1").styleAt(0));
	}

	@Test
	void aFailingKeywordSourceNeverBreaksInput() throws Exception {
		TerminalStyleHolder.set(on());
		SyntaxHighlighter broken = new SyntaxHighlighter(() -> {
			throw new IllegalStateException("registry unavailable");
		});
		Assertions.assertEquals("SELECT 1", broken.highlight((org.jline.reader.LineReader) null, "SELECT 1").toString());
		Assertions.assertEquals(List.of(), SyntaxHighlighter.keywordsOf(null, "x").get());
	}
}
