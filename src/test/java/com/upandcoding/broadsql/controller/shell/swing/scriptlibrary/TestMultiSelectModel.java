package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.swing.scriptlibrary.MultiSelectModel.Kind;
import com.upandcoding.broadsql.controller.shell.swing.scriptlibrary.MultiSelectModel.Row;

/** The selection and filtering model of the Metadata tab's Database Group and Environment selectors, without Swing. */
class TestMultiSelectModel {

	private static MultiSelectModel groups(String... options) {
		MultiSelectModel model = new MultiSelectModel("ALL", "Database Groups");
		model.setOptions(List.of(options));
		return model;
	}

	private static List<String> texts(List<Row> rows) {
		return rows.stream().map(r -> (r.kind() == Kind.HEADING ? "# " : "") + r.text() + (r.selected() ? " [x]" : "")).toList();
	}

	@Test
	void loadingOneValueSelectsItAndShowsItAsTheOnlyChip() {
		MultiSelectModel model = groups("HR", "MYWORLD", "SALES");

		model.load(List.of("MYWORLD"), false);

		Assertions.assertEquals(List.of("MYWORLD"), model.chips());
		Assertions.assertEquals("MYWORLD", model.displayValue());
		Assertions.assertEquals(List.of("ALL", "HR", "MYWORLD [x]", "SALES"), texts(model.rows()));
	}

	@Test
	void loadingSeveralValuesKeepsTheirOrderAndTheirCase() {
		MultiSelectModel model = groups("HR", "MYWORLD", "SALES");

		model.load(List.of("sales", "MYWORLD"), false);

		Assertions.assertEquals(List.of("sales", "MYWORLD"), model.chips(), "a declared id is shown as written, never re-cased on load");
		Assertions.assertEquals("sales,MYWORLD", model.displayValue());
		Assertions.assertTrue(model.isSelected("SALES"));
	}

	@Test
	void nothingDeclaredIsNoneAndAllIsAll() {
		MultiSelectModel model = groups("HR");
		model.load(List.of(), false);
		Assertions.assertEquals("NONE", model.displayValue());
		Assertions.assertEquals(List.of(), model.chips());

		model.load(List.of("HR"), true);
		Assertions.assertEquals("ALL", model.displayValue(), "ALL wins, exactly as the grid has always displayed it");
		Assertions.assertEquals(List.of("ALL"), model.chips());
	}

	@Test
	void selectingAndUnselectingValues() {
		MultiSelectModel model = groups("HR", "MYWORLD", "SALES");
		model.load(List.of(), false);

		model.toggle("SALES");
		model.toggle("hr");
		Assertions.assertEquals("SALES,HR", model.displayValue(), "a known id is written with its own spelling");

		model.toggle("sales");
		Assertions.assertEquals("HR", model.displayValue());
		model.remove("HR");
		Assertions.assertEquals("NONE", model.displayValue());
	}

	@Test
	void allAndSpecificIdsAreExclusive() {
		MultiSelectModel model = groups("HR", "SALES");
		model.load(List.of("HR", "SALES"), false);

		model.toggle("ALL");
		Assertions.assertEquals("ALL", model.displayValue());
		Assertions.assertTrue(model.selected().isEmpty());

		model.toggle("HR");
		Assertions.assertEquals("HR", model.displayValue(), "choosing a specific id leaves ALL");
	}

	@Test
	void removeLastRemovesTheLastChip() {
		MultiSelectModel model = groups("A", "B");
		model.load(List.of("A", "B"), false);

		Assertions.assertTrue(model.removeLast());
		Assertions.assertEquals("A", model.displayValue());
		Assertions.assertTrue(model.removeLast());
		Assertions.assertFalse(model.removeLast(), "nothing left to remove");

		model.toggle("ALL");
		Assertions.assertTrue(model.removeLast());
		Assertions.assertEquals("NONE", model.displayValue());
	}

	@Test
	void typingFiltersByCaseInsensitiveSubstring() {
		MultiSelectModel model = groups("FINANCE", "HR", "MYWORLD", "WORLD_ARCHIVE");
		model.load(List.of(), false);

		model.setFilter("world");
		Assertions.assertEquals(List.of("MYWORLD", "WORLD_ARCHIVE"), texts(model.rows()));

		model.setFilter("al");
		Assertions.assertEquals(List.of("ALL"), texts(model.rows()), "the ALL keyword is filtered like any value");

		model.setFilter("nothing");
		Assertions.assertTrue(model.rows().isEmpty());

		model.setFilter("");
		Assertions.assertEquals(5, model.rows().size(), "clearing the filter shows the complete list again");
	}

