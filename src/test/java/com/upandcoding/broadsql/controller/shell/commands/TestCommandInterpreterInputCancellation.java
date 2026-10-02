package com.upandcoding.broadsql.controller.shell.commands;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.controller.shell.reader.JLineConsoleLineReader;

/**
 * SPRINT 2209C (GitHub #150): Esc must abandon a statement typed over several lines, not only the line
 * JLine is editing. The earlier lines live in {@code CommandInterpreter.run()}'s own accumulation buffer;
 * {@code run()} passes that buffer through {@link CommandInterpreter#discardPendingStatementIfInputCancelled}
 * right after each read. This drives that exact step with real keystrokes through a JLine reader installed
 * in a {@link CapturingShellConsole}, the way {@code BroadSQL.main} installs one.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class TestCommandInterpreterInputCancellation {

	private static final Object STOP = new Object();

	private final PipedOutputStream keyboard = new PipedOutputStream();
	private final LinkedBlockingQueue<Object> steps = new LinkedBlockingQueue<>();
	private CapturingShellConsole console;

	@BeforeEach
	void setUp() throws IOException {
		PipedInputStream in = new PipedInputStream(keyboard, 4096);
		console = new CapturingShellConsole();
		console.setLineReader(JLineConsoleLineReader.createForTestingWithRealKeyBindings(in, new ByteArrayOutputStream(), null, null, new CommandList(), null,
				"windows-vtp"));
		Thread feeder = new Thread(() -> {
			try {
				while (true) {
					Object step = steps.take();
					if (step == STOP) {
						return;
					}
					if (step instanceof Long pause) {
						Thread.sleep(pause);
					} else {
						keyboard.write(((String) step).getBytes(StandardCharsets.UTF_8));
						keyboard.flush();
					}
				}
			} catch (InterruptedException | IOException e) {
				// test finished
			}
		}, "key-feeder");
		feeder.setDaemon(true);
		feeder.start();
	}

	@AfterEach
	void tearDown() {
		steps.add(STOP);
		console.close();
	}

	/** Mirrors {@code run()}: read, then let an Esc during that read drop the accumulated statement. */
	private StringBuilder readInto(StringBuilder pending) {
		String line = console.readLine();
		StringBuilder result = CommandInterpreter.discardPendingStatementIfInputCancelled(console, pending);
		result.append(line).append(' ');
		return result;
	}

	@Test
	void escOnALaterLineAbandonsTheWholeMultiLineStatement() {
		steps.add("SELECT a,\r");
		steps.add("       b\r");
		steps.add("FROM CUSTOMER");
		steps.add("\u001b");
		steps.add(400L);
		steps.add("SHOW TABLES;\r");

		StringBuilder pending = new StringBuilder();
		pending = readInto(pending);
		pending = readInto(pending);
		Assertions.assertEquals("SELECT a,        b ", pending.toString(), "control: lines accumulate until Esc");

		pending = readInto(pending);
		Assertions.assertEquals("SHOW TABLES; ", pending.toString(), "only what was typed after Esc remains");
	}

	@Test
	void withoutEscTheStatementKeepsAccumulating() {
		steps.add("SELECT a\r");
		steps.add("FROM CUSTOMER;\r");

		StringBuilder pending = readInto(new StringBuilder());
		pending = readInto(pending);
		Assertions.assertEquals("SELECT a FROM CUSTOMER; ", pending.toString());
	}
}
