package com.upandcoding.broadsql.controller.shell.commands.core.catalog;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestEntryMetadata {

	@Test
	void extractHeaderLinesReturnsOnlyTheContiguousLeadingAtTaggedLines() {
		String content = "-- @description: d\n-- @status: draft\nselect 1;\n-- @not: part of the header, this is body text";

		java.util.List<String> header = EntryMetadata.extractHeaderLines(content);

		Assertions.assertEquals(java.util.List.of("-- @description: d", "-- @status: draft"), header);
	}

	@Test
	void extractHeaderLinesIsEmptyForAnUntaggedFile() {
		Assertions.assertTrue(EntryMetadata.extractHeaderLines("select 1;").isEmpty());
		Assertions.assertTrue(EntryMetadata.extractHeaderLines(null).isEmpty());
	}

	@Test
	void untaggedFileHasNoneEnvironmentAndAppliesEverywhere() {
		EntryMetadata metadata = EntryMetadata.parse("select * from country;");

		Assertions.assertEquals("NONE", metadata.environmentDisplayValue());
		Assertions.assertTrue(metadata.appliesToEnvironment("PROD"));
		Assertions.assertTrue(metadata.appliesToEnvironment(""));
		Assertions.assertNull(metadata.getDescription());
		Assertions.assertTrue(metadata.getTags().isEmpty());
		Assertions.assertTrue(metadata.getAliases().isEmpty());
	}

	@Test
	void untaggedFileHasNoneInstanceAndAppliesEverywhere() {
		EntryMetadata metadata = EntryMetadata.parse("select * from country;");

		Assertions.assertEquals("NONE", metadata.instanceDisplayValue());
		Assertions.assertTrue(metadata.appliesToInstance("Wiki1"));
		Assertions.assertTrue(metadata.appliesToInstance(""));
	}

	@Test
	void parsesFullHeaderBlock() {
		String content = "-- @description: Monthly revenue by country\n"
				+ "-- @instance: MYSAP\n"
				+ "-- @environment: PROD\n"
				+ "-- @tags: finance, monthly, revenue\n"
				+ "-- @alias: rev\n"
				+ "-- @status: stable\n"
				+ "select country, sum(amount) from revenue where year = %1 group by country;";

		EntryMetadata metadata = EntryMetadata.parse(content);

		Assertions.assertEquals("Monthly revenue by country", metadata.getDescription());
		Assertions.assertEquals("MYSAP", metadata.instanceDisplayValue());
		Assertions.assertEquals("PROD", metadata.environmentDisplayValue());
		Assertions.assertEquals(java.util.List.of("finance", "monthly", "revenue"), metadata.getTags());
		Assertions.assertEquals(java.util.List.of("rev"), metadata.getAliases());
		Assertions.assertEquals("stable", metadata.getStatus());
		Assertions.assertTrue(metadata.appliesToInstance("MYSAP"));
		Assertions.assertTrue(metadata.appliesToInstance("mysap"));
		Assertions.assertFalse(metadata.appliesToInstance("JIRA"));
		Assertions.assertFalse(metadata.appliesToInstance(""));
		Assertions.assertTrue(metadata.appliesToEnvironment("PROD"));
		Assertions.assertTrue(metadata.appliesToEnvironment("prod"));
		Assertions.assertFalse(metadata.appliesToEnvironment("TEST"));
		Assertions.assertFalse(metadata.appliesToEnvironment(""));
	}

	@Test
	void environmentAllAppliesToEverythingIncludingBlank() {
		EntryMetadata metadata = EntryMetadata.parse("-- @environment: ALL\nselect 1;");

		Assertions.assertEquals("ALL", metadata.environmentDisplayValue());
		Assertions.assertTrue(metadata.appliesToEnvironment("PROD"));
		Assertions.assertTrue(metadata.appliesToEnvironment(""));
	}

	@Test
	void instanceAllAppliesToEverythingIncludingBlank() {
		EntryMetadata metadata = EntryMetadata.parse("-- @instance: ALL\nselect 1;");

		Assertions.assertEquals("ALL", metadata.instanceDisplayValue());
		Assertions.assertTrue(metadata.appliesToInstance("Wiki1"));
		Assertions.assertTrue(metadata.appliesToInstance(""));
	}

	@Test
	void repeatedEnvironmentLinesAccumulate() {
		EntryMetadata metadata = EntryMetadata.parse("-- @environment: PROD\n-- @environment: PREPROD\nselect 1;");

		Assertions.assertTrue(metadata.appliesToEnvironment("PROD"));
		Assertions.assertTrue(metadata.appliesToEnvironment("PREPROD"));
		Assertions.assertFalse(metadata.appliesToEnvironment("TEST"));
	}

	@Test
	void repeatedInstanceLinesAccumulate() {
		EntryMetadata metadata = EntryMetadata.parse("-- @instance: Wiki1\n-- @instance: Wiki2\nselect 1;");

		Assertions.assertTrue(metadata.appliesToInstance("Wiki1"));
		Assertions.assertTrue(metadata.appliesToInstance("Wiki2"));
		Assertions.assertFalse(metadata.appliesToInstance("JIRA"));
	}

	@Test
	void commaSeparatedEnvironmentValuesOnOneLineAccumulateJustLikeRepeatedLines() {
		EntryMetadata metadata = EntryMetadata.parse("-- @environment: PROD, PREPROD\nselect 1;");

		Assertions.assertEquals("PROD,PREPROD", metadata.environmentDisplayValue());
		Assertions.assertTrue(metadata.appliesToEnvironment("PROD"));
		Assertions.assertTrue(metadata.appliesToEnvironment("PREPROD"));
		Assertions.assertFalse(metadata.appliesToEnvironment("TEST"));
	}

	@Test
	void commaSeparatedInstanceValuesOnOneLineAccumulateJustLikeRepeatedLines() {
		EntryMetadata metadata = EntryMetadata.parse("-- @instance: Wiki1,Wiki2\nselect 1;");

		Assertions.assertEquals("Wiki1,Wiki2", metadata.instanceDisplayValue());
		Assertions.assertTrue(metadata.appliesToInstance("Wiki1"));
		Assertions.assertTrue(metadata.appliesToInstance("Wiki2"));
		Assertions.assertFalse(metadata.appliesToInstance("JIRA"));
	}

	@Test
	void commaSeparatedAllIsRecognizedLikeALoneAllValue() {
		EntryMetadata metadata = EntryMetadata.parse("-- @environment: ALL\n-- @instance: MYSAP, ALL\nselect 1;");

		Assertions.assertEquals("ALL", metadata.environmentDisplayValue());
		Assertions.assertEquals("ALL", metadata.instanceDisplayValue());
		Assertions.assertTrue(metadata.appliesToInstance("anything"));
	}

	@Test
	void instanceAndEnvironmentAreIndependentDimensions() {
		EntryMetadata metadata = EntryMetadata.parse("-- @instance: MYSAP\nselect 1;");

		// Only @instance is declared - @environment stays untagged (NONE), matching everywhere,
		// regardless of the @instance restriction.
		Assertions.assertFalse(metadata.appliesToInstance("JIRA"));
		Assertions.assertTrue(metadata.appliesToInstance("MYSAP"));
		Assertions.assertTrue(metadata.appliesToEnvironment("PROD"));
		Assertions.assertTrue(metadata.appliesToEnvironment(""));
	}

	@Test
	void parseFindsDirectivesAnywhereInTheDocumentNotOnlyTheLeadingHeaderBlock() {
		// SPRINT 0917-01 corrective acceptance pass, requirement 1.2: parse() (used by both the Script
		// Library metadata panel and LIB LIST) recognizes a directive comment wherever it occurs in a
		// valid SQL comment region - extractHeaderLines()/stripHeader() still stop at the first real
		// content line (the header/body split used for round-trip patching), but parse() itself does not.
		String content = "-- @description: valid\n"
				+ "select 1;\n"
				+ "-- @tags: shouldStillBeParsed\n";

		EntryMetadata metadata = EntryMetadata.parse(content);

		Assertions.assertEquals("valid", metadata.getDescription());
		Assertions.assertEquals(java.util.List.of("shouldStillBeParsed"), metadata.getTags());
	}

	@Test
	void parseAcceptsSpaceSeparatedDirectivesNotOnlyColonSeparated() {
		// The exact acceptance fixture from SPRINT 0917-01's corrective pass: real BroadSQL files use
		// "@key value" (space), not "@key: value" (colon) - both must parse identically.
		String content = "-- @instance MYWORLD\n"
				+ "-- @environment PROD\n"
				+ "-- @description Lorem ipsum dolor sit amet, consectetur adipiscing elit.\n"
				+ "-- @alias QR1\n"
				+ "-- @status draft\n"
				+ "-- @tags ctag\n"
				+ "\n"
				+ "SELECT * FROM PUBLIC.CITY LIMIT 10;";

		EntryMetadata metadata = EntryMetadata.parse(content);

		Assertions.assertEquals("MYWORLD", metadata.instanceDisplayValue());
		Assertions.assertEquals("PROD", metadata.environmentDisplayValue());
		Assertions.assertEquals("Lorem ipsum dolor sit amet, consectetur adipiscing elit.", metadata.getDescription());
		Assertions.assertEquals(java.util.List.of("QR1"), metadata.getAliases());
		Assertions.assertEquals("draft", metadata.getStatus());
		Assertions.assertEquals(java.util.List.of("ctag"), metadata.getTags());
	}

	@Test
	void parseDoesNotStopAtAnOrdinaryCommentBeforeADirective() {
		String content = "-- ordinary explanatory comment\n"
				+ "-- @alias QR1\n"
				+ "SELECT 1;";

		EntryMetadata metadata = EntryMetadata.parse(content);

		Assertions.assertEquals(java.util.List.of("QR1"), metadata.getAliases());
	}

	@Test
	void parseFindsDirectivesInsideAPlainBlockComment() {
		String content = "/*\n Explanation\n @alias QR1\n*/\nSELECT 1;";

		EntryMetadata metadata = EntryMetadata.parse(content);

		Assertions.assertEquals(java.util.List.of("QR1"), metadata.getAliases());
	}

	@Test
	void parseFindsDirectivesInsideAJavadocStyleBlockComment() {
		String content = "/*\n * @instance MYWORLD\n * @environment PROD\n */\nSELECT 1;";

		EntryMetadata metadata = EntryMetadata.parse(content);

		Assertions.assertEquals("MYWORLD", metadata.instanceDisplayValue());
		Assertions.assertEquals("PROD", metadata.environmentDisplayValue());
	}

	@Test
	void fakeMetadataInsideAStringLiteralIsNeverParsedAsADirective() {
		EntryMetadata metadata = EntryMetadata.parse("SELECT '@instance WRONG';");

		Assertions.assertEquals("NONE", metadata.instanceDisplayValue());
	}

	@Test
	void duplicateScalarKeyKeepsTheFirstOccurrence() {
		// Established rule (unchanged by the corrective pass): first occurrence wins for @description/@status.
		EntryMetadata metadata = EntryMetadata.parse("-- @status draft\n-- @status: final\nselect 1;");

		Assertions.assertEquals("draft", metadata.getStatus());
	}

	@Test
	void stripHeaderRemovesOnlyTheLeadingMetadataBlock() {
		String content = "-- @description: d\n-- @instance: MYSAP\n-- @environment: PROD\nselect * from country;\nwhere id = 1;";

		String stripped = EntryMetadata.stripHeader(content);

		Assertions.assertFalse(stripped.contains("@description"));
		Assertions.assertFalse(stripped.contains("@instance"));
		Assertions.assertFalse(stripped.contains("@environment"));
		Assertions.assertTrue(stripped.contains("select * from country;"));
		Assertions.assertTrue(stripped.contains("where id = 1;"));
	}

	@Test
	void unknownMetadataKeyIsIgnoredNotRejected() {
		EntryMetadata metadata = EntryMetadata.parse("-- @future-key: whatever\n-- @description: still parsed\nselect 1;");

		Assertions.assertEquals("still parsed", metadata.getDescription());
	}
}