	@Test
	void aDeclaredIdOutsideTheKnownListStaysSelectedAndVisible() {
		MultiSelectModel model = groups("HR");

		model.load(List.of("HR", "LEGACY"), false);

		Assertions.assertEquals("HR,LEGACY", model.displayValue(), "never dropped: Save reports it, the selector does not repair it");
		Assertions.assertEquals(List.of("ALL", "HR [x]", "LEGACY [x]"), texts(model.rows()));
	}

	@Test
	void withKnownOptionsATypedUnknownValueCannotBeAdded() {
		MultiSelectModel model = groups("HR");
		model.load(List.of(), false);

		model.setFilter("HRX");

		Assertions.assertTrue(model.rows().isEmpty(), "no 'Add' row: only known values can be chosen");
	}

	@Test
	void withoutKnownOptionsATypedValueCanBeAdded() {
		MultiSelectModel model = new MultiSelectModel("ALL", "Database Groups");
		model.setOptions(null);
		model.load(List.of(), false);

		model.setFilter("MYWORLD");
		List<Row> rows = model.rows();

		Assertions.assertEquals(Kind.ADD, rows.get(rows.size() - 1).kind());
		model.activate(rows.get(rows.size() - 1));
		Assertions.assertEquals("MYWORLD", model.displayValue());
	}

	@Test
	void contextPutsCompatibleValuesFirstAndKeepsEveryOtherValueReachable() {
		MultiSelectModel model = groups("FINANCE", "HR", "MYWORLD", "SALES");
		model.load(List.of(), false);

		model.setRelevant(Set.of("MYWORLD", "SALES"), "With a connection in the selected Environments");

		Assertions.assertEquals(List.of("ALL", "# With a connection in the selected Environments", "MYWORLD", "SALES", "# Other Database Groups", "FINANCE", "HR"),
				texts(model.rows()));
		model.toggle("FINANCE");
		Assertions.assertEquals("FINANCE", model.displayValue(), "an incompatible value is still selectable: context assists, it never enforces");
	}

	@Test
	void contextWithNothingCompatibleOrEverythingCompatibleIsAPlainList() {
		MultiSelectModel model = groups("HR", "SALES");
		model.load(List.of(), false);

		model.setRelevant(Set.of(), "heading");
		Assertions.assertEquals(List.of("ALL", "HR", "SALES"), texts(model.rows()));

		model.setRelevant(Set.of("hr", "sales"), "heading");
		Assertions.assertEquals(List.of("ALL", "HR", "SALES"), texts(model.rows()));
	}

	@Test
	void contextAndFilterCombine() {
		MultiSelectModel model = groups("HR", "HR_ARCHIVE", "SALES");
		model.load(List.of(), false);
		model.setRelevant(Set.of("HR_ARCHIVE", "SALES"), "Compatible");

		model.setFilter("hr");

		Assertions.assertEquals(List.of("# Compatible", "HR_ARCHIVE", "# Other Database Groups", "HR"), texts(model.rows()));
	}

	@Test
	void navigationSkipsHeadingsAndStopsAtTheEnds() {
		MultiSelectModel model = groups("A", "B");
		model.setRelevant(Set.of("B"), "Compatible");
		List<Row> rows = model.rows(); // ALL, # Compatible, B, # Other, A

		Assertions.assertEquals(0, MultiSelectModel.nextSelectable(rows, -1, 1));
		Assertions.assertEquals(2, MultiSelectModel.nextSelectable(rows, 0, 1));
		Assertions.assertEquals(4, MultiSelectModel.nextSelectable(rows, 2, 1));
		Assertions.assertEquals(4, MultiSelectModel.nextSelectable(rows, 4, 1), "stays on the last value");
		Assertions.assertEquals(2, MultiSelectModel.nextSelectable(rows, 4, -1));
		Assertions.assertEquals(0, MultiSelectModel.nextSelectable(rows, 0, -1), "stays on the first value");
		Assertions.assertEquals(-1, MultiSelectModel.nextSelectable(List.of(), -1, 1));
	}
}
