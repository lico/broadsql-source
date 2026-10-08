package com.upandcoding.broadsql.controller.shell.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GitHub #196: an H2 function ({@code CREATE ALIAS REC FOR '...RepeatProbe.rec'}) recording, in order, each query
 * that calls it, and how many ran at the same time, so the {@code REPEAT} tests see exactly what executed.
 */
public final class RepeatProbe {

	private static final List<String> CALLS = Collections.synchronizedList(new ArrayList<>());
	private static final AtomicInteger RUNNING = new AtomicInteger();
	private static volatile int maxRunning;

	private RepeatProbe() {
	}

	public static String rec(String label) throws InterruptedException {
		int running = RUNNING.incrementAndGet();
		try {
			maxRunning = Math.max(maxRunning, running);
			CALLS.add(label);
			Thread.sleep(2); // a little time during which a concurrent call would be seen
			return label;
		} finally {
			RUNNING.decrementAndGet();
		}
	}

	public static List<String> calls() {
		synchronized (CALLS) {
			return new ArrayList<>(CALLS);
		}
	}

	public static int maxRunning() {
		return maxRunning;
	}

	public static void reset() {
		CALLS.clear();
		maxRunning = 0;
	}
}
