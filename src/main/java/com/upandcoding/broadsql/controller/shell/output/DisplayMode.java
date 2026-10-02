package com.upandcoding.broadsql.controller.shell.output;

import java.util.Locale;

/**
 * SPRINT 2409K: the {@code displaymode} setting, the geometry of tabular output (independent of the
 * theme, which is appearance). {@link #AUTO} picks one of the three others from the terminal's actual
 * width each time a table is printed (see {@link DisplayLayout}).
 */
public enum DisplayMode {
	AUTO, COMPACT, NORMAL, WIDE;

	/** The mode named by {@code value} (case-insensitive, trimmed), or {@code null} if it names none. */
	public static DisplayMode parse(String value) {
		if (value == null) {
			return null;
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
