package com.upandcoding.broadsql.controller.shell.swing;

import java.awt.Color;
import java.awt.Font;
import java.util.HashMap;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

/**
 * Central, one-time Swing Look &amp; Feel initialization and BroadSQL visual language for every
 * Settings window (SPRINT 0911D GUI polish, corrective pass). Installs FlatLaf's light theme plus a
 * BroadSQL-specific accent color and a small set of derived surface tints, applied through normal
 * Swing {@code setBackground}/{@code setForeground} and FlatLaf {@link UIManager} keys - never custom
 * painting.
 *
 * <p><b>Why the first pass (accent color alone) looked almost unchanged</b>: {@code @accentColor}
 * only reaches the handful of FlatLaf defaults that reference it in {@code FlatLightLaf.properties} -
 * the tab underline, radio/checkbox marks, focus rings, links and table/list selection. Every one of
 * those is a small, low-area UI element; nothing about panel backgrounds, headers, or section framing
 * changes just from setting {@code @accentColor}, so the screen's overall visual weight stayed
 * essentially default Swing grey/white/black. This class now also colors actual surface area - the
 * tab strip background and each tab's filter/header strip - via {@link #TAB_STRIP_BACKGROUND}/
 * {@link #FILTER_BAR_BACKGROUND}, both derived from {@link #ACCENT} by blending toward white
 * ({@link #tint}), plus a bolder, accent-colored section title style ({@link #sectionTitleLabel}).
 *
 * <p>Called once, centrally, from {@link JSettingsFrame#initApp()} - the only place that used to call
 * {@code FlatLightLaf.setup()} directly - rather than from each panel (requirement 12: "initialize the
 * L&amp;F centrally rather than independently in individual panels"). The styling helpers below
 * ({@link #styleFilterBar}, {@link #sectionTitleLabel}) are called from {@link SettingsFilterBar}, the
 * one shared header component all three Settings tabs use, so the palette lives in exactly one place
 * rather than being scattered as magic RGB values across three panels (requirement 8).
 *
 * <p>The class itself and {@link #installOnce()} are {@code public} (SPRINT XT02 sub-sprint 5) so
 * {@code controller.shell.swing.api.JApiSettingsFrame} can reuse this exact installation - see
 * {@link #installOnce()}'s own javadoc. {@link #ACCENT}, {@link #sectionTitleLabel(String)} and
 * {@link #styleFilterBar(JComponent)} are also {@code public} (SPRINT 0917-01, same precedent) so
 * {@code controller.shell.swing.scriptlibrary}'s Script Library GUI reuses the exact same
 * section-title/filter-bar treatment and accent color rather than picking its own. Every other
 * member stays package-private.
 */
public final class BroadSqlLookAndFeel {

	/**
	 * Sampled empirically (a small throwaway pixel-histogram program, run against the packaged icon,
	 * not eyeballed) from {@code src/main/resources/icons/broadsql.png} - the same authoritative brand
	 * mark {@link AppIcon} uses for the window icon, so the accent color and the window icon are
	 * visually the same blue rather than two independently-chosen ones. The icon's dominant blue band
	 * spans roughly {@code #0040B0}-{@code #0088F8}; this is a representative mid-tone within it.
	 */
	public static final Color ACCENT = new Color(0x0060C0);

	/**
	 * The exact string handed to FlatLaf's {@code @accentColor} global default - <b>must</b> include
	 * the leading {@code '#'} (FlatLaf's color parser requires a {@code #RRGGBB} literal or another
	 * valid color expression; a bare hex string is neither and fails to parse at runtime - the exact
	 * bug a prior corrective pass fixed and regression-tested, see {@code TestBroadSqlLookAndFeel}).
	 */
	static final String ACCENT_HEX = "#0060C0";

	/**
	 * Backdrop behind the top-level tab strip (Connections/Database Groups/Environments) and the
	 * Connection/Login Scripts sub-tab strip - installed as the global {@code TabbedPane.background}
	 * default (FlatLaf's {@code *.background = @background} wildcard is what {@code TabbedPane.background}
	 * would otherwise fall back to, confirmed by inspecting {@code FlatLaf.properties}), so every
	 * {@code JTabbedPane} in this application picks it up automatically - one place, not per-component
	 * styling. A light tint, not a filled color: the tab *content* underneath stays neutral/white,
	 * matching "content/form area: neutral white/light surface".
	 */
	static final Color TAB_STRIP_BACKGROUND = tint(0.06f);

