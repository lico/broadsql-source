package com.upandcoding.broadsql.controller.shell.swing.api;

import javax.swing.Timer;

/**
 * The repeating poll behind {@link JApiSettingsFrame}'s dirty indicator (title "*" and the Save menu
 * item's enabled state), factored out of the frame so its lifecycle is explicit and unit-testable without
 * a display.
 *
 * <p>Why this must be stopped: a running {@link javax.swing.Timer} posts an event to the AWT event queue
 * every period, forever, and keeps the (disposed) frame reachable through its listener. That keeps AWT's
 * auto-shutdown from ever completing, so after {@code EXIT} the JVM stays alive although no window is
 * visible or even displayable. {@link JApiSettingsFrame#dispose()} therefore always calls {@link #stop()}.
 */
final class DirtyIndicatorPoller {

	private final Timer timer;

	DirtyIndicatorPoller(int periodMillis, Runnable tick) {
		this.timer = new Timer(periodMillis, e -> tick.run());
	}

	void start() {
		timer.start();
	}

	/** Idempotent; safe to call when never started or already stopped. */
	void stop() {
		timer.stop();
	}

	boolean isRunning() {
		return timer.isRunning();
	}
}
