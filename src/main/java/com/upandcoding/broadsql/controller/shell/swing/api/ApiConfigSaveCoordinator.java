package com.upandcoding.broadsql.controller.shell.swing.api;

import java.util.function.BooleanSupplier;

/**
 * SPRINT XT02B, section 2/5: the one place {@code API &gt; Save}, {@code Ctrl+S}, and the toolbar Save
 * button all call - {@code JApiSettingsFrame} owns exactly one instance, constructed with references to
 * its own API-level form's dirty-check/save methods and to the Environments/Endpoints tabs.
 *
 * <p>Deliberately named {@code saveCurrentEdits()}, not {@code saveAll()} - per the user's explicit
 * decision, this sprint keeps the existing, low-risk single-object staging model (at most one of the
 * three staged objects below is ever actually dirty at a time, by construction of the navigation guard
 * that surrounds every place a different object could become selected), not true multi-object staging.
 * This method's body is essentially "call whichever of the three existing per-object save methods
 * currently reports dirty" - it contains no new persistence logic of its own.
 *
 * <p>The three staged objects:
 * <ol>
 * <li>The API-level form (General + Authentication + Variables &amp; Headers together, already treated
 * as one unit by {@code JApiSettingsFrame}'s own snapshot);</li>
 * <li>the currently-selected Environment ({@link JApiEnvironmentsPanel#isCurrentEditorDirty}/
 * {@link JApiEnvironmentsPanel#trySaveCurrentEditor});</li>
 * <li>the currently-selected Endpoint/Folder ({@link JApiEndpointTreePanel#isCurrentEditorDirty}/
 * {@link JApiEndpointTreePanel#trySaveCurrentEditor}).</li>
 * </ol>
 */
public class ApiConfigSaveCoordinator {

	private final BooleanSupplier apiFormIsDirty;
	private final BooleanSupplier apiFormTrySave;
	private final JApiEnvironmentsPanel environmentsPanel;
	private final JApiEndpointTreePanel endpointsPanel;

	public ApiConfigSaveCoordinator(BooleanSupplier apiFormIsDirty, BooleanSupplier apiFormTrySave,
			JApiEnvironmentsPanel environmentsPanel, JApiEndpointTreePanel endpointsPanel) {
		this.apiFormIsDirty = apiFormIsDirty;
		this.apiFormTrySave = apiFormTrySave;
		this.environmentsPanel = environmentsPanel;
		this.endpointsPanel = endpointsPanel;
	}

	/** {@code true} if any of the three staged objects has unsaved edits - drives the Save menu item's/toolbar button's enabled state and the frame title's {@code *} indicator. */
	public boolean isAnyDirty() {
		return apiFormIsDirty.getAsBoolean() || environmentsPanel.isCurrentEditorDirty() || endpointsPanel.isCurrentEditorDirty();
	}

	/**
	 * Persists whichever of the three staged objects is currently dirty (at most one, in practice - see
	 * class javadoc). Stops and surfaces the first validation error exactly like the underlying
	 * per-object save methods already do (an error dialog has already been shown by the time this
	 * returns {@code false}).
	 *
	 * @return {@code true} if there was nothing to save, or everything dirty was saved successfully
	 */
	public boolean saveCurrentEdits() {
		if (apiFormIsDirty.getAsBoolean() && !apiFormTrySave.getAsBoolean()) {
			return false;
		}
		if (environmentsPanel.isCurrentEditorDirty() && !environmentsPanel.trySaveCurrentEditor()) {
			return false;
		}
		if (endpointsPanel.isCurrentEditorDirty() && !endpointsPanel.trySaveCurrentEditor()) {
			return false;
		}
		return true;
	}
}
