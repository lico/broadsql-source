package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;

/**
 * SPRINT 3009A correction pass: editing metadata from the Editor's Metadata tab must change the edited directive
 * and nothing else. Every test compares the whole text before and after: comments (line and block, before, between
 * and after the directives), blank lines, the directives' order and the SQL body must survive byte for byte.
 */
class TestMetadataDirectiveEditor {

	/** The exact Script from the manual test that lost its comments and blank lines. */
	private static final String MANUAL_CASE = """
			/*
			 Hello World
			*/
			-- @instance: MyWorld
			-- @status: stable
			-- toto
			/* my comments */
			-- @environment: QA,PROD,DEV
			-- @tags: toto,tutu,tata,titi,pepe


			select count(*)
			from COUNTRY
			where 1=1;
			""";

	private static List<String> lines(String text) {
		return Arrays.asList(text.split("\n", -1));
	}

	/** Asserts that {@code after} equals {@code before} except for exactly the lines listed (index -> new content). */
	private static void assertOnlyLinesChanged(String before, String after, Map<Integer, String> changed) {
		List<String> b = lines(before);
		List<String> a = lines(after);
		Assertions.assertEquals(b.size(), a.size(), "no line added or removed:\n" + after);
		for (int i = 0; i < b.size(); i++) {
			if (changed.containsKey(i)) {
				Assertions.assertEquals(changed.get(i), a.get(i), "line " + i);
			} else {
				Assertions.assertEquals(b.get(i), a.get(i), "line " + i + " must be untouched");
			}
		}
	}

	@Test
	void caseF_changingTagsChangesOnlyTheTagsLineOfTheManualTestScript() {
		String after = MetadataDirectiveEditor.setField(MANUAL_CASE, "tags", "toto,tutu,tata,titi,pepe,popo,pupu");

		assertOnlyLinesChanged(MANUAL_CASE, after, Map.of(8, "-- @tags: toto,tutu,tata,titi,pepe,popo,pupu"));
		Assertions.assertEquals(List.of("toto", "tutu", "tata", "titi", "pepe", "popo", "pupu"), EntryMetadata.parse(after).getTags());
	}

	@Test
	void caseA_commentsBeforeTheMetadataSurvive() {
		String text = "/* Hello */\n\n-- arbitrary note\n-- @status: draft\nselect 1;\n";
		String after = MetadataDirectiveEditor.setField(text, "status", "stable");
		assertOnlyLinesChanged(text, after, Map.of(3, "-- @status: stable"));
	}

	@Test
	void caseB_lineCommentsBetweenDirectivesSurvive() {
		String text = "-- @instance: A\n-- toto\n-- @environment: PROD\n-- another note\n-- @tags: x\nselect 1;\n";
		String after = MetadataDirectiveEditor.setField(text, "environment", "QA");
		assertOnlyLinesChanged(text, after, Map.of(2, "-- @environment: QA"));
	}

	@Test
	void caseC_blockCommentsBetweenDirectivesSurvive() {
		String text = "-- @instance: A\n/* another comment */\n/*\n multi\n line\n*/\n-- @tags: x\nselect 1;\n";
		String after = MetadataDirectiveEditor.setField(text, "instance", "B");
		assertOnlyLinesChanged(text, after, Map.of(0, "-- @instance: B"));
	}

	@Test
	void caseD_multipleBlankLinesBeforeTheSqlSurvive() {
		String text = "-- @description: old\n\n\n\nselect 1;\n\n\n";
		String after = MetadataDirectiveEditor.setField(text, "description", "new text");
		assertOnlyLinesChanged(text, after, Map.of(0, "-- @description: new text"));
	}

	@Test
	void caseE_aDifferentDirectiveOrderIsKept() {
		String text = "-- @tags: t\n-- @status: draft\n-- @environment: PROD\n-- @instance: A\n-- @description: d\nselect 1;\n";
		String after = MetadataDirectiveEditor.setField(text, "status", "deprecated");
		assertOnlyLinesChanged(text, after, Map.of(1, "-- @status: deprecated"));
	}

	@Test
	void theSeparatorStyleAndSpacingOfTheEditedLineAreKept() {
		Assertions.assertEquals("--   @status   stable\nselect 1;", MetadataDirectiveEditor.setField("--   @status   draft\nselect 1;", "status", "stable"));
		Assertions.assertEquals("-- @status:stable\n", MetadataDirectiveEditor.setField("-- @status:draft\n", "status", "stable"));
		Assertions.assertEquals("-- @description: x\n", MetadataDirectiveEditor.setField("-- @description:\n", "description", "x"));
		Assertions.assertEquals("-- @description: x\n", MetadataDirectiveEditor.setField("-- @description\n", "description", "x"));
	}

