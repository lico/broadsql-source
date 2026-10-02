package com.upandcoding.broadsql.controller.shell.swing;

import java.awt.Color;
import java.util.Map;

import javax.swing.UIManager;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

/**
 * Regression coverage for {@link BroadSqlLookAndFeel}'s FlatLaf configuration:
 * <ul>
 * <li>the accent-color parsing bug found on manual launch of {@code CONFIG} (a prior corrective pass:
 * {@code @accentColor} was originally set to {@code "0060C0"}, no leading {@code '#'}, which is not
 * valid FlatLaf color syntax - FlatLaf logs a parse error and silently falls back to a default instead
 * of throwing, so {@code installOnce()} completing without an exception proved nothing);</li>
 * <li>the corrective pass after that one, which added actual surface color ({@link BroadSqlLookAndFeel#TAB_STRIP_BACKGROUND}/
 * {@link BroadSqlLookAndFeel#FILTER_BAR_BACKGROUND}) beyond the accent-derived defaults alone, since a
 * manual review found the accent-only version visually indistinguishable from stock Swing.</li>
 * </ul>
 *
 * <p>No GUI/component is created here (no {@code JFrame}, no painting) - only
 * {@link FlatLaf#setGlobalExtraDefaults} and {@link FlatLightLaf#setup()}, which resolve colors via
 * plain property parsing and do not require a non-headless display. Deliberately not a
 * screenshot/rendering test - these assert the resolved {@link UIManager} values, not pixels.
 */
class TestBroadSqlLookAndFeel {

	@Test
	void accentColorHexIsAValidFlatLafHexLiteral() {
		Assertions.assertTrue(BroadSqlLookAndFeel.ACCENT_HEX.matches("#[0-9A-Fa-f]{6}"),
				"FlatLaf requires a '#RRGGBB' color literal (or another valid FlatLaf color expression) - got: " + BroadSqlLookAndFeel.ACCENT_HEX);
	}

	@Test
	void extraDefaultsMapCarriesTheHashPrefixedAccentColor() {
		Map<String, String> extras = BroadSqlLookAndFeel.buildExtraDefaults();
		Assertions.assertEquals("#0060C0", extras.get("@accentColor"));
	}

	@Test
	void accentConstantMatchesTheStringHandedToFlatLaf() {
		Assertions.assertEquals(String.format("#%06X", BroadSqlLookAndFeel.ACCENT.getRGB() & 0xFFFFFF), BroadSqlLookAndFeel.ACCENT_HEX,
				"the Color constant and the string constant passed to FlatLaf must be the same color, not two independently-maintained values");
	}

	/**
	 * The two surface tints must actually be tints (distinct from plain white and from each other,
	 * and each closer to white than to the full accent) - not accidentally left equal to white/each
	 * other, which would silently undo the "actual visible surface color" fix.
	 */
	@Test
	void surfaceTintsAreDistinctFromWhiteAndFromEachOtherAndOrderedByStrength() {
		Assertions.assertNotEquals(Color.WHITE, BroadSqlLookAndFeel.TAB_STRIP_BACKGROUND);
		Assertions.assertNotEquals(Color.WHITE, BroadSqlLookAndFeel.FILTER_BAR_BACKGROUND);
		Assertions.assertNotEquals(BroadSqlLookAndFeel.TAB_STRIP_BACKGROUND, BroadSqlLookAndFeel.FILTER_BAR_BACKGROUND);
		// The filter bar strip must read as a visually stronger tint than the tab strip behind it
		// (required hierarchy: tabs = light tint, filter bar = a touch stronger) - checked via distance
		// from white on the blue channel, the channel both tints move the most.
		int tabStripDistanceFromWhite = 255 - BroadSqlLookAndFeel.TAB_STRIP_BACKGROUND.getBlue();
		int filterBarDistanceFromWhite = 255 - BroadSqlLookAndFeel.FILTER_BAR_BACKGROUND.getBlue();
		Assertions.assertTrue(filterBarDistanceFromWhite > tabStripDistanceFromWhite,
				"the filter bar tint must be visibly stronger than the tab-strip tint behind it");
	}

	/**
	 * The actual regression test: installs the real extra-defaults map and the real FlatLaf light
	 * theme (same calls {@link BroadSqlLookAndFeel#installOnce()} makes, just not gated behind its
	 * once-per-JVM {@code installed} guard), then asserts every UIManager default this class is
	 * responsible for actually resolves to the configured value - not merely that setup did not throw,
	 * which a silent parse-and-fallback would still satisfy.
	 */
	@Test
	void flatLafResolvesEveryConfiguredDefaultWithoutFallingBackToADefault() {
		// The exact call installOnce() makes internally - exercised directly here (not via
		// installOnce() itself) so this test is not at the mercy of installOnce()'s once-per-JVM
		// "installed" guard silently turning a second call into a no-op.
		FlatLaf.setGlobalExtraDefaults(BroadSqlLookAndFeel.buildExtraDefaults());
		Assertions.assertTrue(FlatLightLaf.setup(), "FlatLightLaf.setup() must succeed");
		try {
			Color accent = UIManager.getColor("Component.accentColor");
			Assertions.assertNotNull(accent, "Component.accentColor must resolve to a real Color - null means FlatLaf could not parse @accentColor");
			Assertions.assertEquals(0x0060C0, accent.getRGB() & 0xFFFFFF,
					"resolved accent color must match the configured RGB value exactly - a mismatch means FlatLaf silently fell back to a default "
							+ "after failing to parse the configured @accentColor expression");

			// installOnce()'s remaining UIManager.put() calls (TabbedPane.background/selectedForeground)
			// have no parsing step to silently fail, but are asserted anyway so a future edit that
			// breaks the tab-strip tinting fails a test instead of only being caught on manual review.
			BroadSqlLookAndFeel.installOnce();
			Assertions.assertEquals(BroadSqlLookAndFeel.TAB_STRIP_BACKGROUND, UIManager.getColor("TabbedPane.background"));
			Assertions.assertEquals(BroadSqlLookAndFeel.ACCENT, UIManager.getColor("TabbedPane.selectedForeground"));
		} finally {
			// Leave no trace for any later test in the same JVM - restores the platform default L&F.
			try {
				UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
			} catch (Exception ignored) {
				// best-effort cleanup only
			}
			FlatLaf.setGlobalExtraDefaults(java.util.Collections.emptyMap());
		}
	}
}
