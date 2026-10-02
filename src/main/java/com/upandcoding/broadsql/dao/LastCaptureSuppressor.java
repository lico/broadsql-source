package com.upandcoding.broadsql.dao;

/**
 * Cooperative flag telling {@code QueryExtractorToScreen} to skip updating {@link LastQueryResultHolder}
 * for the query currently executing - mirrors the existing {@code CommandCancellation} static-flag
 * pattern (same package as neither, but same shape: a plain static flag, no dependency injection, no
 * execution-context object threaded through method signatures).
 *
 * <p>Exists for exactly one caller today: {@code CommandHelp}'s "H2 specific help" lookup
 * (querying H2's own internal help tables via {@code DatabaseConnection#executeSelectQuery}) is a
 * BroadSQL-internal query, not a user data query - {@code <@last:column>} must keep reflecting the most
 * recent *user* query result across a {@code HELP} lookup in between (docs/TODO.md, "Previous result's
 * column as SQL input"). Deliberately not a general execution-context/tagging framework: this is a
 * single boolean, checked at the one place capture is actually committed
 * ({@code QueryExtractorToScreen.extractToScreen}), not threaded through any method signature.
 *
 * <p>Not designed for reentrancy - the one caller always wraps a single {@code executeSelectQuery} call
 * in a {@code try/finally} ({@link #suppress()} before, {@link #allow()} after), so nesting never
 * actually occurs in practice.
 */
public final class LastCaptureSuppressor {

	private static boolean suppressed = false;

	private LastCaptureSuppressor() {
	}

	public static void suppress() {
		suppressed = true;
	}

	public static void allow() {
		suppressed = false;
	}

	public static boolean isSuppressed() {
		return suppressed;
	}
}
