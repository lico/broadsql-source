package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Headless (no {@link javax.swing.JFrame} constructed anywhere) - see the plan's "Headless vs. real GUI verification." */
class TestScriptLibrarySession {

	@Test
	void repeatedOpenRequestsForTheSameAssetReuseTheSameTab() {
		ScriptLibrarySession session = new ScriptLibrarySession();

		boolean firstCreated = session.openOrFocus("asset-1", "customer.sql");
		boolean secondCreated = session.openOrFocus("asset-1", "customer.sql");

		Assertions.assertTrue(firstCreated);
		Assertions.assertFalse(secondCreated, "a second open request for the same asset must never duplicate a tab");
		Assertions.assertEquals(1, session.tabCount());
	}

	@Test
	void distinctAssetsGetDistinctTabs() {
		ScriptLibrarySession session = new ScriptLibrarySession();

		session.openOrFocus("asset-1", "a.sql");
		session.openOrFocus("asset-2", "b.sql");

		Assertions.assertEquals(2, session.tabCount());
		Assertions.assertEquals("asset-2", session.activeAssetId());
	}

	@Test
	void openOrFocusAlwaysMakesTheAssetActive() {
		ScriptLibrarySession session = new ScriptLibrarySession();
		session.openOrFocus("asset-1", "a.sql");
		session.openOrFocus("asset-2", "b.sql");

		session.openOrFocus("asset-1", "a.sql");

		Assertions.assertEquals("asset-1", session.activeAssetId());
	}

	@Test
	void dirtyTrackingPerTab() {
		ScriptLibrarySession session = new ScriptLibrarySession();
		session.openOrFocus("asset-1", "a.sql");
		session.openOrFocus("asset-2", "b.sql");

		session.setDirty("asset-1", true);

		Assertions.assertTrue(session.isDirty("asset-1"));
		Assertions.assertFalse(session.isDirty("asset-2"));
		Assertions.assertTrue(session.isAnyDirty());
		Assertions.assertEquals(java.util.List.of("asset-1"), session.dirtyAssetIds());
	}

	@Test
	void closingATabRemovesItAndReassignsActiveIfNeeded() {
		ScriptLibrarySession session = new ScriptLibrarySession();
		session.openOrFocus("asset-1", "a.sql");
		session.openOrFocus("asset-2", "b.sql");

		session.closeTab("asset-2");

		Assertions.assertFalse(session.hasTab("asset-2"));
		Assertions.assertEquals("asset-1", session.activeAssetId());
		Assertions.assertEquals(1, session.tabCount());
	}

	@Test
	void hidingTheWorkspaceNeverClearsOpenTabsOrDirtyState() {
		ScriptLibrarySession session = new ScriptLibrarySession();
		session.markOpen();
		session.openOrFocus("asset-1", "a.sql");
		session.setDirty("asset-1", true);

		// Window X (hide) never calls markClosed()/closeTab() - it only affects Swing visibility, never
		// this session's tab/dirty state. Simulate that by simply NOT touching the session here.

		Assertions.assertTrue(session.hasTab("asset-1"));
		Assertions.assertTrue(session.isDirty("asset-1"));
	}
}
