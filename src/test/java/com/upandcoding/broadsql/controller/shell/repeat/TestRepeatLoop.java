package com.upandcoding.broadsql.controller.shell.repeat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.repeat.RepeatLoop.Ending;
import com.upandcoding.broadsql.controller.shell.repeat.RepeatLoop.Outcome;

/**
 * GitHub #196: the {@code REPEAT} timing rules, on a fake clock: the first iteration is immediate, {@code EVERY} is
 * waited after each completed iteration (never fixed-rate, never overlapping), {@code COUNT} runs exactly n iterations,
 * {@code FOR} starts no iteration at or after its deadline and never interrupts one, failure and cancellation end the loop.
 * No test sleeps.
 */
class TestRepeatLoop {

	/** A fake time line: iterations take {@code iterationMillis}, waits advance the clock exactly. */
	private static final class FakeTime {
		long now = 1_000_000;
		final List<long[]> iterations = new ArrayList<>(); // start, end
		final List<Long> waits = new ArrayList<>();
		long iterationMillis;
		int running;
		int maxRunning;

		final RepeatLoop.Clock clock = () -> now;
		final RepeatLoop.Sleeper sleeper = (millis, cancelled) -> {
			waits.add(millis);
			now += millis;
			return !cancelled.getAsBoolean();
		};

		boolean iterate() {
			running++;
			maxRunning = Math.max(maxRunning, running);
			long start = now;
			now += iterationMillis;
			iterations.add(new long[] { start, now });
			running--;
			return true;
		}

		List<Long> startOffsets(long origin) {
			List<Long> offsets = new ArrayList<>();
			for (long[] it : iterations) {
				offsets.add(it[0] - origin);
			}
			return offsets;
		}
	}

	private static RepeatLoop loop(FakeTime time, long every, Long forMillis, Integer count, AtomicBoolean cancelled) {
		return new RepeatLoop(every, forMillis, count, time.clock, time.sleeper, cancelled::get);
	}

	@Test
	void theFirstIterationIsImmediateAndCountRunsExactlyNIterations() {
		FakeTime time = new FakeTime();
		long origin = time.now;
		Outcome outcome = loop(time, 2000, null, 3, new AtomicBoolean()).run(n -> time.iterate(), new RepeatLoop.Listener() {
		});
		Assertions.assertEquals(new Outcome(Ending.COUNT_REACHED, 3), outcome);
		Assertions.assertEquals(List.of(0L, 2000L, 4000L), time.startOffsets(origin), "first immediate, then EVERY after each");
		Assertions.assertEquals(List.of(2000L, 2000L), time.waits, "no wait after the last iteration");
	}

	@Test
	void theWaitStartsAfterTheIterationCompletes() {
		FakeTime time = new FakeTime();
		time.iterationMillis = 3000;
		long origin = time.now;
		loop(time, 10_000, null, 3, new AtomicBoolean()).run(n -> time.iterate(), new RepeatLoop.Listener() {
		});
		Assertions.assertEquals(List.of(0L, 13_000L, 26_000L), time.startOffsets(origin), "3s of queries + EVERY 10s: a new start every 13s");
		for (int i = 1; i < time.iterations.size(); i++) {
			Assertions.assertTrue(time.iterations.get(i)[0] >= time.iterations.get(i - 1)[1], "iterations never overlap");
		}
		Assertions.assertEquals(1, time.maxRunning);
	}

	@Test
	void countOneRunsOnceWithoutWaiting() {
		FakeTime time = new FakeTime();
		Outcome outcome = loop(time, 60_000, null, 1, new AtomicBoolean()).run(n -> time.iterate(), new RepeatLoop.Listener() {
		});
		Assertions.assertEquals(new Outcome(Ending.COUNT_REACHED, 1), outcome);
		Assertions.assertTrue(time.waits.isEmpty());
	}

	@Test
	void forStartsNoIterationAtOrAfterTheDeadline() {
		FakeTime time = new FakeTime();
		long origin = time.now;
		Outcome outcome = loop(time, 10_000, 30_000L, null, new AtomicBoolean()).run(n -> time.iterate(), new RepeatLoop.Listener() {
		});
		Assertions.assertEquals(new Outcome(Ending.DURATION_ELAPSED, 3), outcome);
		Assertions.assertEquals(List.of(0L, 10_000L, 20_000L), time.startOffsets(origin));
		Assertions.assertEquals(List.of(10_000L, 10_000L), time.waits, "no useless wait once the next start would be past the deadline");
	}

