package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The selection and filtering model behind one {@link SearchableMultiSelect} (the Metadata tab's Database Group
 * and Environment selectors), kept free of Swing so it is tested on its own.
 *
 * <p>The value is what {@code @instance}/{@code @environment} already store: the {@code ALL} keyword, or a list
 * of specific ids, or nothing ({@code NONE}). {@code ALL} and specific ids are exclusive here: choosing one clears
 * the other, since {@code ALL} already covers every id. Ids are matched case-insensitively and kept as written,
 * so loading a Script never rewrites the case of what it declares; a declared id that is not among the known
 * options stays selected and visible (Save reports it, as before), it is never dropped.
 *
 * <p>The offered list is {@code ALL} then the known options. With a context ({@link #setRelevant}), the options
 * compatible with the other selector's choice come first, under a heading, and every other option follows under a
 * second heading: context orders the list, it never removes a value, so no earlier choice can make a valid value
 * unreachable. Typing filters by case-insensitive substring. When the options are unknown (no CDF), a typed value
 * can be added as is.
 */
public final class MultiSelectModel {

	/** One line of the offered list. */
	public enum Kind {
		/** The {@code ALL} keyword. */
		ALL,
		/** A section heading, not selectable. */
		HEADING,
		/** A selectable id. */
		VALUE,
		/** "Add the typed text", only when the options are unknown. */
		ADD
	}

	/** One line of the offered list: {@code text} is the id, the heading text, or the text to add. */
	public record Row(Kind kind, String text, boolean selected) {

		public boolean selectable() {
			return kind != Kind.HEADING;
		}
	}

	private final String allKeyword;
	private final String noun;
	private List<String> options;
	private Set<String> relevant;
	private String relevantHeading = "";
	private final List<String> selected = new ArrayList<>();
	private boolean all;
	private String filter = "";

	/**
	 * @param allKeyword the keyword selecting everything ({@code ALL})
	 * @param noun       the plural name of the values, for the second heading ({@code Database Groups})
	 */
	public MultiSelectModel(String allKeyword, String noun) {
		this.allKeyword = allKeyword;
		this.noun = noun;
	}

	/** The known ids, or {@code null} when unknown (free entry is then allowed). */
	public void setOptions(List<String> options) {
		this.options = options == null ? null : List.copyOf(options);
	}

	public boolean optionsKnown() {
		return options != null;
	}

	/**
	 * The options compatible with the other selector's current choice, shown first under {@code heading};
	 * {@code null} for no context (a plain list).
	 */
	public void setRelevant(Set<String> relevant, String heading) {
		this.relevant = relevant;
		this.relevantHeading = heading == null ? "" : heading;
	}

	/** Replaces the selection, as declared by a Script: {@code all} wins over specific ids, as in the grid display. */
	public void load(List<String> values, boolean all) {
		selected.clear();
		this.all = all;
		if (!all && values != null) {
			for (String value : values) {
				if (indexOf(value) < 0) {
					selected.add(value);
				}
			}
		}
	}

	public boolean isAll() {
		return all;
	}

	/** The selected specific ids, as written (empty when {@code ALL} or nothing is selected). */
	public List<String> selected() {
		return Collections.unmodifiableList(selected);
	}

	/** The chips to show: {@code ALL}, or the selected ids. */
	public List<String> chips() {
		return all ? List.of(allKeyword) : selected();
	}

	/** {@code ALL}, {@code NONE}, or the comma-joined ids: the same text the Metadata tab has always shown and written. */
	public String displayValue() {
		if (all) {
			return allKeyword;
		}
		if (selected.isEmpty()) {
			return "NONE";
		}
		return String.join(",", selected);
	}

	public boolean isSelected(String value) {
		if (allKeyword.equalsIgnoreCase(value)) {
			return all;
		}
		return indexOf(value) >= 0;
	}

	/** Selects or unselects {@code value} ({@code ALL} included); selecting {@code ALL} clears the specific ids and the reverse. */
	public void toggle(String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		String trimmed = value.trim();
		if (allKeyword.equalsIgnoreCase(trimmed)) {
			all = !all;
			if (all) {
				selected.clear();
			}
			return;
		}
		int index = indexOf(trimmed);
		if (index >= 0) {
			selected.remove(index);
		} else {
			all = false;
			selected.add(canonical(trimmed));
		}
	}

	/** Unselects {@code value} (a chip's remove button). */
	public void remove(String value) {
		if (isSelected(value)) {
			toggle(value);
		}
	}

	/** Unselects the last chip (Backspace in an empty filter); {@code false} when nothing was selected. */
	public boolean removeLast() {
		if (all) {
			all = false;
			return true;
		}
		if (selected.isEmpty()) {
			return false;
		}
		selected.remove(selected.size() - 1);
		return true;
	}

	public void setFilter(String filter) {
		this.filter = filter == null ? "" : filter.trim();
	}

	public String filter() {
		return filter;
	}

	/** The list to offer for the current filter and context; see this class's javadoc. */
	public List<Row> rows() {
		List<Row> rows = new ArrayList<>();
		if (matches(allKeyword)) {
			rows.add(new Row(Kind.ALL, allKeyword, all));
		}
		List<String> values = new ArrayList<>();
		if (options != null) {
			values.addAll(options);
		}
		for (String value : selected) {
			if (indexIn(values, value) < 0) {
				values.add(value);
			}
		}
		List<String> first = new ArrayList<>();
		List<String> others = new ArrayList<>();
		for (String value : values) {
			if (!matches(value)) {
				continue;
			}
			if (relevant != null && containsIgnoreCase(relevant, value)) {
				first.add(value);
			} else {
				others.add(value);
			}
		}
		if (relevant != null && !first.isEmpty() && !others.isEmpty()) {
			rows.add(new Row(Kind.HEADING, relevantHeading, false));
			addValues(rows, first);
			rows.add(new Row(Kind.HEADING, "Other " + noun, false));
			addValues(rows, others);
		} else {
			addValues(rows, first);
			addValues(rows, others);
		}
		if (options == null && !filter.isEmpty() && indexIn(values, filter) < 0 && !allKeyword.equalsIgnoreCase(filter)) {
			rows.add(new Row(Kind.ADD, filter, false));
		}
		return rows;
	}

	private void addValues(List<Row> rows, List<String> values) {
		for (String value : values) {
			rows.add(new Row(Kind.VALUE, value, indexOf(value) >= 0));
		}
	}

	/** The index of the next selectable row after {@code from} in {@code direction} (+1/-1), staying on {@code from} at either end. */
	public static int nextSelectable(List<Row> rows, int from, int direction) {
		for (int i = from + direction; i >= 0 && i < rows.size(); i += direction) {
			if (rows.get(i).selectable()) {
				return i;
			}
		}
		if (from >= 0 && from < rows.size() && rows.get(from).selectable()) {
			return from;
		}
		return -1;
	}

	/** Applies {@code row}: toggles an id or {@code ALL}, or adds the typed value. */
	public void activate(Row row) {
		if (row != null && row.selectable()) {
			toggle(row.text());
		}
	}

	private boolean matches(String value) {
		return filter.isEmpty() || value.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
	}

	/** A known option's own spelling for {@code value}, or {@code value} itself. */
	private String canonical(String value) {
		if (options != null) {
			int index = indexIn(options, value);
			if (index >= 0) {
				return options.get(index);
			}
		}
		return value;
	}

	private int indexOf(String value) {
		return indexIn(selected, value);
	}

	private static int indexIn(List<String> values, String value) {
		for (int i = 0; i < values.size(); i++) {
			if (values.get(i).equalsIgnoreCase(value)) {
				return i;
			}
		}
		return -1;
	}

	private static boolean containsIgnoreCase(Set<String> values, String value) {
		for (String candidate : values) {
			if (candidate.equalsIgnoreCase(value)) {
				return true;
			}
		}
		return false;
	}
}
