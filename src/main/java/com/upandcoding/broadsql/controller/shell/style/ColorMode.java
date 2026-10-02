package com.upandcoding.broadsql.controller.shell.style;

import java.util.Locale;

/**
 * SPRINT 2409K: the {@code color} setting.
 * <ul>
 * <li>{@link #AUTO}: style only when BroadSQL's console is a JLine terminal that reports color support
 * (never a dumb terminal, never output redirected to a file or pipe, never with {@code activatejline=OFF});</li>
 * <li>{@link #ON}: always style, even when the terminal does not report color support;</li>
 * <li>{@link #OFF}: never style.</li>
 * </ul>
 */
public enum ColorMode {
	AUTO, ON, OFF;

	/** The mode named by {@code value} (case-insensitive, trimmed), or {@code null} if it names none. */
	public static ColorMode parse(String value) {
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
