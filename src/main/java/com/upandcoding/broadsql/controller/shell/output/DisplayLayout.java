package com.upandcoding.broadsql.controller.shell.output;

import java.util.Arrays;
import java.util.function.IntSupplier;

/**
 * SPRINT 2409K: the one place that decides the geometry of tabular screen output, shared by the SQL
 * result renderer ({@code QueryExtractorToScreen}) and the metadata/catalog grids ({@link ConsolePrinter}),
 * so every command using them adapts the same way. Presentation only: a value is never altered where it
 * is stored, captured ({@code <@last:...>}, {@code DUMP /}) or exported, only in what is printed.
 *
 * <p>The terminal width is read through a supplier at every table (JLine's live terminal size), so
 * resizing the window affects the next output without restarting. With no known width (no JLine terminal,
 * output redirected) {@link DisplayMode#AUTO} is {@link DisplayMode#NORMAL}.
 *
 * <ul>
 * <li>{@link DisplayMode#NORMAL}: BroadSQL's established layout, unchanged: each column as wide as its
 * header and declared size, values never shortened;</li>
 * <li>{@link DisplayMode#COMPACT}: when the table is wider than the terminal, the widest columns are
 * narrowed (to no less than {@link #COMPACT_MIN_COLUMN_WIDTH}) and longer values shortened on screen with a
 * trailing {@value #TRUNCATION_MARK}; when even that cannot fit, each row is shown vertically, one
 * {@code COLUMN: value} line per column;</li>
 * <li>{@link DisplayMode#WIDE}: same widths as {@code NORMAL}, never shortened, with a space on each side of
 * the column separators for readability.</li>
 * </ul>
 * {@link DisplayMode#AUTO} is {@code COMPACT} below {@link #COMPACT_BELOW_COLUMNS} columns, {@code WIDE} from
 * {@link #WIDE_FROM_COLUMNS} columns, {@code NORMAL} in between.
 */
public final class DisplayLayout {

	/** AUTO chooses COMPACT for a terminal narrower than this many columns. */
	public static final int COMPACT_BELOW_COLUMNS = 80;
	/** AUTO chooses WIDE for a terminal at least this many columns wide. */
	public static final int WIDE_FROM_COLUMNS = 160;
	/** COMPACT never narrows a column below this width. */
	public static final int COMPACT_MIN_COLUMN_WIDTH = 6;
	/** COMPACT's width budget when the terminal width is unknown. */
	static final int DEFAULT_TERMINAL_COLUMNS = 80;
	/** Marks a value shortened for display. ASCII, so it survives the console's Cp850 output encoding. */
	public static final String TRUNCATION_MARK = "~";

	private static volatile DisplayMode configured = DisplayMode.AUTO;
	private static volatile IntSupplier terminalWidth = () -> 0;

	private DisplayLayout() {
	}

	/** Installed once at startup ({@code BroadSQL.main}); {@code width} returns 0 when unknown. */
	public static void configure(DisplayMode mode, IntSupplier width) {
		configured = mode == null ? DisplayMode.AUTO : mode;
		terminalWidth = width == null ? () -> 0 : width;
	}

	public static DisplayMode configuredMode() {
		return configured;
	}

	/** The current terminal width in columns, 0 when unknown. */
	public static int terminalWidth() {
		try {
			return Math.max(0, terminalWidth.getAsInt());
		} catch (RuntimeException e) {
			return 0;
		}
	}

	/** The mode in effect now: the configured one, or for AUTO the one the current width calls for. */
	public static DisplayMode effectiveMode() {
		return resolve(configured, terminalWidth());
	}

	static DisplayMode resolve(DisplayMode mode, int width) {
		if (mode != null && mode != DisplayMode.AUTO) {
			return mode;
		}
		if (width <= 0) {
			return DisplayMode.NORMAL;
		}
		if (width < COMPACT_BELOW_COLUMNS) {
			return DisplayMode.COMPACT;
		}
		return width >= WIDE_FROM_COLUMNS ? DisplayMode.WIDE : DisplayMode.NORMAL;
	}

	/** The decided geometry of one table. */
	public static final class Plan {
		private final DisplayMode mode;
		private final int[] widths;
		private final boolean vertical;
		private final int availableColumns;

