package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;

class TestScriptMetadataHeader {

	/**
	 * The required test from the implementation plan's "Metadata round-trip preservation": parse an
	 * original header (including an unknown/future key), patch one known field, reconstruct, re-parse -
	 * the edited field changed and every other known field plus every unknown raw line is
	 * byte-identical to the original.
	 */
	@Test
	void editingOneKnownFieldPreservesEveryOtherLineByteIdentical() {
		String original = "-- @description: Cleanup inactive customer\n"
				+ "-- @environment: PROD\n"
				+ "-- @tags: cleanup,customer\n"
				+ "-- @future-key: some value the current model does not know about\n"
				+ "-- a plain comment line, not @-tagged, so it is NOT part of the header block\n"
				+ "SELECT 1 FROM CUSTOMER;";

		ScriptMetadataHeader parsed = ScriptMetadataHeader.parse(original);
		Assertions.assertEquals("Cleanup inactive customer", parsed.metadata().getDescription());
		// SPRINT 0917-01 corrective acceptance pass (requirement 3.6): the header/body boundary is the
		// first line of REAL content (here, "SELECT 1 FROM CUSTOMER;") - a plain, non-@-tagged comment
		// line preceding it is still part of the header block, so it round-trips untouched rather than
		// being silently dropped.
		Assertions.assertEquals(5, parsed.rawLines().size(), "the plain comment line is still header, not body - only real SQL content ends the header");

		ScriptMetadataHeader edited = parsed.withField("description", "Cleanup inactive customer records older than 90 days");
		String headerText = edited.toHeaderText();
		ScriptMetadataHeader reparsed = ScriptMetadataHeader.parse(headerText + "SELECT 1 FROM CUSTOMER;");

		Assertions.assertEquals("Cleanup inactive customer records older than 90 days", reparsed.metadata().getDescription());
		Assertions.assertEquals("PROD", reparsed.metadata().environmentDisplayValue());
		Assertions.assertEquals("cleanup,customer", reparsed.metadata().getTagsDisplayValue());
		Assertions.assertTrue(reparsed.rawLines().stream().anyMatch(line -> line.contains("@future-key: some value the current model does not know about")),
				"an unknown/future metadata key must survive the round-trip untouched");
		Assertions.assertTrue(reparsed.rawLines().stream().anyMatch(line -> line.equals("-- @environment: PROD")),
				"an untouched known field's raw line must remain byte-identical");
	}

	@Test
	void appendsANewLineWhenTheFieldWasNotPresentBefore() {
		ScriptMetadataHeader header = ScriptMetadataHeader.parse("-- @description: d\nSELECT 1;");
		ScriptMetadataHeader withStatus = header.withField("status", "draft");

		Assertions.assertEquals("draft", withStatus.metadata().getStatus());
		Assertions.assertEquals("d", withStatus.metadata().getDescription(), "the pre-existing field must be untouched");
	}

	@Test
	void aBrandNewAssetStartsFromASeedTemplate() {
		ScriptMetadataHeader seeded = ScriptMetadataHeader.fromSeed(java.util.List.of("-- @description:", "-- @status: draft"));

		Assertions.assertEquals(2, seeded.rawLines().size());
		Assertions.assertEquals("draft", seeded.metadata().getStatus());
	}

	@Test
	void withoutFieldRemovesTheLineEntirelyRatherThanWritingAPlaceholderValue() {
		ScriptMetadataHeader header = ScriptMetadataHeader.parse("-- @description: d\n-- @instance: MYSAP\nselect 1;");

		ScriptMetadataHeader cleared = header.withoutField("instance");

		Assertions.assertEquals(1, cleared.rawLines().size());
		Assertions.assertEquals("NONE", cleared.metadata().instanceDisplayValue(), "clearing @instance must round-trip back to the NONE sentinel, not a literal instance named NONE");
		Assertions.assertEquals("d", cleared.metadata().getDescription());
	}

	@Test
	void toHeaderTextRoundTripsAnUntaggedFileWithNoHeaderAtAll() {
		ScriptMetadataHeader header = ScriptMetadataHeader.parse("SELECT 1 FROM DUAL;");

		Assertions.assertEquals("", header.toHeaderText());
		Assertions.assertTrue(header.rawLines().isEmpty());
	}

	@Test
	void editingAFieldPreservesUnrelatedExplanatoryComments() {
		// SPRINT 0917-01 corrective acceptance pass, requirement 3.6, exact spec example.
		String original = "-- Customer report used by Operations.\n"
				+ "-- Do not remove the following filter.\n"
				+ "-- @environment PROD\n"
				+ "-- @alias CITY\n"
				+ "SELECT * FROM CUSTOMER;";

		ScriptMetadataHeader edited = ScriptMetadataHeader.parse(original).withField("alias", "CITY_V2");
		String result = edited.toHeaderText() + EntryMetadata.stripHeader(original);

		Assertions.assertTrue(result.contains("-- Customer report used by Operations."));
		Assertions.assertTrue(result.contains("-- Do not remove the following filter."));
		Assertions.assertTrue(result.contains("-- @environment PROD"), "the untouched field's original space-separated syntax must survive as written");
		Assertions.assertEquals(java.util.List.of("CITY_V2"), edited.metadata().getAliases());
	}

	@Test
	void editingASpaceSeparatedDirectivePatchesInPlaceWithoutDuplicating() {
		// Before the corrective fix, ScriptMetadataHeader.keyOf() required a colon, so an existing
		// "-- @environment PROD" (space-separated) line was never recognized as declaring "environment" -
		// withField would leave it behind AND append a second, colon-separated line for the same key.
		ScriptMetadataHeader header = ScriptMetadataHeader.parse("-- @environment PROD\nSELECT 1;");

		ScriptMetadataHeader updated = header.withField("environment", "QA");

		Assertions.assertEquals(1, updated.rawLines().size(), "the original space-separated line must be replaced in place, not duplicated");
		Assertions.assertEquals("QA", updated.metadata().environmentDisplayValue());
	}
}
