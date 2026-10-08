package com.upandcoding.broadsql.controller.shell.repeat;

import java.util.function.BooleanSupplier;

/**
 * GitHub #196: the timing rules of {@code REPEAT}, separated from execution so they can be tested with a fake
 * clock and sleeper:
 * <ul>
 * <li>the first iteration starts immediately;</li>
 * <li>{@code EVERY} is the wait <b>after</b> an iteration has completed, so iterations never overlap and the
 * period is the iteration's own duration plus {@code EVERY} (not fixed-rate scheduling);</li>
 * <li>{@code COUNT n} runs at most {@code n} complete iterations;</li>
 * <li>{@code FOR d} is measured from the start of the first iteration: an iteration is started only before that
 * deadline. An iteration still running at the deadline is never interrupted; when the next start would fall at or
 * after the deadline, the loop ends at once instead of waiting for nothing;</li>
 * <li>a failed iteration ends the loop (fail-fast), and so does cancellation (CTRL+C), checked before each
 * iteration, after it, and throughout the wait.</li>
 * </ul>
 */
public final class RepeatLoop {

	/** The time source: milliseconds, only differences matter. */
	@FunctionalInterface
	public interface Clock {
		long nowMillis();
	}

	/** Waits; implementations return early, with {@code false}, as soon as {@code cancelled} becomes true. */
	@FunctionalInterface
	public interface Sleeper {
		/** @return {@code true} when the whole wait elapsed, {@code false} when it was cancelled */
		boolean sleep(long millis, BooleanSupplier cancelled);
	}

	/** One iteration's body. */
	@FunctionalInterface
	public interface Iteration {
		/**
		 * @param number the iteration number, from 1
		 * @return {@code true} when the iteration succeeded, {@code false} when it failed (the loop ends)
		 */
		boolean run(int number);
	}

	/** Told what happens between iterations (console messages); nothing by default. */
	public interface Listener {
		/** The iteration completed in {@code elapsedMillis}; {@code nextWaitMillis} is the coming wait, or -1 when no iteration follows. */
		default void iterationCompleted(int number, long elapsedMillis, long nextWaitMillis) {
		}
	}

	/** Why the loop ended. */
	public enum Ending {
		/** {@code COUNT} iterations ran. */
		COUNT_REACHED,
		/** The {@code FOR} deadline was reached. */
		DURATION_ELAPSED,
		/** An iteration failed. */
		FAILED,
		/** CTRL+C. */
		CANCELLED
	}

	/** How the loop ended, and after how many complete iterations. */
	public record Outcome(Ending ending, int iterations) {
	}

	/** Real time, and a wait that checks for cancellation every {@value #POLL_MILLIS} ms. */
	public static final Clock SYSTEM_CLOCK = System::currentTimeMillis;
	public static final long POLL_MILLIS = 100;
	public static final Sleeper SYSTEM_SLEEPER = (millis, cancelled) -> {
		long deadline = System.currentTimeMillis() + millis;
		while (!cancelled.getAsBoolean()) {
			long left = deadline - System.currentTimeMillis();
			if (left <= 0) {
				return true;
			}
			try {
				Thread.sleep(Math.min(left, POLL_MILLIS));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	};

	private final long everyMillis;
	private final Long forMillis;
	private final Integer count;
	private final Clock clock;
	private final Sleeper sleeper;
	private final BooleanSupplier cancelled;

	/**
	 * @param forMillis {@code null} without {@code FOR}
	 * @param count     {@code null} without {@code COUNT}
	 */
	public RepeatLoop(long everyMillis, Long forMillis, Integer count, Clock clock, Sleeper sleeper, BooleanSupplier cancelled) {
		this.everyMillis = everyMillis;
		this.forMillis = forMillis;
		this.count = count;
		this.clock = clock;
		this.sleeper = sleeper;
		this.cancelled = cancelled;
	}

	public Outcome run(Iteration iteration, Listener listener) {
		long start = clock.nowMillis();
		int completed = 0;
		while (true) {
			if (cancelled.getAsBoolean()) {
				return new Outcome(Ending.CANCELLED, completed);
			}
			long iterationStart = clock.nowMillis();
			boolean succeeded = iteration.run(completed + 1);
			long iterationEnd = clock.nowMillis();
			if (cancelled.getAsBoolean()) {
				// cancelled during the iteration: it is not a complete one
				return new Outcome(Ending.CANCELLED, completed);
			}
			if (!succeeded) {
				return new Outcome(Ending.FAILED, completed);
			}
			completed++;
			Ending ending = null;
			if (count != null && completed >= count) {
				ending = Ending.COUNT_REACHED;
			} else if (forMillis != null && iterationEnd + everyMillis >= start + forMillis) {
				// the next iteration would start at or after the deadline
				ending = Ending.DURATION_ELAPSED;
			}
			listener.iterationCompleted(completed, iterationEnd - iterationStart, ending == null ? everyMillis : -1);
			if (ending != null) {
				return new Outcome(ending, completed);
			}
			if (!sleeper.sleep(everyMillis, cancelled)) {
				return new Outcome(Ending.CANCELLED, completed);
			}
		}
	}
}
