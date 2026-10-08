package com.upandcoding.broadsql.controller.shell.repeat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibRun;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandConnect;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandDefault;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExternalFile;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandRepeat;

/** GitHub #196: the read-only rule of {@code REPEAT}, on its own (the integration tests apply it through each target). */
class TestRepeatSafety {

	@ParameterizedTest
	@ValueSource(strings = { "SELECT 1", "select * from t", "SELECT\tID FROM T", "SELECT\nID\nFROM T", "WITH x AS (SELECT 1) SELECT * FROM x",
			"SHOW TABLES", "EXPLAIN SELECT 1", "SELECT 'INSERT INTO t' AS txt FROM t", "SELECT \"UPDATE\" FROM t", "SELECT t.update FROM t",
			"SELECT COUNT(*) FROM JOBS WHERE STATUS = 'DELETE' -- not deleting", "SELECT UPDATED_AT, DELETED FROM T" })
	void queriesMayBeRepeated(String sql) {
		Assertions.assertNull(RepeatSafety.problem(null, sql), sql);
		Assertions.assertNull(RepeatSafety.problem(new CommandDefault(), sql), sql);
	}

	@ParameterizedTest
	@ValueSource(strings = { "INSERT INTO t VALUES (1)", "UPDATE t SET a = 1", "DELETE FROM t", "MERGE INTO t USING s ON (1=1)", "CREATE TABLE x (a INT)",
			"DROP TABLE x", "ALTER TABLE x ADD b INT", "TRUNCATE TABLE x", "CALL my_proc()", "SCRIPT TO 'dump.sql'", "commit", "rollback",
			"WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d", "SELECT * INTO new_t FROM t", "SELECT * FROM t FOR UPDATE", "GRANT SELECT ON t TO u",
			"(SELECT 1)", "" })
	void everythingElseIsRefused(String sql) {
		String problem = RepeatSafety.problem(null, sql);
		Assertions.assertNotNull(problem, sql);
		Assertions.assertTrue(problem.contains(RepeatSafety.RULE), problem);
	}

	@Test
	void scriptCallsAreAllowedTheirStatementsAreCheckedWhenTheyRun() {
		Assertions.assertNull(RepeatSafety.problem(new CommandExternalFile(), "@monitor.sql"));
		Assertions.assertNull(RepeatSafety.problem(new CommandLibRun(), "LIB RUN monitor.sql"));
	}

	@Test
	void broadSqlCommandsAreRefusedByName() {
		String problem = RepeatSafety.problem(new CommandConnect(), "CONNECT WORLD");
		Assertions.assertTrue(problem.contains("CONNECT is a BroadSQL command"), problem);
	}

	@Test
	void aNestedRepeatHasItsOwnMessage() {
		String problem = RepeatSafety.problem(new CommandRepeat(), "REPEAT EVERY 1s");
		Assertions.assertTrue(problem.startsWith("Nested REPEAT is not supported"), problem);
	}

	@Test
	void writeWordsAreFoundOutsideQuotesAndCommentsOnly() {
		Assertions.assertEquals("INTO", RepeatSafety.firstWriteWord("SELECT a INTO b FROM t"));
		Assertions.assertNull(RepeatSafety.firstWriteWord("SELECT 'it''s INTO' FROM t /* UPDATE */ -- DELETE"));
		Assertions.assertNull(RepeatSafety.firstWriteWord("SELECT INSERTED, UPDATE_COUNT FROM t"));
		Assertions.assertEquals("UPDATE", RepeatSafety.firstWriteWord("SELECT 1 FROM t\nFOR\tUPDATE"));
	}
}
