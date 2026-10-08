package com.upandcoding.broadsql.controller.shell.completion;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidateType;

/** SPRINT 0917-02, section 41 - {@link CompletionEngine}: merging, dedupe, strings/comments guard, provider-failure tolerance. */
class TestCompletionEngine {

	private static CompletionProvider constant(CompletionCandidate... candidates) {
		return ctx -> List.of(candidates);
	}

	@Test
	void mergesResultsFromMultipleProvidersInOrder() {
		CompletionEngine engine = new CompletionEngine(List.of(
				constant(new CompletionCandidate("SHOW", "SHOW", null, CompletionCandidateType.BROADSQL_COMMAND)),
				constant(new CompletionCandidate("SELECT", "SELECT", null, CompletionCandidateType.SQL_KEYWORD))));
		List<CompletionCandidate> result = engine.complete(SqlCompletionLexer.analyze("s", 1, false));
		Assertions.assertEquals(List.of("SHOW", "SELECT"), result.stream().map(CompletionCandidate::getValue).toList());
	}

	@Test
	void dropsDuplicateKeywordCandidatesCaseInsensitively() {
		CompletionEngine engine = new CompletionEngine(List.of(
				constant(new CompletionCandidate("SELECT", "SELECT", null, CompletionCandidateType.SQL_KEYWORD)),
				constant(new CompletionCandidate("select", "select", null, CompletionCandidateType.SQL_KEYWORD))));
		List<CompletionCandidate> result = engine.complete(SqlCompletionLexer.analyze("s", 1, false));
		Assertions.assertEquals(1, result.size());
	}

	@Test
	void keepsDistinctCaseSensitiveIdentifierCandidates() {
		CompletionEngine engine = new CompletionEngine(List.of(
				constant(new CompletionCandidate("Customer", "Customer", null, CompletionCandidateType.TABLE),
						new CompletionCandidate("CUSTOMER", "CUSTOMER", null, CompletionCandidateType.TABLE))));
		List<CompletionCandidate> result = engine.complete(SqlCompletionLexer.analyze("c", 1, true));
		Assertions.assertEquals(2, result.size());
	}

	@Test
	void noCandidatesInsideAStringLiteral() {
		CompletionEngine engine = new CompletionEngine(List.of(
				constant(new CompletionCandidate("SELECT", "SELECT", null, CompletionCandidateType.SQL_KEYWORD))));
		CompletionContext ctx = SqlCompletionLexer.analyze("SELECT 'sel'", 11, false);
		Assertions.assertTrue(engine.complete(ctx).isEmpty());
	}

	@Test
	void aMisbehavingProviderNeverBreaksTheOthers() {
		CompletionProvider throwing = ctx -> {
			throw new RuntimeException("boom");
		};
		CompletionEngine engine = new CompletionEngine(List.of(
				throwing, constant(new CompletionCandidate("SELECT", "SELECT", null, CompletionCandidateType.SQL_KEYWORD))));
		List<CompletionCandidate> result = engine.complete(SqlCompletionLexer.analyze("s", 1, false));
		Assertions.assertEquals(1, result.size());
	}

	@Test
	void emptyProviderListReturnsNoCandidates() {
		CompletionEngine engine = new CompletionEngine(List.of());
		Assertions.assertTrue(engine.complete(SqlCompletionLexer.analyze("s", 1, false)).isEmpty());
	}
}
