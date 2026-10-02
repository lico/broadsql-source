package com.upandcoding.broadsql.controller.shell.swing.api;

import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;

/**
 * EXIT hang after {@code CONFIG API}: {@link JApiSettingsFrame} closed and was even disposed, but its
 * repeating dirty-indicator {@link javax.swing.Timer} kept running, which keeps AWT (and so the JVM)
 * alive after {@code EXIT}. The lifecycle decision is factored into {@link DirtyIndicatorPoller} and
 * {@code JApiSettingsFrame.dispose()} so it is testable here without a display; the real-frame test runs
 * only where a display exists (Surefire is headless), and manual acceptance is still required for the
 * native Windows behavior.
 */
class TestJApiSettingsFrameLifecycle {

	@Test
	void pollerRunsOnlyBetweenStartAndStopAndStopIsIdempotent() {
		DirtyIndicatorPoller poller = new DirtyIndicatorPoller(500, () -> { });
		Assertions.assertFalse(poller.isRunning(), "not running before start");

		poller.stop(); // stop before start must be harmless
		poller.start();
		Assertions.assertTrue(poller.isRunning());

		poller.stop();
		Assertions.assertFalse(poller.isRunning());
		poller.stop();
		Assertions.assertFalse(poller.isRunning(), "a second stop is harmless");
	}

	@Test
	void theFrameOwnsItsPollerAndStopsItInDispose() throws Exception {
		Method dispose = JApiSettingsFrame.class.getDeclaredMethod("dispose");
		Assertions.assertEquals(JApiSettingsFrame.class, dispose.getDeclaringClass(),
				"JApiSettingsFrame must override dispose() so every close path stops the poll");
		Field poller = JApiSettingsFrame.class.getDeclaredField("dirtyIndicatorPoller");
		Assertions.assertEquals(DirtyIndicatorPoller.class, poller.getType(), "the timer must be kept in a field so it can be stopped, not left as an unreachable local");
	}

	@Test
	void noTimerIsLeftRunningAndTheFrameIsNotDisplayableAfterTheUserClosesIt() throws Exception {
		Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display; Surefire runs headless");
		JApiSettingsFrame[] holder = new JApiSettingsFrame[1];
		SwingUtilities.invokeAndWait(() -> {
			try {
				JApiSettingsFrame frame = new JApiSettingsFrame();
				frame.setApiDefinitionsVault(TestApiDefinitionsVaults.newFileBackedVault());
				frame.initApp();
				frame.setVisible(true);
				holder[0] = frame;
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		Field pollerField = JApiSettingsFrame.class.getDeclaredField("dirtyIndicatorPoller");
		pollerField.setAccessible(true);
		DirtyIndicatorPoller poller = (DirtyIndicatorPoller) pollerField.get(holder[0]);
		Assertions.assertTrue(poller.isRunning(), "the poll runs while the window is open");

		// exactly what the window's X button does
		SwingUtilities.invokeAndWait(() -> holder[0].dispatchEvent(new WindowEvent(holder[0], WindowEvent.WINDOW_CLOSING)));

		Assertions.assertFalse(holder[0].isDisplayable());
		Assertions.assertFalse(poller.isRunning(), "closing the window must stop the poll, or the JVM never exits");
	}
}