	@Test
	void forNeverInterruptsAnIterationRunningAtTheDeadline() {
		FakeTime time = new FakeTime();
		time.iterationMillis = 25_000; // longer than the remaining time
		long origin = time.now;
		Outcome outcome = loop(time, 10_000, 30_000L, null, new AtomicBoolean()).run(n -> time.iterate(), new RepeatLoop.Listener() {
		});
		Assertions.assertEquals(new Outcome(Ending.DURATION_ELAPSED, 1), outcome, "the first iteration completed, the next would start after 35s");
		Assertions.assertEquals(25_000L, time.iterations.get(0)[1] - origin);
	}

	@Test
	void theFirstIterationAlwaysRunsEvenWithAShortFor() {
		FakeTime time = new FakeTime();
		Outcome outcome = loop(time, 10_000, 1_000L, null, new AtomicBoolean()).run(n -> time.iterate(), new RepeatLoop.Listener() {
		});
		Assertions.assertEquals(new Outcome(Ending.DURATION_ELAPSED, 1), outcome);
	}

	@Test
	void aFailedIterationEndsTheLoopWithoutWaiting() {
		FakeTime time = new FakeTime();
		AtomicInteger calls = new AtomicInteger();
		Outcome outcome = loop(time, 1000, null, 10, new AtomicBoolean()).run(n -> {
			calls.incrementAndGet();
			return n < 2;
		}, new RepeatLoop.Listener() {
		});
		Assertions.assertEquals(new Outcome(Ending.FAILED, 1), outcome);
		Assertions.assertEquals(2, calls.get(), "the failing iteration is not run again");
		Assertions.assertEquals(List.of(1000L), time.waits);
	}

	@Test
	void cancellationDuringTheWaitEndsTheLoop() {
		FakeTime time = new FakeTime();
		AtomicBoolean cancelled = new AtomicBoolean();
		RepeatLoop.Sleeper cancellingSleeper = (millis, isCancelled) -> {
			cancelled.set(true); // CTRL+C pressed while waiting
			return !isCancelled.getAsBoolean();
		};
		Outcome outcome = new RepeatLoop(1000, null, null, time.clock, cancellingSleeper, cancelled::get).run(n -> time.iterate(), new RepeatLoop.Listener() {
		});
		Assertions.assertEquals(new Outcome(Ending.CANCELLED, 1), outcome);
	}

	@Test
	void cancellationDuringAnIterationDoesNotCountItAndStopsAtOnce() {
		FakeTime time = new FakeTime();
		AtomicBoolean cancelled = new AtomicBoolean();
		Outcome outcome = loop(time, 1000, null, null, cancelled).run(n -> {
			if (n == 3) {
				cancelled.set(true);
			}
			return true;
		}, new RepeatLoop.Listener() {
		});
		Assertions.assertEquals(new Outcome(Ending.CANCELLED, 2), outcome);
		Assertions.assertEquals(2, time.waits.size());
	}

	@Test
	void theListenerIsToldTheDurationAndTheNextWait() {
		FakeTime time = new FakeTime();
		time.iterationMillis = 842;
		List<String> events = new ArrayList<>();
		loop(time, 5000, null, 2, new AtomicBoolean()).run(n -> time.iterate(), new RepeatLoop.Listener() {
			@Override
			public void iterationCompleted(int number, long elapsedMillis, long nextWaitMillis) {
				events.add(number + ":" + elapsedMillis + ":" + nextWaitMillis);
			}
		});
		Assertions.assertEquals(List.of("1:842:5000", "2:842:-1"), events);
	}

	@Test
	void theSystemSleeperReturnsEarlyWhenCancelled() {
		AtomicBoolean cancelled = new AtomicBoolean(true);
		long start = System.nanoTime();
		Assertions.assertFalse(RepeatLoop.SYSTEM_SLEEPER.sleep(60_000, cancelled::get));
		Assertions.assertTrue(System.nanoTime() - start < 5_000_000_000L);
		Assertions.assertTrue(RepeatLoop.SYSTEM_SLEEPER.sleep(1, () -> false));
	}
}