	@Test
	void directivesInsideABlockCommentAreEditedInPlace() {
		String text = "/*\n@instance MYWORLD\n@environment PROD\n@description Lorem ipsum.\n@status draft\n@tags atag\n*/\nSELECT 1;\n";
		String after = MetadataDirectiveEditor.setField(text, "tags", "atag,btag");
		assertOnlyLinesChanged(text, after, Map.of(5, "@tags atag,btag"));
	}

	@Test
	void aMissingDirectiveIsInsertedAfterTheLastHeaderDirectiveInItsStyle() {
		String after = MetadataDirectiveEditor.setField(MANUAL_CASE, "description", "Counts countries");
		List<String> expected = new ArrayList<>(lines(MANUAL_CASE));
		expected.add(9, "-- @description: Counts countries");
		Assertions.assertEquals(expected, lines(after), "one line inserted after the last directive, nothing else touched");

		String block = "/*\n@instance A\n*/\nselect 1;\n";
		Assertions.assertEquals("/*\n@instance A\n@status stable\n*/\nselect 1;\n", MetadataDirectiveEditor.setField(block, "status", "stable"));
	}

	@Test
	void withNoDirectiveAtAllTheNewLineGoesFirstAndEverythingElseFollowsUnchanged() {
		String text = "/*\n Hello World\n*/\n\nselect 1;\n";
		Assertions.assertEquals("-- @status: draft\n" + text, MetadataDirectiveEditor.setField(text, "status", "draft"));
	}

	@Test
	void clearingRemovesOnlyTheDirectiveLine() {
		String after = MetadataDirectiveEditor.setField(MANUAL_CASE, "status", null);
		List<String> expected = new ArrayList<>(lines(MANUAL_CASE));
		expected.remove(4);
		Assertions.assertEquals(expected, lines(after), "the neighbouring comments and blank lines stay");
	}

	@Test
	void clearingADirectiveSharingItsLineRemovesOnlyTheDirective() {
		Assertions.assertEquals("/*  */\n-- @description: x\nselect 1;", MetadataDirectiveEditor.setField("/* @environment PROD */\n-- @description: x\nselect 1;", "environment", null));
	}

	@Test
	void anAccumulatingKeySetTwiceBecomesOneValueAndAFirstWinsKeyLeavesLaterLinesAlone() {
		String text = "-- @instance: A\n-- note\n-- @instance: B\n-- @status: draft\n-- @status: stable\nselect 1;\n";
		String instance = MetadataDirectiveEditor.setField(text, "instance", "C");
		Assertions.assertEquals("-- @instance: C\n-- note\n-- @status: draft\n-- @status: stable\nselect 1;\n", instance);
		Assertions.assertEquals(List.of("C"), EntryMetadata.parse(instance).getInstances());

		String status = MetadataDirectiveEditor.setField(text, "status", "deprecated");
		assertOnlyLinesChanged(text, status, Map.of(3, "-- @status: deprecated"));
	}

	@Test
	void windowsLineBreaksAreKept() {
		String text = "-- @status: draft\r\n-- toto\r\n\r\nselect 1;\r\n";
		Assertions.assertEquals("-- @status: stable\r\n-- toto\r\n\r\nselect 1;\r\n", MetadataDirectiveEditor.setField(text, "status", "stable"));
		Assertions.assertEquals("-- @status: draft\r\n-- @tags: a\r\n-- toto\r\n\r\nselect 1;\r\n", MetadataDirectiveEditor.setField(text, "tags", "a"));
	}

	@Test
	void anAtSignInsideSqlIsNeverTakenForADirective() {
		String text = "-- @status: draft\nselect '@status WRONG' from dual;\n";
		Assertions.assertEquals("-- @status: stable\nselect '@status WRONG' from dual;\n", MetadataDirectiveEditor.setField(text, "status", "stable"));
	}

	@Test
	void severalChangesApplyInOrder() {
		Map<String, String> changes = new LinkedHashMap<>();
		changes.put("tags", "a,b");
		changes.put("status", null);
		String after = MetadataDirectiveEditor.apply(MANUAL_CASE, changes);
		List<String> expected = new ArrayList<>(lines(MANUAL_CASE));
		expected.set(8, "-- @tags: a,b");
		expected.remove(4);
		Assertions.assertEquals(expected, lines(after));
	}
}