		Plan(DisplayMode mode, int[] widths, boolean vertical, int availableColumns) {
			this.mode = mode;
			this.widths = widths;
			this.vertical = vertical;
			this.availableColumns = availableColumns;
		}

		public DisplayMode getMode() {
			return mode;
		}

		/** Display width of column {@code i}. */
		public int width(int i) {
			return widths[i];
		}

		/** Show each row vertically ({@code COLUMN: value} lines) instead of as a grid. */
		public boolean isVertical() {
			return vertical;
		}

		/** Values longer than their column are shortened on screen (COMPACT only). */
		public boolean truncates() {
			return mode == DisplayMode.COMPACT;
		}

		/** Spaces on each side of a column separator: 1 in WIDE, 0 otherwise. */
		public int gap() {
			return mode == DisplayMode.WIDE ? 1 : 0;
		}

		/** Columns available on the terminal line (a nominal width when unknown). */
		public int availableColumns() {
			return availableColumns;
		}
	}

	/** The plan for a table with these natural column widths, for the mode in effect now. */
	public static Plan plan(int[] naturalWidths, int separatorWidth) {
		return plan(naturalWidths, separatorWidth, 0);
	}

	/** Same as {@link #plan(int[], int)} for a table that also prints {@code fixedOverhead} characters per line (e.g. a leading border). */
	public static Plan plan(int[] naturalWidths, int separatorWidth, int fixedOverhead) {
		return plan(effectiveMode(), terminalWidth(), naturalWidths, separatorWidth, fixedOverhead);
	}

	static Plan plan(DisplayMode mode, int width, int[] naturalWidths, int separatorWidth) {
		return plan(mode, width, naturalWidths, separatorWidth, 0);
	}

	/**
	 * @param naturalWidths  each column's width in the established (NORMAL) layout
	 * @param separatorWidth characters printed per column besides its content (the separator itself)
	 * @param fixedOverhead  characters printed once per line besides the columns
	 */
	static Plan plan(DisplayMode mode, int width, int[] naturalWidths, int separatorWidth, int fixedOverhead) {
		// one column is kept free: a line exactly as wide as the terminal makes some consoles wrap
		int available = (width > 0 ? width : DEFAULT_TERMINAL_COLUMNS) - 1;
		int[] widths = Arrays.copyOf(naturalWidths, naturalWidths.length);
		if (mode != DisplayMode.COMPACT || widths.length == 0) {
			return new Plan(mode, widths, false, available);
		}
		int overhead = widths.length * separatorWidth + fixedOverhead;
		if (sum(widths) + overhead <= available) {
			return new Plan(mode, widths, false, available);
		}
		int budget = available - overhead;
		int minimumTotal = 0;
		for (int w : widths) {
			minimumTotal += Math.min(w, COMPACT_MIN_COLUMN_WIDTH);
		}
		if (minimumTotal > budget) {
			return new Plan(mode, widths, true, available);
		}
		// the largest cap that fits: columns narrower than the cap keep their width
		int low = COMPACT_MIN_COLUMN_WIDTH;
		int high = max(widths);
		while (low < high) {
			int cap = (low + high + 1) / 2;
			if (cappedSum(widths, cap) <= budget) {
				low = cap;
			} else {
				high = cap - 1;
			}
		}
		for (int i = 0; i < widths.length; i++) {
			widths[i] = Math.min(widths[i], low);
		}
		return new Plan(mode, widths, false, available);
	}

	/**
	 * {@code value} as shown in a column {@code width} wide: unchanged when it fits, otherwise its first
	 * {@code width - 1} characters and {@link #TRUNCATION_MARK}.
	 */
	public static String fit(String value, int width) {
		if (value == null || value.length() <= width || width <= 0) {
			return value;
		}
		if (width == 1) {
			return TRUNCATION_MARK;
		}
		return value.substring(0, width - 1) + TRUNCATION_MARK;
	}

	private static int sum(int[] values) {
		int total = 0;
		for (int v : values) {
			total += v;
		}
		return total;
	}

	private static int max(int[] values) {
		int m = 0;
		for (int v : values) {
			m = Math.max(m, v);
		}
		return m;
	}

	private static int cappedSum(int[] values, int cap) {
		int total = 0;
		for (int v : values) {
			total += Math.min(v, cap);
		}
		return total;
	}
}
