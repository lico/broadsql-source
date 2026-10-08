package com.upandcoding.broadsql.controller.shell.reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jline.terminal.Terminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.upandcoding.broadsql.controller.shell.commands.CommandList;

/**
 * GitHub #196, the CTRL+C defect found in a real terminal: with JLine active, CTRL+C during a running command ended
 * BroadSQL. Root cause: {@code LineReader.readLine} restores, when it returns, the INT handler the terminal had before
 * it; the terminal was built with JLine's default {@code SIG_DFL}, and restoring {@code SIG_DFL} on a system terminal
 * re-registers the JVM's default SIGINT action (exit), replacing BroadSQL's own handler. These tests use a real
 * key-binding terminal over pipes: its input pump turns a CTRL+C byte into {@code Terminal.raise(INT)}, the same call a
 * system terminal makes for a console CTRL+C (its native SIGINT hook) or a CTRL+C key event.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class TestJLineConsoleLineReaderInterruptHandler {

	private static final int CTRL_C = 3;

	private PipedOutputStream keyboard;
	private JLineConsoleLineReader reader;
	private Terminal terminal;

	@BeforeEach
	void setUp() throws IOException {
		keyboard = new PipedOutputStream();
		PipedInputStream in = new PipedInputStream(keyboard, 4096);
		reader = JLineConsoleLineReader.createForTestingWithRealKeyBindings(in, new ByteArrayOutputStream(), null, null, new CommandList(), null,
				"windows-vtp");
		terminal = reader.terminalForTesting();
	}

	@AfterEach
	void tearDown() {
		reader.close();
	}

	private void type(String keys) throws IOException {
		keyboard.write(keys.getBytes(StandardCharsets.UTF_8));
		keyboard.flush();
	}

	private static void waitFor(AtomicInteger counter, int value) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (counter.get() < value) {
			Assertions.assertTrue(System.currentTimeMillis() < deadline, "the interrupt handler was not called");
			Thread.sleep(10);
		}
	}

	@Test
	void rootCauseWithoutAHandlerReadLineLeavesTheDefaultIntAction() throws IOException {
		type("SELECT 1;\r");
		Assertions.assertEquals("SELECT 1;", reader.readLine("> "));
		Terminal.SignalHandler afterRead = terminal.handle(Terminal.Signal.INT, Terminal.SignalHandler.SIG_DFL);
		Assertions.assertSame(Terminal.SignalHandler.SIG_DFL, afterRead,
				"what readLine restores: on a system terminal SIG_DFL is the JVM's default SIGINT action, which ends BroadSQL");
	}

	@Test
	void theInterruptHandlerSurvivesEveryReadLine() throws Exception {
		AtomicInteger interrupts = new AtomicInteger();
		reader.setInterruptHandler(interrupts::incrementAndGet);
		for (int i = 0; i < 3; i++) {
			type("SELECT " + i + ";\r");
			Assertions.assertEquals("SELECT " + i + ";", reader.readLine("> "));
			Terminal.SignalHandler current = terminal.handle(Terminal.Signal.INT, Terminal.SignalHandler.SIG_DFL);
			Assertions.assertNotSame(Terminal.SignalHandler.SIG_DFL, current, "BroadSQL's handler is restored after read " + i);
			terminal.handle(Terminal.Signal.INT, current);
		}
	}

	@Test
	void ctrlCOutsideReadLineReachesTheHandler() throws Exception {
		AtomicInteger interrupts = new AtomicInteger();
		reader.setInterruptHandler(interrupts::incrementAndGet);
		type("SELECT 1;\r");
		reader.readLine("> ");
		// a command is now running: no read in progress
		keyboard.write(CTRL_C);
		keyboard.flush();
		waitFor(interrupts, 1);
		terminal.raise(Terminal.Signal.INT); // the native SIGINT hook of a system terminal calls exactly this
		waitFor(interrupts, 2);
	}

	@Test
	void ctrlCDuringReadLineStillAbandonsTheLineOnly() throws Exception {
		AtomicInteger interrupts = new AtomicInteger();
		reader.setInterruptHandler(interrupts::incrementAndGet);
		AtomicReference<String> read = new AtomicReference<>();
		Thread prompt = new Thread(() -> read.set(reader.readLine("> ")));
		prompt.start();
		type("SELECT 1");
		Thread.sleep(200);
		keyboard.write(CTRL_C);
		keyboard.flush();
		prompt.join(10_000);
		Assertions.assertFalse(prompt.isAlive());
		Assertions.assertEquals("", read.get(), "CTRL+C at the prompt abandons the line, as before");
		Assertions.assertEquals(0, interrupts.get(), "the command handler is not called while reading");
		terminal.raise(Terminal.Signal.INT);
		waitFor(interrupts, 1);
	}
}
