package com.upandcoding.broadsql.controller.shell.swing.api;

import java.lang.reflect.Field;

import javax.swing.JTextField;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;

/**
 * SPRINT XT02B, section 2/5 - {@link ApiConfigSaveCoordinator} is the one place {@code JApiSettingsFrame}'s
 * {@code API > Save}/{@code Ctrl+S} and its unsaved-changes navigation guard both call. These tests cover
 * the coordinator itself: OR-ing the three staged objects' dirty state, and {@code saveCurrentEdits()}
 * persisting whichever one is actually dirty and stopping at the first failure - not the frame's menu/
 * tab-switch wiring around it. The API-level form's own dirty-check/save are supplied as simple
 * {@code BooleanSupplier} stubs, exactly like {@code JApiSettingsFrame} would wire {@code this::apiFormIsDirty}/
 * {@code this::trySaveApi}, so no {@code JApiSettingsFrame} instance is needed here.
 */
class TestApiConfigSaveCoordinator {

	private static final String API_ID = "COORDTEST";

	@Test
	void isAnyDirtyIsFalseWhenNothingIsDirty() throws Exception {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		JApiEnvironmentsPanel environmentsPanel = new JApiEnvironmentsPanel();
		environmentsPanel.setContext(vault, API_ID);
		JApiEndpointTreePanel endpointsPanel = new JApiEndpointTreePanel();

		ApiConfigSaveCoordinator coordinator = new ApiConfigSaveCoordinator(() -> false, () -> true, environmentsPanel, endpointsPanel);

		Assertions.assertFalse(coordinator.isAnyDirty());
		Assertions.assertTrue(coordinator.saveCurrentEdits(), "nothing dirty means nothing to save - trivially succeeds");
	}

	@Test
	void isAnyDirtyReflectsTheApiFormSupplier() {
		JApiEnvironmentsPanel environmentsPanel = new JApiEnvironmentsPanel();
		JApiEndpointTreePanel endpointsPanel = new JApiEndpointTreePanel();

		ApiConfigSaveCoordinator coordinator = new ApiConfigSaveCoordinator(() -> true, () -> true, environmentsPanel, endpointsPanel);

		Assertions.assertTrue(coordinator.isAnyDirty());
	}

	@Test
	void saveCurrentEditsCallsTheApiFormSaverWhenTheApiFormIsDirty() {
		JApiEnvironmentsPanel environmentsPanel = new JApiEnvironmentsPanel();
		JApiEndpointTreePanel endpointsPanel = new JApiEndpointTreePanel();
		boolean[] called = { false };

		ApiConfigSaveCoordinator coordinator = new ApiConfigSaveCoordinator(() -> true, () -> {
			called[0] = true;
			return true;
		}, environmentsPanel, endpointsPanel);

		Assertions.assertTrue(coordinator.saveCurrentEdits());
		Assertions.assertTrue(called[0], "a dirty API form must be persisted via the supplied saver");
	}

	@Test
	void saveCurrentEditsStopsAtTheFirstFailureAndReportsFalse() {
		JApiEnvironmentsPanel environmentsPanel = new JApiEnvironmentsPanel();
		JApiEndpointTreePanel endpointsPanel = new JApiEndpointTreePanel();

		ApiConfigSaveCoordinator coordinator = new ApiConfigSaveCoordinator(() -> true, () -> false, environmentsPanel, endpointsPanel);

		Assertions.assertFalse(coordinator.saveCurrentEdits(), "a validation failure on the dirty object must surface as false, not be silently skipped");
	}

	@Test
	void isAnyDirtyReflectsTheEnvironmentsTabAndSaveCurrentEditsPersistsIt() throws Exception {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		JApiEnvironmentsPanel environmentsPanel = new JApiEnvironmentsPanel();
		environmentsPanel.setContext(vault, API_ID);
		environmentsPanel.newEnvironmentButtonClicked();
		nameField(environmentsPanel).setText("Staging");

		JApiEndpointTreePanel endpointsPanel = new JApiEndpointTreePanel();

		ApiConfigSaveCoordinator coordinator = new ApiConfigSaveCoordinator(() -> false, () -> true, environmentsPanel, endpointsPanel);

		Assertions.assertTrue(coordinator.isAnyDirty(), "a staged new environment must be reported dirty");
		Assertions.assertTrue(coordinator.saveCurrentEdits());
		Assertions.assertFalse(environmentsPanel.isCurrentEditorDirty(), "a successful save must clear the environment's dirty state");
		Assertions.assertEquals(1, vault.getEnvironmentsForApi(API_ID).size());
	}

	private JTextField nameField(JApiEnvironmentsPanel panel) throws Exception {
		Field field = JApiEnvironmentsPanel.class.getDeclaredField("nameField");
		field.setAccessible(true);
		return (JTextField) field.get(panel);
	}
}
