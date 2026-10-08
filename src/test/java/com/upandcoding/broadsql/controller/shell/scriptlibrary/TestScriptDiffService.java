package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestScriptDiffService {

	private final ScriptDiffService diffService = new ScriptDiffService();

	@Test
	void identicalContentHasNoChanges() {
		TextDiffResult result = diffService.diffContent("select 1;\nselect 2;", "select 1;\nselect 2;");

		Assertions.assertFalse(result.hasChanges());
		Assertions.assertEquals(0, result.additions());
		Assertions.assertEquals(0, result.deletions());
		Assertions.assertEquals(0, result.modifications());
		Assertions.assertTrue(result.changedRowIndices().isEmpty());
		Assertions.assertTrue(result.lines().stream().allMatch(l -> l.tag() == DiffLine.Tag.EQUAL));
	}

	@Test
	void additionsAreDetected() {
		TextDiffResult result = diffService.diffContent("select 1;", "select 1;\nselect 2;");

		Assertions.assertEquals(1, result.additions());
		Assertions.assertEquals(0, result.deletions());
		Assertions.assertEquals(0, result.modifications());
	}

	@Test
	void deletionsAreDetected() {
		TextDiffResult result = diffService.diffContent("select 1;\nselect 2;", "select 1;");

		Assertions.assertEquals(0, result.additions());
		Assertions.assertEquals(1, result.deletions());
		Assertions.assertEquals(0, result.modifications());
	}

	@Test
	void modificationsAreDetectedWithIntraLineHighlighting() {
		TextDiffResult result = diffService.diffContent("select id, name from customer;", "select id, name, status from customer;");

		Assertions.assertEquals(0, result.additions());
		Assertions.assertEquals(0, result.deletions());
		Assertions.assertEquals(1, result.modifications());
		DiffLine changed = result.lines().stream().filter(l -> l.tag() == DiffLine.Tag.CHANGE).findFirst().orElseThrow();
		Assertions.assertFalse(changed.newHighlightRanges().isEmpty(), "the added ', status' fragment must be highlighted within the line, not just the whole line flagged changed");
		Assertions.assertEquals("select id, name, status from customer;", changed.newText());
		Assertions.assertEquals("select id, name from customer;", changed.oldText());
	}

	@Test
	void changedRowIndicesOnlyListNonEqualRowsInOrder() {
		TextDiffResult result = diffService.diffContent("a\nb\nc\nd", "a\nX\nc\nY");

		for (int index : result.changedRowIndices()) {
			Assertions.assertNotEquals(DiffLine.Tag.EQUAL, result.lines().get(index).tag());
		}
		Assertions.assertEquals(2, result.changedRowIndices().size());
	}

	@Test
	void metadataOnlyDiffProducesNonEmptyMetadataResultWithNoTextChange() {
		ScriptMetadataHeader oldHeader = ScriptMetadataHeader.parse("-- @description: old\nselect 1;");
		ScriptMetadataHeader newHeader = ScriptMetadataHeader.parse("-- @description: new\nselect 1;");

		MetadataDiffResult metadataDiff = diffService.diffMetadata(oldHeader, newHeader);
		TextDiffResult textDiff = diffService.diffContent("select 1;", "select 1;");

		Assertions.assertTrue(metadataDiff.hasChanges());
		Assertions.assertEquals(1, metadataDiff.fields().size());
		Assertions.assertEquals("Description", metadataDiff.fields().get(0).fieldName());
		Assertions.assertEquals("old", metadataDiff.fields().get(0).oldValue());
		Assertions.assertEquals("new", metadataDiff.fields().get(0).newValue());
		Assertions.assertFalse(textDiff.hasChanges(), "an unrelated body must show no text changes when only metadata differs");
	}

	@Test
	void unchangedMetadataProducesNoFields() {
		ScriptMetadataHeader header = ScriptMetadataHeader.parse("-- @description: same\nselect 1;");

		MetadataDiffResult result = diffService.diffMetadata(header, header);

		Assertions.assertFalse(result.hasChanges());
		Assertions.assertTrue(result.fields().isEmpty());
	}

	@Test
	void pathChangeIsDetected() {
		PathDiffResult unchanged = diffService.diffPath("scripts/a.bsql", "scripts/a.bsql");
		PathDiffResult changed = diffService.diffPath("scripts/customer-cleanup.bsql", "scripts/customer-decommission.bsql");

		Assertions.assertFalse(unchanged.changed());
		Assertions.assertTrue(changed.changed());
		Assertions.assertEquals("scripts/customer-cleanup.bsql", changed.oldPath());
		Assertions.assertEquals("scripts/customer-decommission.bsql", changed.newPath());
	}

	@Test
	void emptyOldContentIsAllAdditions() {
		TextDiffResult result = diffService.diffContent("", "select 1;\nselect 2;");

		Assertions.assertEquals(2, result.additions());
		Assertions.assertEquals(0, result.deletions());
	}

	@Test
	void repeatedCallsProduceIdenticalResultsNeverMutatingSharedState() {
		TextDiffResult first = diffService.diffContent("select 1;", "select 2;");
		TextDiffResult second = diffService.diffContent("select 1;", "select 2;");

		Assertions.assertEquals(first.modifications(), second.modifications());
		Assertions.assertEquals(List.of(0), second.changedRowIndices());
	}
}
