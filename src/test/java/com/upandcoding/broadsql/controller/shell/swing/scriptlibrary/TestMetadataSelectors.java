package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.MetadataIntegrityContext;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.MetadataIntegrityException;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptAsset;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryService;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;

/**
 * The Metadata tab's Database Group and Environment selectors, through the panel (headless: the list's rows, the
 * key bindings and the chips are exercised directly; the popup's on-screen display is a manual check). The CDF
 * is {@link TestMetadataChoices#vault()}: MYWORLD has DEV and PROD, SALES has PROD, HR has no connection.
 */
class TestMetadataSelectors {

	private final DatabaseDefinitionsVault vault = TestMetadataChoices.vault();
	private final List<MetadataPanel.Change> committed = new ArrayList<>();

	private MetadataPanel panel(String content) {
		MetadataPanel panel = new MetadataPanel();
		panel.setChoicesSupplier(() -> MetadataChoices.fromVault(vault));
		panel.setCommitListener(committed::add);
		panel.load(ScriptMetadataHeader.parse(content));
		return panel;
	}

	private static SearchableMultiSelect groups(MetadataPanel panel) {
		return (SearchableMultiSelect) panel.field("instance");
	}

	private static SearchableMultiSelect environments(MetadataPanel panel) {
		return (SearchableMultiSelect) panel.field("environment");
	}

	private static List<String> rowTexts(SearchableMultiSelect select) {
		return select.offeredRows().stream().map(r -> (r.kind() == MultiSelectModel.Kind.HEADING ? "# " : "") + r.text() + (r.selected() ? " [x]" : "")).toList();
	}

