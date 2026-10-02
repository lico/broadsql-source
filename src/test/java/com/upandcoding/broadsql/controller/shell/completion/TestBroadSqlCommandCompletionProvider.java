package com.upandcoding.broadsql.controller.shell.completion;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowEndpoints;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandConnect;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowAllConnections;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowSchemas;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowTables;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidateType;

/**
 * SPRINT 0917-02, section 41 - unit tests for {@link BroadSqlCommandCompletionProvider}, driven by a
 * {@link CommandList} populated with real, production {@link Command} classes (section 41: "no
 * hardcoded mismatch with actual command registry" - using the real classes is what actually enforces
 * that, rather than a hand-written fixture that could silently drift from them).
 */
class TestBroadSqlCommandCompletionProvider {

	private CommandList commandList;

	@BeforeEach
	void setUp() {
		commandList = new CommandList();
		register(new CommandConnect()); // CONNECT, OPEN, CONN, CON
		register(new CommandShowTables()); // SHOW TABLES, SH TA, SHTA
		register(new CommandShowSchemas()); // SHOW SCHEMAS, SH SC, SHSC
		register(new CommandShowEndpoints()); // SHOW ENDPOINTS, ENDPOINTS, ALL ENDPOINTS, SHENDS
		register(new CommandShowAllConnections()); // SHOW ALL CONNECTIONS, SH ALL CO, SH AL CO, SHALLCO, SHALCO
	}

	private void register(Command command) {
		for (String keyword : command.getKeywords()) {
			commandList.put(keyword, command);
		}
	}

	private List<CompletionCandidate> complete(String textWithCursor) {
		int cursor = textWithCursor.indexOf('|');
		String text = textWithCursor.substring(0, cursor) + textWithCursor.substring(cursor + 1);
		CompletionContext ctx = SqlCompletionLexer.analyze(text, cursor, false);
		return new BroadSqlCommandCompletionProvider(commandList).complete(ctx);
	}

	private List<String> values(String textWithCursor) {
		return complete(textWithCursor).stream().map(CompletionCandidate::getValue).toList();
	}

	@Test
	void uniqueSingleWordCanonicalCommandCompletesDirectly() {
		Assertions.assertEquals(List.of("CONNECT"), values("CONN|"));
	}

	@Test
	void ambiguousFirstTokenOffersTheSharedFirstWordOnceDeduped() {
		List<String> result = values("SH|");
		Assertions.assertEquals(List.of("SHOW"), result);
	}

	@Test
	void subcommandCompletionOffersTheMatchingCanonicalContinuation() {
		Assertions.assertEquals(List.of("ENDPOINTS"), values("SHOW END|"));
	}

	@Test
	void bareShowOffersEveryValidContinuation() {
		List<String> result = values("SHOW |");
		Assertions.assertTrue(result.contains("TABLES"), result.toString());
		Assertions.assertTrue(result.contains("SCHEMAS"), result.toString());
		Assertions.assertTrue(result.contains("ENDPOINTS"), result.toString());
		Assertions.assertTrue(result.contains("ALL"), result.toString());
	}

	@Test
	void aliasOnlyOfferedWhenNoCanonicalCommandSharesThePrefix() {
		// No canonical command starts with "SHAL" (canonical is "SHOW ALL CONNECTIONS") - only then
		// are the single-word aliases (section 23) offered.
		List<String> result = values("SHAL|");
		Assertions.assertTrue(result.contains("SHALCO"), result.toString());
		Assertions.assertTrue(result.contains("SHALLCO"), result.toString());
	}

	@Test
	void canonicalIsPreferredOverAliasWhenBothShareThePrefix() {
		// "SH" matches the canonical "SHOW" - the SHALCO/SHTA/SHSC/... single-word aliases must not
		// also be offered alongside it (section 23).
		List<String> result = values("SH|");
		Assertions.assertFalse(result.contains("SHALCO"), result.toString());
		Assertions.assertFalse(result.contains("SHTA"), result.toString());
	}

	@Test
	void matchingIsCaseInsensitive() {
		Assertions.assertEquals(List.of("CONNECT"), values("conn|"));
	}

	@Test
	void candidatesAreTypedCorrectly() {
		List<CompletionCandidate> first = complete("CONN|");
		Assertions.assertEquals(CompletionCandidateType.BROADSQL_COMMAND, first.get(0).getType());
		List<CompletionCandidate> sub = complete("SHOW END|");
		Assertions.assertEquals(CompletionCandidateType.BROADSQL_KEYWORD, sub.get(0).getType());
	}

	@Test
	void hiddenCommandsAreNeverOffered() {
		CommandShowEndpoints endpoints = new CommandShowEndpoints();
		endpoints.setHidden(true);
		CommandList onlyHidden = new CommandList();
		for (String keyword : endpoints.getKeywords()) {
			onlyHidden.put(keyword, endpoints);
		}
		int cursor = "SHOW END".length();
		CompletionContext ctx = SqlCompletionLexer.analyze("SHOW END", cursor, false);
		Assertions.assertTrue(new BroadSqlCommandCompletionProvider(onlyHidden).complete(ctx).isEmpty());
	}

	@Test
	void noMatchLeavesInputUnchanged() {
		Assertions.assertTrue(values("ZZZZZ|").isEmpty());
	}

	@Test
	void emptyCommandListReturnsNoCandidates() {
		CompletionContext ctx = SqlCompletionLexer.analyze("SEL", 3, false);
		Assertions.assertTrue(new BroadSqlCommandCompletionProvider(new CommandList()).complete(ctx).isEmpty());
	}
}