	/**
	 * Backdrop for each tab's filter/header strip ({@link SettingsFilterBar}) - a touch stronger than
	 * {@link #TAB_STRIP_BACKGROUND} so the strip reads as its own distinct band between the tabs and
	 * the neutral content area below it, per the required visual hierarchy: menu (neutral) -&gt; tabs
	 * (light tint) -&gt; filter bar (stronger tint) -&gt; content (neutral).
	 */
	static final Color FILTER_BAR_BACKGROUND = tint(0.11f);

	/** A slightly darker shade of {@link #ACCENT}, used for section-title text so it reads clearly against {@link #FILTER_BAR_BACKGROUND}. */
	static final Color SECTION_TITLE_FOREGROUND = ACCENT.darker();

	private static volatile boolean installed;

	private BroadSqlLookAndFeel() {
	}

	/** Blends {@link #ACCENT} into white by {@code amount} (0..1) - the one place every derived surface tint is computed, so none of it is a hand-picked, undocumented hex value. */
	private static Color tint(float amount) {
		int r = Math.round(255 * (1 - amount) + ACCENT.getRed() * amount);
		int g = Math.round(255 * (1 - amount) + ACCENT.getGreen() * amount);
		int b = Math.round(255 * (1 - amount) + ACCENT.getBlue() * amount);
		return new Color(r, g, b);
	}

	/**
	 * Public (SPRINT XT02 sub-sprint 5) so {@code controller.shell.swing.api.JApiSettingsFrame} - a
	 * sibling top-level frame in its own package, opened directly by {@code CONFIG API} without ever
	 * requiring {@code CONFIG} (this class {@link JSettingsFrame}) to have run first - can install the
	 * exact same Look &amp; Feel rather than duplicating this logic in a second class, per the sprint
	 * spec's "Use the same Swing/FlatLaf visual conventions as BroadSQL's existing configuration GUI."
	 * Idempotent either way ({@link #installed} guards it), so calling it from both entry points is safe.
	 */
	public static synchronized void installOnce() {
		if (installed) {
			return;
		}
		FlatLaf.setGlobalExtraDefaults(buildExtraDefaults());
		FlatLightLaf.setup();
		applyBroadSqlDefaults();
		installed = true;
	}// installOnce

	/**
	 * Extracted from {@link #installOnce()} purely so a test can exercise the exact map passed to
	 * {@link FlatLaf#setGlobalExtraDefaults} - and, separately, actually install it and verify FlatLaf
	 * resolves {@code @accentColor} without a parse failure - without depending on {@link #installOnce()}'s
	 * once-per-JVM {@code installed} guard (which would make a second, independent test invocation a
	 * silent no-op).
	 */
	static Map<String, String> buildExtraDefaults() {
		Map<String, String> extras = new HashMap<>();
		extras.put("@accentColor", ACCENT_HEX);
		return extras;
	}// buildExtraDefaults

	/**
	 * The defaults that give the screen actual visible surface color, beyond what {@code @accentColor}
	 * alone reaches (see this class's javadoc for why that alone was not enough): a tinted tab-strip
	 * background (confirmed a real, honored key - {@code TabbedPane.background} - by inspecting
	 * {@code FlatLaf.properties}' {@code *.background} wildcard fallback), the selected tab's text
	 * colored to match its underline (not defined by FlatLightLaf.properties by default, confirmed via
	 * its {@code $?TabbedPane.selectedForeground} - optional-reference syntax - elsewhere in that same
	 * file), and a touch more tab-strip height (requirement 6).
	 */
	private static void applyBroadSqlDefaults() {
		UIManager.put("TabbedPane.tabHeight", 34);
		UIManager.put("TabbedPane.background", TAB_STRIP_BACKGROUND);
		UIManager.put("TabbedPane.selectedForeground", ACCENT);
	}// applyBroadSqlDefaults

	/**
	 * Applies the filter/header strip's tinted background - plain {@code setBackground}/{@code setOpaque},
	 * not custom painting. Used only by {@link SettingsFilterBar}, so every tab's strip looks identical
	 * (requirement 3: "Use the same visual treatment on all three tabs").
	 */
	public static void styleFilterBar(JComponent bar) {
		bar.setOpaque(true);
		bar.setBackground(FILTER_BAR_BACKGROUND);
	}// styleFilterBar

	/**
	 * A section title with "stronger visual emphasis" (bold, accent-tinted text) - used for the title
	 * label inside {@link SettingsFilterBar}, and available to any panel that needs the same treatment
	 * elsewhere, so the look stays centralized rather than re-picked per panel.
	 */
	public static JLabel sectionTitleLabel(String text) {
		JLabel label = new JLabel(text);
		label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
		label.setForeground(SECTION_TITLE_FOREGROUND);
		return label;
	}// sectionTitleLabel
}