	private static int rowIndex(SearchableMultiSelect select, String text) {
		List<MultiSelectModel.Row> rows = select.offeredRows();
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).selectable() && rows.get(i).text().equals(text)) {
				return i;
			}
		}
		throw new AssertionError(text + " not offered: " + rows);
	}

	/** The text after applying every commit, in order, to {@code original}. */
	private String applied(String original) {
		String text = original;
		for (MetadataPanel.Change change : committed) {
			text = change.applyTo(text);
		}
		return text;
	}

	// ---------------- loading ----------------

	@Test
	void anExistingScriptWithOneValueShowsItAsAChip() {
		MetadataPanel panel = panel("-- @instance MYWORLD\n-- @environment: PROD\nselect 1;");

		Assertions.assertEquals(List.of("MYWORLD"), groups(panel).chipTexts());
		Assertions.assertEquals(List.of("PROD"), environments(panel).chipTexts());
		Assertions.assertTrue(panel.pendingChange().isEmpty(), "loading never rewrites the header");
	}

	@Test
	void anExistingScriptWithSeveralValuesShowsEveryOneInEitherSyntax() {
		MetadataPanel panel = panel("-- @instance: MYWORLD,SALES\n-- @environment: DEV\n-- @environment: PROD\nselect 1;");

		Assertions.assertEquals(List.of("MYWORLD", "SALES"), groups(panel).chipTexts());
		Assertions.assertEquals(List.of("DEV", "PROD"), environments(panel).chipTexts(), "repeated lines and a comma list are the same thing");
		Assertions.assertTrue(panel.pendingChange().isEmpty());
		groups(panel).openList();
		Assertions.assertTrue(rowTexts(groups(panel)).containsAll(List.of("MYWORLD [x]", "SALES [x]", "HR")), rowTexts(groups(panel)).toString());
	}

	@Test
	void aScriptWithoutTheseFieldsIsUnchanged() {
		String original = "-- @description: no scope\nselect 1;";
		MetadataPanel panel = panel(original);

		Assertions.assertEquals("NONE", panel.fieldText("instance"));
		Assertions.assertEquals("NONE", panel.fieldText("environment"));
		groups(panel).openList();
		groups(panel).closeList();
		panel.commitPendingEdits();
		Assertions.assertEquals(original, applied(original), "opening and closing a list without choosing writes nothing");
	}

	@Test
	void theAllKeywordLoadsAsOneChip() {
		MetadataPanel panel = panel("-- @instance: ALL\nselect 1;");

		Assertions.assertEquals(List.of("ALL"), groups(panel).chipTexts());
		groups(panel).openList();
		Assertions.assertEquals("ALL [x]", rowTexts(groups(panel)).get(0));
	}

	// ---------------- selecting and unselecting ----------------

	@Test
	void databaseGroupsAreSelectedAndUnselectedWithTheKeyboard() {
		String original = "select 1;";
		MetadataPanel panel = panel(original);
		SearchableMultiSelect select = groups(panel);

		select.typeFilter("world");
		Assertions.assertTrue(select.isListOpen(), "typing opens the list");
		Assertions.assertEquals(List.of("MYWORLD"), rowTexts(select));
		select.pressKey("ENTER");
		Assertions.assertEquals(List.of("MYWORLD"), select.chipTexts());
		Assertions.assertTrue(committed.isEmpty(), "nothing is written while the list is open");

		select.typeFilter("");
		Assertions.assertEquals("MYWORLD", select.offeredRows().get(select.highlightedIndex()).text(), "the highlight stays on the value just chosen");
		select.pressKey("UP");
		Assertions.assertEquals("HR", select.offeredRows().get(select.highlightedIndex()).text());
		select.pressKey("ENTER");
		Assertions.assertTrue(select.pressKey("ESCAPE"), "Esc closes the open list");
		Assertions.assertFalse(select.isListOpen());

		Assertions.assertEquals(1, committed.size(), "one commit for the whole round of choices");
		Assertions.assertEquals("MYWORLD,HR", ScriptMetadataHeader.parse(applied(original)).metadata().instanceDisplayValue());

		select.pressKey("BACK_SPACE");
		Assertions.assertEquals(List.of("MYWORLD"), select.chipTexts(), "Backspace in an empty filter removes the last chip");
		Assertions.assertEquals("MYWORLD", ScriptMetadataHeader.parse(applied(original)).metadata().instanceDisplayValue());

		select.pressKey("DOWN");
		Assertions.assertTrue(select.isListOpen(), "Down opens the list");
		select.typeFilter("myw");
		Assertions.assertEquals("MYWORLD [x]", rowTexts(select).get(0));
		select.pressKey("ENTER");
		select.pressKey("ESCAPE");
		Assertions.assertEquals("NONE", ScriptMetadataHeader.parse(applied(original)).metadata().instanceDisplayValue());
		Assertions.assertFalse(applied(original).contains("@instance"), "an empty selection removes the directive:\n" + applied(original));
	}

	@Test
	void escWithTheListClosedIsLeftToTheWindow() {
		SearchableMultiSelect select = groups(panel("select 1;"));

		Assertions.assertFalse(select.pressKey("ESCAPE"));
	}

	@Test
	void environmentsAreSelectedAndUnselectedWithTheMouse() {
		String original = "-- @environment: DEV\nselect 1;";
		MetadataPanel panel = panel(original);
		SearchableMultiSelect select = environments(panel);

		select.openList();
		select.clickRow(rowIndex(select, "PROD"));
		select.clickRow(rowIndex(select, "QA"));
		select.clickRow(rowIndex(select, "DEV"));
		select.closeList();

		Assertions.assertEquals(List.of("PROD", "QA"), select.chipTexts());
		Assertions.assertEquals("PROD,QA", ScriptMetadataHeader.parse(applied(original)).metadata().environmentDisplayValue());

		select.clickRemove("QA");
		Assertions.assertEquals("PROD", ScriptMetadataHeader.parse(applied(original)).metadata().environmentDisplayValue(), "removing a chip commits at once");
	}

	@Test
	void choosingAllClearsTheSpecificValues() {
		String original = "-- @instance: MYWORLD,SALES\nselect 1;";
		MetadataPanel panel = panel(original);
		SearchableMultiSelect select = groups(panel);

		select.openList();
		select.clickRow(rowIndex(select, "ALL"));
		select.closeList();

		Assertions.assertEquals(List.of("ALL"), select.chipTexts());
		Assertions.assertEquals("ALL", ScriptMetadataHeader.parse(applied(original)).metadata().instanceDisplayValue());
	}

	@Test
	void aMisspelledValueCannotBeEntered() {
		MetadataPanel panel = panel("select 1;");
		SearchableMultiSelect select = groups(panel);

		select.typeFilter("MYWROLD");
		Assertions.assertTrue(select.offeredRows().isEmpty(), "nothing matches, nothing can be added");
		select.pressKey("ENTER");
		select.pressKey("ESCAPE");
		panel.commitPendingEdits();

		Assertions.assertEquals("NONE", panel.fieldText("instance"));
		Assertions.assertTrue(committed.isEmpty());
	}

	@Test
	void theFilterNarrowsTheListAndClearingItShowsEveryValue() {
		SearchableMultiSelect select = environments(panel("select 1;"));

		select.typeFilter("d");
		Assertions.assertEquals(List.of("DEV", "PROD"), rowTexts(select));
		Assertions.assertEquals("DEV", select.offeredRows().get(select.highlightedIndex()).text(), "the first match is highlighted, ready for Enter");

		select.typeFilter("");
		Assertions.assertEquals(List.of("ALL", "DEV", "PROD", "QA"), rowTexts(select), "the complete list of valid values");
	}

	// ---------------- contextual assistance ----------------

	@Test
	void selectedGroupsPutTheirEnvironmentsFirstWithoutHidingTheOthers() {
		MetadataPanel panel = panel("-- @instance: SALES\nselect 1;");
		SearchableMultiSelect select = environments(panel);

		select.openList();

		Assertions.assertEquals(List.of("ALL", "# Used by the selected Database Groups", "PROD", "# Other Environments", "DEV", "QA"), rowTexts(select));
		select.clickRow(rowIndex(select, "QA"));
		Assertions.assertEquals(List.of("QA"), select.chipTexts(), "an incompatible Environment is still selectable");
	}

	@Test
	void selectedEnvironmentsPutTheirGroupsFirstWithoutHidingTheOthers() {
		MetadataPanel panel = panel("-- @environment: DEV\nselect 1;");
		SearchableMultiSelect select = groups(panel);

		select.openList();

		Assertions.assertEquals(List.of("ALL", "# With a connection in the selected Environments", "MYWORLD", "# Other Database Groups", "HR", "SALES"), rowTexts(select));
	}

	/** No circularity: whichever selector is filled first, every valid combination remains reachable from the other. */
	@Test
	void contextNeverMakesAValidValueUnreachable() {
		MetadataPanel panel = panel("select 1;");
		SearchableMultiSelect environments = environments(panel);
		SearchableMultiSelect groups = groups(panel);

		environments.openList();
		environments.clickRow(rowIndex(environments, "QA"));
		environments.closeList();
		groups.openList();
		Assertions.assertEquals(List.of("ALL", "HR", "MYWORLD", "SALES"), rowTexts(groups), "nothing is compatible with QA: a plain, complete list");
		groups.clickRow(rowIndex(groups, "SALES"));
		groups.closeList();
		environments.openList();
		Assertions.assertTrue(rowTexts(environments).containsAll(List.of("PROD", "DEV", "QA [x]")), rowTexts(environments).toString());
	}

	@Test
	void groupsCreatedAfterTheTabWasLoadedAreOfferedNextTime() {
		MetadataPanel panel = panel("select 1;");
		vault.getGroups().add("NEWGROUP");

		groups(panel).openList();

		Assertions.assertTrue(rowTexts(groups(panel)).contains("NEWGROUP"));
	}

	// ---------------- Save, reload and the Save-time safety net ----------------

	private static ConsoleSettings settings(Path root, Path history) {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setScriptsLibraryPath(root.toString());
		settings.setScriptHistoryVaultRaw(history.toString());
		return settings;
	}

	@Test
	void selectedValuesSurviveSaveAndReload(@TempDir Path root, @TempDir Path history) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settings(root, history));
		String original = "-- @description: report\nselect 1;";
		Files.writeString(root.resolve("R.sql"), original);
		ScriptAsset asset = service.open("R.sql");
		MetadataPanel panel = panel(asset.content());

		groups(panel).openList();
		groups(panel).clickRow(rowIndex(groups(panel), "MYWORLD"));
		groups(panel).closeList();
		environments(panel).openList();
		environments(panel).clickRow(rowIndex(environments(panel), "PROD"));
		environments(panel).clickRow(rowIndex(environments(panel), "DEV"));
		environments(panel).closeList();
		service.save(asset.assetId(), asset.relativePath(), applied(asset.content()), null, MetadataIntegrityContext.fromVault(vault));

		String saved = Files.readString(root.resolve("R.sql"));
		Assertions.assertTrue(saved.contains("@instance: MYWORLD"), saved);
		Assertions.assertTrue(saved.contains("@environment: PROD,DEV"), "the same comma-joined format as before:\n" + saved);
		committed.clear();
		MetadataPanel reopened = panel(service.open("R.sql").content());
		Assertions.assertEquals(List.of("MYWORLD"), groups(reopened).chipTexts());
		Assertions.assertEquals(List.of("PROD", "DEV"), environments(reopened).chipTexts());
		Assertions.assertTrue(reopened.pendingChange().isEmpty());
	}

	/** The selectors do not replace Save's check: an unknown id typed in the text itself is still refused. */
	@Test
	void saveTimeValidationStillRefusesAnUnknownId(@TempDir Path root, @TempDir Path history) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settings(root, history));
		Files.writeString(root.resolve("R.sql"), "select 1;");
		ScriptAsset asset = service.open("R.sql");
		String typedInTheText = "-- @instance: MYWROLD\nselect 1;";

		MetadataIntegrityException ex = Assertions.assertThrows(MetadataIntegrityException.class,
				() -> service.save(asset.assetId(), asset.relativePath(), typedInTheText, null, MetadataIntegrityContext.fromVault(vault)));
		Assertions.assertEquals("Unknown Database Group 'MYWROLD'.", ex.getMessage());

		MetadataPanel panel = panel(typedInTheText);
		Assertions.assertEquals(List.of("MYWROLD"), groups(panel).chipTexts(), "shown as written, neither dropped nor repaired");
		Assertions.assertTrue(panel.pendingChange().isEmpty());
	}

	@Test
	void theGroupEnvironmentPairRuleStillAppliesAtSave(@TempDir Path root, @TempDir Path history) throws Exception {
		ScriptLibraryService service = new ScriptLibraryService(settings(root, history));
		Files.writeString(root.resolve("R.sql"), "select 1;");
		ScriptAsset asset = service.open("R.sql");
		MetadataPanel panel = panel(asset.content());

		groups(panel).openList();
		groups(panel).clickRow(rowIndex(groups(panel), "SALES"));
		groups(panel).closeList();
		environments(panel).openList();
		environments(panel).clickRow(rowIndex(environments(panel), "DEV")); // offered, under "Other Environments"
		environments(panel).closeList();

		// The pair rule reads the CDF file, which this in-memory vault does not have: the same rule, over the same connections.
		MetadataChoices choices = MetadataChoices.fromVault(vault);
		MetadataIntegrityContext cdf = new MetadataIntegrityContext(vault.getGroups(), vault.getEnvironments(),
				(group, environment) -> choices.environmentsCompatibleWith(List.of(group)).contains(environment));
		MetadataIntegrityException ex = Assertions.assertThrows(MetadataIntegrityException.class,
				() -> service.save(asset.assetId(), asset.relativePath(), applied(asset.content()), null, cdf));
		Assertions.assertEquals("Database Group 'SALES' has no connection for environment 'DEV'.", ex.getMessage());
	}

	@Test
	void withoutACdfATypedValueCanStillBeAdded() {
		MetadataPanel panel = new MetadataPanel();
		panel.setCommitListener(committed::add);
		panel.load(ScriptMetadataHeader.parse("select 1;"));
		SearchableMultiSelect select = groups(panel);

		select.typeFilter("MYWORLD");
		Assertions.assertEquals(MultiSelectModel.Kind.ADD, select.offeredRows().get(select.highlightedIndex()).kind());
		select.pressKey("ENTER");
		select.pressKey("ESCAPE");

		Assertions.assertEquals("MYWORLD", ScriptMetadataHeader.parse(applied("select 1;")).metadata().instanceDisplayValue());
	}

	@Test
	void focusFieldTargetsTheSelectorsFilterField() {
		MetadataPanel panel = panel("select 1;");

		Assertions.assertDoesNotThrow(() -> {
			panel.focusField("instance");
			panel.focusField("environment");
		});
	}
}
