package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.JTextField;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.RunOutcome;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptRunResult;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptStatus;

/**
 * SPRINT 0110A: the BroadSQL Editor's side of named arguments (spec sections 14.5, 17.7, 17.8): the Run parameter
 * fields come from {@code -- @params} (never from {@code %N}), no dialog without them, the status bar text per
 * status, and the declared parameters in the Metadata panel. The dialog itself needs a display; its field logic is
 * tested headless through {@code ScriptArguments}, and its widget is exercised when a display is available.
 */
class TestEditorScriptParameters {

	@Test
	void theDialogFieldsAreTheDeclaredParamsInOrder() {
		Assertions.assertEquals(List.of("customer_id", "country"),
				ScriptLibraryFrame.runParameterNames("-- @description: x\n-- @params: customer_id, country\nSELECT ${customer_id}, ${country};"));
		Assertions.assertEquals(List.of("a", "b"), ScriptLibraryFrame.runParameterNames("-- @params: a\nSELECT 1;\n/* @params: b, A */"));
	}

	@Test
	void noParamsMeansNoDialogEvenWithLegacyPercentPlaceholders() {
		Assertions.assertTrue(ScriptLibraryFrame.runParameterNames("SELECT * FROM t WHERE a = %1 AND b = '%2';").isEmpty());
		Assertions.assertTrue(ScriptLibraryFrame.runParameterNames("SELECT ${undeclared};").isEmpty());
	}

	@Test
	void theStatusBarTextFollowsTheRunStatus() {
		Assertions.assertEquals("Execution completed: SUCCESS.", ScriptLibraryFrame.statusText(outcome(ScriptStatus.SUCCESS)));
		Assertions.assertEquals("Execution completed with errors: COMPLETED_WITH_ERRORS, see Output.", ScriptLibraryFrame.statusText(outcome(ScriptStatus.COMPLETED_WITH_ERRORS)));
		Assertions.assertEquals("Execution failed: FAILED, see Output.", ScriptLibraryFrame.statusText(outcome(ScriptStatus.FAILED)));
		Assertions.assertEquals("Execution cancelled: CANCELLED, see Output.", ScriptLibraryFrame.statusText(outcome(ScriptStatus.CANCELLED)));
		Assertions.assertEquals("Execution failed: see Output.",
				ScriptLibraryFrame.statusText(RunOutcome.executed("x", new BroadSQLException("boom"))), "no run result: unexpected failure");
	}

	@Test
	void hasErrorsIsTrueForEveryStatusButSuccess() {
		for (ScriptStatus status : ScriptStatus.values()) {
			Assertions.assertEquals(status != ScriptStatus.SUCCESS, outcome(status).hasErrors(), status.name());
		}
	}

	private static RunOutcome outcome(ScriptStatus status) {
		return RunOutcome.executed("", null, 0, new ScriptRunResult("x.bsql", status, 1, status == ScriptStatus.SUCCESS ? 0 : 1, "RUNID001", 0, 0, true));
	}

	@Test
	void theMetadataPanelShowsTheDeclaredParameters() {
		MetadataPanel panel = new MetadataPanel();
		Assertions.assertEquals("none declared", panel.fieldText("params"));
		panel.load(ScriptMetadataHeader.parse("-- @params: customer_id, country\nSELECT 1;"));
		Assertions.assertEquals("customer_id, country", panel.fieldText("params"));
		Assertions.assertFalse(((JTextField) panel.field("params")).isEditable(), "read only: edited in the text");
		panel.load(ScriptMetadataHeader.parse("SELECT 1;"));
		Assertions.assertEquals("none declared", panel.fieldText("params"));
	}

	@Test
	void theDialogEnablesRunOnlyWhenEveryFieldIsValidAndBuildsNamedArguments(@TempDir Path ignored) throws Exception {
		Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "the dialog needs a display (covered headless through ScriptArguments)");
		RunParametersDialog dialog = new RunParametersDialog(null, List.of("customer_id", "country"));
		Assertions.assertEquals(List.of("customer_id", "country"), dialog.names());
		Assertions.assertFalse(dialog.isRunEnabled(), "empty fields");
		dialog.fields().get(0).setText("42");
		dialog.fields().get(1).setText("FR");
		Assertions.assertFalse(dialog.isRunEnabled(), "FR is not a value (a string is quoted)");
		dialog.fields().get(1).setText("'F R'");
		Assertions.assertTrue(dialog.isRunEnabled(), dialog.problems().toString());
		Assertions.assertEquals("customer_id=42 country='F R'", dialog.argumentText());
		dialog.dispose();
		Files.exists(ignored);
	}
}
