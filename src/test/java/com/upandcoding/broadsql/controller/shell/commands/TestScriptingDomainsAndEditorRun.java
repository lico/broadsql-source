package com.upandcoding.broadsql.controller.shell.commands;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.scriptlibrary.RunOutcome;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptRunContext;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptRunCoordinator;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptStatus;
import com.upandcoding.broadsql.dao.api.ApiSessionVariablesHolder;
import com.upandcoding.broadsql.dao.api.ApiVariableSubstitutor;

/**
 * SPRINT 0110A: SQL scripting variables and API variables stay two independent domains (spec section 21), the new
 * keywords and MySQL-style {@code SHOW VARIABLES} (6.8), and the Editor Run end to end with named arguments, status,
 * {@code OUTPUT QUIET} and {@code ECHO} in the captured output (14.5, 17.7).
 */
class TestScriptingDomainsAndEditorRun extends ScriptingTestBase {

	@Test
	void varAndLetWithTheSameNameAreIndependent() {
		line("VAR X=api;");
		line("LET X = 'sql';");
		Assertions.assertEquals("api", ApiSessionVariablesHolder.get("X"));
		Assertions.assertEquals("sql", value("X"));
		line("VAR X=api2;");
		Assertions.assertEquals("sql", value("X"), "VAR never writes SQL scripting variables");
		line("LET y = 1;");
		Assertions.assertFalse(ApiSessionVariablesHolder.isSet("y"), "LET never writes API variables");
	}

	@Test
	void anApiVariableIsNeverVisibleInSqlOnlyNamedInTheHint() {
		line("VAR TOKEN=abc;");
		line("SELECT ${token};");
		Assertions.assertTrue(output().contains("Variable token is not defined. An API variable token exists"), output());
	}

	@Test
	void apiTemplatesStillResolveFromApiVariablesOnly() throws Exception {
		line("LET id = 999;");
		String resolved = ApiVariableSubstitutor.substitute("/customers/${id}", Map.of("id", "42"), "test");
		Assertions.assertEquals("/customers/42", resolved);
	}

	@Test
	void theNewKeywordsAreCommandsAndMySqlShowVariablesStillReachesTheDatabase() throws Exception {
		line("SHOW VARIABLES;");
		Assertions.assertTrue(output().contains("ERROR"), "sent to H2, which has no SHOW VARIABLES: " + output());
		Assertions.assertFalse(output().contains("No script variables"), output());
		Assertions.assertNotNull(interpreter.getCommands().getCommandClassFromName("LET X = 1"));
		Assertions.assertNotNull(interpreter.getCommands().getCommandClassFromName("ECHO 'x'"));
		Assertions.assertNotNull(interpreter.getCommands().getCommandClassFromName("ON ERROR STOP"));
		Assertions.assertNotNull(interpreter.getCommands().getCommandClassFromName("OUTPUT QUIET"));
		Assertions.assertNotNull(interpreter.getCommands().getCommandClassFromName("SHOW SCRIPT VARIABLES"));
		Assertions.assertNull(interpreter.getCommands().getCommandClassFromName("LETTER = 1"), "whole-word keywords only");
	}

	@Test
	void anEditorRunPassesTypedNamedArgumentsAndReportsTheStatus() throws Exception {
		lib("ed.bsql", "-- @params: customer_id, country\nOUTPUT QUIET;\nINSERT INTO customer VALUES (${customer_id}, 'x', ${country});\n"
				+ "ECHO 'Inserted ${customer_id} for ${country}';\n");
		line("LET cid = 7;");
		ScriptRunContext context = new ScriptRunContext(db, interpreter, null, null, settings, db.getPlatform().getId());
		RunOutcome outcome = new ScriptRunCoordinator().run("ed.bsql", context, "customer_id=${cid} country='FR'");
		Assertions.assertEquals(ScriptStatus.SUCCESS, outcome.scriptStatus(), outcome.capturedOutput());
		Assertions.assertFalse(outcome.hasErrors());
		Assertions.assertSame(var("cid"), var("customer_id"), "a ${name} field is read from the session as a typed value");
		Assertions.assertTrue(outcome.capturedOutput().contains("Inserted 7 for FR"), outcome.capturedOutput());
		Assertions.assertFalse(outcome.capturedOutput().contains("1 row(s) created."), "QUIET applies in the Output pane: " + outcome.capturedOutput());
		Assertions.assertFalse(outcome.capturedOutput().contains("SUCCESS ("), outcome.capturedOutput());
		Assertions.assertEquals(1L, count("CUSTOMER"));
	}

	@Test
	void anEditorRunWithoutTheDeclaredArgumentsFails() throws Exception {
		lib("ed.bsql", "-- @params: customer_id\nSELECT ${customer_id};\n");
		ScriptRunContext context = new ScriptRunContext(db, interpreter, null, null, settings, db.getPlatform().getId());
		RunOutcome outcome = new ScriptRunCoordinator().run("ed.bsql", context, "");
		Assertions.assertEquals(ScriptStatus.FAILED, outcome.scriptStatus());
		Assertions.assertTrue(outcome.hasErrors());
		Assertions.assertTrue(outcome.capturedOutput().contains("requires argument customer_id"), outcome.capturedOutput());
	}

	@Test
	void anEditorRunWithErrorsCompletesWithErrors() throws Exception {
		lib("ed.bsql", "INSERT INTO nope VALUES (1);\nSELECT 1;\n");
		ScriptRunContext context = new ScriptRunContext(db, interpreter, null, null, settings, db.getPlatform().getId());
		RunOutcome outcome = new ScriptRunCoordinator().run("ed.bsql", context, "");
		Assertions.assertEquals(ScriptStatus.COMPLETED_WITH_ERRORS, outcome.scriptStatus());
		Assertions.assertTrue(outcome.hasErrors());
		Assertions.assertTrue(outcome.capturedOutput().contains("ed.bsql: COMPLETED_WITH_ERRORS (2 statements, 1 failed)"), outcome.capturedOutput());
	}

	@Test
	void loginScriptStyleLinesUseTheSessionVariablesAndNeverThrow() throws Exception {
		line("LET who = 'login';");
		interpreter.setQuery("INSERT INTO customer VALUES (1, ${who}, 'FR')");
		interpreter.executeCommand();
		interpreter.setQuery("SELECT ${undefined_in_login}");
		Assertions.assertDoesNotThrow(() -> interpreter.executeCommand(), "an undefined reference is reported like any SQL error of a login line");
		Assertions.assertEquals("login", sql("SELECT NAME FROM CUSTOMER WHERE ID = 1"));
	}
}
