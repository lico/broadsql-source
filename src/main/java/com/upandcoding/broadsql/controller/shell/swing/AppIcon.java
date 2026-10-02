package com.upandcoding.broadsql.controller.shell.swing;

import java.awt.Image;
import java.awt.Toolkit;
import java.net.URL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the BroadSQL/UpAndCoding application icon for top-level Swing windows, from a packaged
 * classpath resource - never from a {@code release/site} filesystem path, so it keeps working from
 * the built JAR regardless of working directory (SPRINT 0911D, requirement 22: "the Settings window
 * currently displays the default Java icon").
 *
 * <p>The source artwork ({@code src/main/resources/icons/broadsql.png}) is a copy of the same
 * {@code upandcoding.png} used as the site favicon (see {@code releases/site-build/nav.json}) - the
 * one authoritative brand mark already in the repository, not a second, divergent copy of BroadSQL
 * branding.
 *
 * <p>Deliberately a small, reusable, frame-agnostic loader (a {@link #load()} call, not a mixin or
 * base class) rather than something wired only into {@link JSettingsFrame}. Widened from
 * package-private to {@code public} (API Quality and UX Consolidation sprint, Phase 2) so
 * {@code com.upandcoding.broadsql.controller.shell.swing.api.JApiSettingsFrame} - the API Configuration
 * window - can reuse the exact same lookup instead of duplicating it or hardcoding a second path to
 * the same resource; {@link JSettingsFrame} is no longer the only top-level {@code JFrame} using it.
 */
public final class AppIcon {

	private static final Logger log = LoggerFactory.getLogger(AppIcon.class);

	private static final String ICON_RESOURCE = "/icons/broadsql.png";

	private AppIcon() {
	}

	/**
	 * @return the application icon image, or {@code null} if the packaged resource could not be found
	 *         or loaded (logged, never thrown - a missing icon must never prevent a window from
	 *         opening).
	 */
	public static Image load() {
		try {
			URL resource = AppIcon.class.getResource(ICON_RESOURCE);
			if (resource == null) {
				log.warn("Application icon resource not found on classpath: {}", ICON_RESOURCE);
				return null;
			}
			return Toolkit.getDefaultToolkit().getImage(resource);
		} catch (Exception ex) {
			log.warn("Could not load application icon: {}", ex.getLocalizedMessage());
			return null;
		}
	}
}
