package com.upandcoding.broadsql.controller.shell.reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.jline.reader.impl.LineReaderImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.upandcoding.broadsql.controller.shell.commands.CommandList;

/**
 * SPRINT 3009A (#189): the BroadSQL Editor's Send to CLI places a command on the prompt's input line from another
 * thread, through the real JLine reader (the same key-binding terminal as {@code TestJLineConsoleLineReaderShortcutKeys}).
 * The line is only returned, that is executed, when Enter arrives; typed input, an unfinished statement and any
 * read other than the command prompt are never touched.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class TestJLineConsoleLineReaderOfferInput {

	private static final String COMMAND = "@reports/QR13.sql;";

	/** A reader over a pipe that stays open, so a read blocks exactly like a person not typing yet. */
	private static final class Console implements AutoCloseable {
		final PipedOutputStream keyboard = new PipedOutputStream();
		final ByteArrayOutputStream screen = new ByteArrayOutputStream();
		final JLineConsoleLineReader reader;

		Console() throws IOException {
			PipedInputStream in = new PipedInputStream(keyboard, 4096);
			reader = JLineConsoleLineReader.createForTestingWithRealKeyBindings(in, screen, null, null, new CommandList(), null, "windows-vtp");
		}

		void type(String keys) throws IOException {
			keyboard.write(keys.getBytes(StandardCharsets.UTF_8));
			keyboard.flush();
		}

		LineReaderImpl impl() {
			return (LineReaderImpl) reader.lineReaderForTesting();
		}

		@Override
		public void close() {
			reader.close();
		}
	}

	private static void await(BooleanSupplier condition, String what) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				Assertions.fail("timed out waiting for " + what);
			}
			Thread.sleep(10);
		}
	}

	@Test
	void theOfferedCommandIsPlacedOnThePromptAndRunsOnlyWhenEnterIsPressed() throws Exception {
		try (Console console = new Console()) {
			CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> console.reader.readCommandLine("SQL> ", false));
			await(() -> console.impl().isReading(), "the prompt");

			Assertions.assertEquals(InputOffer.PLACED, console.reader.offerInput(COMMAND));

			Thread.sleep(300);
			Assertions.assertFalse(line.isDone(), "nothing is submitted: the prompt is still waiting for Enter");
			Assertions.assertEquals(COMMAND, console.impl().getBuffer().toString(), "the command is on the input line");
			Assertions.assertTrue(console.screen.toString(StandardCharsets.UTF_8).contains(COMMAND), "and drawn on screen");

			console.type("\r");
			Assertions.assertEquals(COMMAND, line.get(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void theUserCanEditTheOfferedCommandBeforeRunningIt() throws Exception {
		try (Console console = new Console()) {
			CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> console.reader.readCommandLine("SQL> ", false));
			await(() -> console.impl().isReading(), "the prompt");
			Assertions.assertEquals(InputOffer.PLACED, console.reader.offerInput(COMMAND));

			console.type("\b 42;\r"); // Backspace removes the ';', then a parameter and a new ';'
			Assertions.assertEquals("@reports/QR13.sql 42;", line.get(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void typedInputIsNeverReplaced() throws Exception {
		try (Console console = new Console()) {
			CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> console.reader.readCommandLine("SQL> ", false));
			await(() -> console.impl().isReading(), "the prompt");
			console.type("SELECT 1");
			await(() -> console.impl().getBuffer().length() == 8, "the typed text");

			Assertions.assertEquals(InputOffer.INPUT_NOT_EMPTY, console.reader.offerInput(COMMAND));

			console.type(";\r");
			Assertions.assertEquals("SELECT 1;", line.get(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void anUnfinishedStatementIsNeverExtended() throws Exception {
		try (Console console = new Console()) {
			CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> console.reader.readCommandLine("   > ", true));
			await(() -> console.impl().isReading(), "the continuation prompt");

			Assertions.assertEquals(InputOffer.STATEMENT_PENDING, console.reader.offerInput(COMMAND));

			console.type("\r");
			Assertions.assertEquals("", line.get(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void nothingIsOfferedOutsideTheCommandPrompt() throws Exception {
		try (Console console = new Console()) {
			Assertions.assertEquals(InputOffer.NOT_AT_PROMPT, console.reader.offerInput(COMMAND), "no read in progress: a command is running");

			// A read that is not the command prompt (a command asking a question, as SET PASSWORD does).
			CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> console.reader.readLine("Confirm: "));
			await(() -> console.impl().isReading(), "the question");
			Assertions.assertEquals(InputOffer.NOT_AT_PROMPT, console.reader.offerInput(COMMAND));
			console.type("yes\r");
			Assertions.assertEquals("yes", answer.get(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void theBasicConsoleCannotReceiveOfferedInput() {
		Assertions.assertEquals(InputOffer.UNSUPPORTED, new BasicLineReader(null).offerInput(COMMAND));
	}
}
