package com.upandcoding.broadsql.controller.shell;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;

/**
 * Builds a {@link Session} whose {@code currentDatabase} is a given {@link DatabaseConnection} - the
 * package-private field {@code Session.openDatabase} connects, which is what {@code CONNECT}'s success
 * path needs (SPRINT 2309T, {@code ENV} end-to-end tests). Lives in this package for that field access only.
 */
public final class SessionTestSupport {

	private SessionTestSupport() {
	}

	public static Session newSession(DatabaseDefinitionsVault vault, ConsoleSettings settings, DatabaseConnection currentDatabase) throws BroadSQLException {
		// The vault's own password and file: the interpreter re-reads the new connection's login script
		// from the CDF after every successful CONNECT.
		settings.setProtectedPlatformsFileName(vault.getFileName());
		Session session = new Session(vault, settings, null, vault.getPassword());
		session.currentDatabase = currentDatabase;
		return session;
	}
}
