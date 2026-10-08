package com.upandcoding.broadsql.controller.shell.swing.api;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * SPRINT XT02B, section 2/5 - {@code JApiSettingsFrame} itself cannot be constructed in this project's
 * headless test runs: unlike a plain {@code JPanel} (which every other {@code CONFIG API} panel test in
 * this package constructs directly), {@code JFrame}'s own constructor calls
 * {@code GraphicsEnvironment.checkHeadless()} and throws {@link java.awt.HeadlessException} immediately -
 * confirmed empirically (a first version of this test tried exactly that and failed on construction,
 * before any menu/dialog code ever ran). So the frame's menu wiring, the tab-domain-switch
 * {@code ChangeListener}, the title/Save-menu-item dirty indicator, and the {@code UnsavedChangesDialog}
 * round trip are all genuinely unverifiable here - they are covered in the sprint's manual-verification
 * checklist instead ({@code docs/TECHNICAL_CHANGE.md}, closure entry for this sprint).
 *
 * <p>What this class does verify is {@link JApiSettingsFrame#tabDomain(int)} - the pure, static grouping
 * function the {@code ChangeListener} uses to decide whether a tab switch crosses a staged-edit domain
 * boundary - since it needs no {@code JApiSettingsFrame} instance to call.
 */
class TestJApiSettingsFrame {

	@Test
	void tabDomainGroupsGeneralAuthenticationAndVariablesHeadersTogetherAndKeepsEnvironmentsAndEndpointsSeparate() throws Exception {
		Assertions.assertEquals(tabDomain(0), tabDomain(2), "General and Authentication must be the same staged-edit domain");
		Assertions.assertEquals(tabDomain(0), tabDomain(3), "General and Variables & Headers must be the same staged-edit domain");
		Assertions.assertNotEquals(tabDomain(0), tabDomain(1), "Environments must be its own staged-edit domain");
		Assertions.assertNotEquals(tabDomain(0), tabDomain(4), "Endpoints must be its own staged-edit domain");
		Assertions.assertNotEquals(tabDomain(1), tabDomain(4), "Environments and Endpoints must be different staged-edit domains");
	}

	private static int tabDomain(int tabIndex) throws Exception {
		Method method = JApiSettingsFrame.class.getDeclaredMethod("tabDomain", int.class);
		method.setAccessible(true);
		return (int) method.invoke(null, tabIndex);
	}
}
