package com.upandcoding.broadsql.controller.shell.completion;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidateType;

/** SPRINT 0917-02, section 41 - unit tests for {@link SqlKeywordCompletionProvider}. */
class TestSqlKeywordCompletionProvider {

	private final SqlKeywordCompletionProvider provider = new SqlKeywordCompletionProvider();

	private List<String> completeValues(String textWithCursor) {
		int cursor = textWithCursor.indexOf('|');
		String text = textWithCursor.substring(0, cursor) + textWithCursor.substring(cursor + 1);
		CompletionContext ctx = SqlCompletionLexer.analyze(text, cursor, false);
		return provider.complete(ctx).stream().map(CompletionCandidate::getValue).toList();
	}

	@Test
	void selStartOfStatementCompletesToSelect() {
		Assertions.assertEquals(List.of("SELECT"), completeValues("SEL|"));
	}

	@Test
	void matchingIsCaseInsensitive() {
		Assertions.assertEquals(List.of("SELECT"), completeValues("sel|"));
		Assertions.assertEquals(List.of("SELECT"), completeValues("Sel|"));
	}

	@Test
	void fromAfterSelectList() {
		Assertions.assertTrue(completeValues("SELECT * fr|").contains("FROM"));
	}

	@Test
	void whereAfterFromClause() {
		Assertions.assertTrue(completeValues("SELECT * FROM customer wh|").contains("WHERE"));
	}

	@Test
	void groupByTwoStepCompletion() {
		Assertions.assertTrue(completeValues("SELECT region, COUNT(*) gr|").contains("GROUP"));
		Assertions.assertEquals(List.of("BY"), completeValues("SELECT region, COUNT(*) GROUP b|"));
	}

	@Test
	void orderByTwoStepCompletion() {
		Assertions.assertEquals(List.of("BY"), completeValues("SELECT * FROM customer ORDER b|"));
	}

	@Test
	void leftOffersJoinAndOuter() {
		List<String> values = completeValues("SELECT * FROM customer LEFT |");
		Assertions.assertTrue(values.contains("JOIN"));
		Assertions.assertTrue(values.contains("OUTER"));
	}

	@Test
	void noKeywordsRightAfterFromTableNameIsExpectedInstead() {
		Assertions.assertTrue(completeValues("SELECT * FR|").contains("FROM"));
		Assertions.assertTrue(completeValues("SELECT * FROM |").isEmpty());
	}

	@Test
	void candidatesAreTypedAsSqlKeyword() {
		List<CompletionCandidate> candidates = provider.complete(SqlCompletionLexer.analyze("SEL", 3, false));
		Assertions.assertEquals(1, candidates.size());
		Assertions.assertEquals(CompletionCandidateType.SQL_KEYWORD, candidates.get(0).getType());
	}

	@Test
	void insertUpdateDeleteBasics() {
		Assertions.assertEquals(List.of("INTO"), completeValues("INSERT |"));
		Assertions.assertEquals(List.of("FROM"), completeValues("DELETE |"));
		Assertions.assertTrue(completeValues("UPDATE customer |").contains("SET"));
	}
}
