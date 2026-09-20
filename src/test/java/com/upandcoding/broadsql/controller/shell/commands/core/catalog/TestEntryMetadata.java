package com.upandcoding.broadsql.controller.shell.commands.core.catalog;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestEntryMetadata {

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
	void headerParsingStopsAtFirstNonMetadataLine() {
		String content = "-- @description: valid\n"
				+ "select 1;\n"
				+ "-- @tags: shouldNotBeParsed\n";

		EntryMetadata metadata = EntryMetadata.parse(content);

		Assertions.assertEquals("valid", metadata.getDescription());
		Assertions.assertTrue(metadata.getTags().isEmpty(), "a metadata-shaped line after the header block must not be parsed");
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
