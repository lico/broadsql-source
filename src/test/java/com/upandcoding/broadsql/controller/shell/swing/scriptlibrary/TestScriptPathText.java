package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.reader.InputOffer;

/**
 * SPRINT 3009A (#189): Copy Path and Send to CLI texts. The prompt is a fake that records what it was offered, so the
 * test sees exactly what reaches the console: one offer of the canonical command, never an execution.
 */
class TestScriptPathText {

	@Test
	void copyPathOffersTheLibraryPathTheFullPathAndTheCliCommand() throws BroadSQLException {
		Path full = Path.of("C:/lib/reports/QR13.sql").toAbsolutePath();
		Assertions.assertEquals("reports/QR13.sql", ScriptPathText.text(ScriptPathText.Kind.LIBRARY_PATH, "reports/QR13.sql", full));
		Assertions.assertEquals(full.toString(), ScriptPathText.text(ScriptPathText.Kind.FULL_PATH, "reports/QR13.sql", full));
		Assertions.assertEquals("@reports/QR13.sql;", ScriptPathText.text(ScriptPathText.Kind.CLI_COMMAND, "reports/QR13.sql", full));
	}

	@Test
	void sendToCliOffersTheCanonicalCommandOnceAndNeverSubmitsIt() {
		List<String> offered = new ArrayList<>();
		ScriptPathText.SendResult result = ScriptPathText.sendToCli("reports/QR13.sql", false, text -> {
			offered.add(text);
			return InputOffer.PLACED;
		});

		Assertions.assertEquals(List.of("@reports/QR13.sql;"), offered, "exactly one offer, of the canonical command; offering is the only way text reaches the prompt");
		Assertions.assertTrue(result.placed());
		Assertions.assertEquals("Sent '@reports/QR13.sql;' to the BroadSQL prompt; press Enter there to run it.", result.message());
	}

	@Test
	void theCurrentPathIsUsedAfterARenameOrMove() {
		List<String> offered = new ArrayList<>();
		ScriptPathText.sendToCli("archive/my reports/QR 13.sql", false, text -> {
			offered.add(text);
			return InputOffer.PLACED;
		});
		Assertions.assertEquals(List.of("@\"archive/my reports/QR 13.sql\";"), offered);
	}

	@Test
	void unsavedChangesAreMentionedBecauseTheCommandRunsTheSavedFile() {
		ScriptPathText.SendResult result = ScriptPathText.sendToCli("a.sql", true, text -> InputOffer.PLACED);
		Assertions.assertTrue(result.message().contains("save first"), result.message());
	}

	@Test
	void everyRefusalIsExplained() {
		for (InputOffer refusal : List.of(InputOffer.UNSUPPORTED, InputOffer.NOT_AT_PROMPT, InputOffer.STATEMENT_PENDING, InputOffer.INPUT_NOT_EMPTY)) {
			ScriptPathText.SendResult result = ScriptPathText.sendToCli("a.sql", false, text -> refusal);
			Assertions.assertFalse(result.placed(), refusal.name());
			Assertions.assertTrue(result.message().startsWith("Not sent: "), result.message());
		}
		ScriptPathText.SendResult noConsole = ScriptPathText.sendToCli("a.sql", false, null);
		Assertions.assertFalse(noConsole.placed());
		Assertions.assertTrue(noConsole.message().contains("Copy CLI Command"), noConsole.message());
	}

	@Test
	void aPathThatCannotBeACommandArgumentIsNotOffered() {
		List<String> offered = new ArrayList<>();
		ScriptPathText.SendResult result = ScriptPathText.sendToCli("odd\"name.sql", false, text -> {
			offered.add(text);
			return InputOffer.PLACED;
		});
		Assertions.assertTrue(offered.isEmpty());
		Assertions.assertFalse(result.placed());
	}
}
