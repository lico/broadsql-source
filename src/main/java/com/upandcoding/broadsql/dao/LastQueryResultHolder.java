package com.upandcoding.broadsql.dao;

/**
 * Static holder for the current {@link LastQueryResult} snapshot, mirroring the existing
 * {@code CommandCancellation} pattern (a cooperative flag with no Spring wiring) - needed because
 * {@code LastResultListSource} is instantiated by the static
 * {@code com.upandcoding.broadsql.controller.shell.commands.listsource.ListSourceResolver.resolve(String)}
 * factory, with no dependency-injected path back to whichever {@code DatabaseConnection} instance
 * (the session's singleton {@code sqlDatabase} bean, or a transient one built for
 * {@code CrossEnvironmentRerun}) produced the result.
 *
 * <p>BroadSQL runs one command at a time on a single dedicated worker thread (see
 * {@code CommandInterpreter}), so a plain {@code volatile} field is sufficient - no concurrent
 * producers are possible.
 */
public final class LastQueryResultHolder {

	private static volatile LastQueryResult current;

	private LastQueryResultHolder() {
	}

	public static void set(LastQueryResult result) {
		current = result;
	}

	public static LastQueryResult get() {
		return current;
	}
}
