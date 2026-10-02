package com.upandcoding.broadsql.controller.shell.style;

/**
 * SPRINT 2409K: the process-wide effective {@link TerminalStyle}, set once at startup by
 * {@code BroadSQL.main} after the console is built (same holder pattern as {@code ApiSessionContextHolder}).
 * Until then, and whenever it is never set (tests, {@code activatejline=OFF} with {@code color=AUTO}), it is
 * {@link TerminalStyle#PLAIN}.
 */
public final class TerminalStyleHolder {

	private static volatile TerminalStyle current = TerminalStyle.PLAIN;

	private TerminalStyleHolder() {
	}

	public static TerminalStyle get() {
		return current;
	}

	public static void set(TerminalStyle style) {
		current = style == null ? TerminalStyle.PLAIN : style;
	}
}
