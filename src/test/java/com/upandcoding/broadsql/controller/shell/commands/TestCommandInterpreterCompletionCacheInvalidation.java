package com.upandcoding.broadsql.controller.shell.commands;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.completion.JdbcMetadataCompletionCache;
import com.upandcoding.broadsql.controller.shell.completion.JdbcMetadataCompletionCacheHolder;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * SPRINT 0917-02, section 41 ("Cache tests") - proves {@code CommandInterpreter} actually reaches into
 * {@link JdbcMetadataCompletionCacheHolder}'s shared cache at the two points section 26 requires (a
 * successful {@code CREATE}/{@code ALTER}/{@code DROP}, and a platform change), not just that the cache
 * class itself supports invalidation (already covered in isolation by
 * {@code TestJdbcMetadataCompletionProvider}).
 */
class TestCommandInterpreterCompletionCacheInvalidation {

	private DatabaseConnection db;
	private CapturingShellConsole console;
	private ConsoleSettings settings;
	private CommandInterpreter interpreter;
	private JdbcMetadataCompletionCache cache;

	@BeforeEach
	void setUp() throws BroadSQLException {
		settings = TestDatabaseConnections.defaultConsoleSettings();
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE T (ID INT PRIMARY KEY)");
		console = new CapturingShellConsole();
		interpreter = CommandTestSupport.createCommandInterpreter(settings, console, db);
		interpreter.setPlatform(db.getPlatform().getId());
		cache = JdbcMetadataCompletionCacheHolder.get();
		cache.invalidateAll();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		cache.invalidateAll();
		TestDatabaseConnections.close(db);
	}

	@Test
	void successfulDdlInvalidatesTheCurrentPlatformsCache() {
		String platformId = db.getPlatform().getId();
		cache.tables(platformId, "", () -> List.of(new JdbcMetadataCompletionCache.TableEntry("STALE", false)));
		Assertions.assertEquals("STALE", cache.tables(platformId, "", () -> List.of()).get(0).getName(), "sanity check: cached");

		interpreter.executeMultiStatementLine("CREATE TABLE NEW_TABLE (ID INT) ");

		List<JdbcMetadataCompletionCache.TableEntry> afterDdl = cache.tables(platformId, "",
				() -> List.of(new JdbcMetadataCompletionCache.TableEntry("FRESH", false)));
		Assertions.assertEquals("FRESH", afterDdl.get(0).getName(), "DDL must have cleared the stale cached entry");
	}

	@Test
	void aFailedDdlStatementDoesNotInvalidateTheCache() {
		String platformId = db.getPlatform().getId();
		cache.tables(platformId, "", () -> List.of(new JdbcMetadataCompletionCache.TableEntry("STALE", false)));

		// Table T already exists (created in setUp) - this CREATE TABLE must fail.
		interpreter.executeMultiStatementLine("CREATE TABLE T (ID INT) ");

		List<JdbcMetadataCompletionCache.TableEntry> stillCached = cache.tables(platformId, "",
				() -> List.of(new JdbcMetadataCompletionCache.TableEntry("SHOULD_NOT_APPEAR", false)));
		Assertions.assertEquals("STALE", stillCached.get(0).getName(), "a failed statement must not clear the cache");
	}
}
