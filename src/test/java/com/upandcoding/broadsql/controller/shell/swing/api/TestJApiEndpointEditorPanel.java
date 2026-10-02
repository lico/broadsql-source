package com.upandcoding.broadsql.controller.shell.swing.api;

import java.lang.reflect.Field;
import java.util.List;

import javax.swing.JTextField;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * API Quality and UX Consolidation sprint, Phase 2 - {@link JApiEndpointEditorPanel}'s URL/query-
 * parameter synchronization and the persistence invariant it exists to enforce ("endpointPath is base
 * path only, never the composed display text" - see the class javadoc and
 * {@code docs/TECHNICAL_CHANGE.md}, 2026-09-14). No component/pixel rendering exercised - construction
 * and programmatic field mutation only, same headless-structural convention
 * {@code TestJApiAuthenticationPanelMasking} already establishes.
 */
class TestJApiEndpointEditorPanel {

	private static final String API_ID = "EDITORTEST";

	@Test
	void loadingComposesTheDisplayedUrlFromBasePathAndOnlyTheEnabledParams() throws Exception {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Products", "GET", "${baseUrl}/products", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "limit", "10", false));
		ApiAttribute disabledExpand = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "expand", "full", false);
		disabledExpand.setEnabled(false);
		vault.saveAttribute(disabledExpand);

		JApiEndpointEditorPanel panel = new JApiEndpointEditorPanel();
		panel.load(vault, API_ID, version.getId(), endpoint.getId(), null);

		Assertions.assertEquals("${baseUrl}/products?limit=10", pathField(panel).getText(),
				"the disabled 'expand' parameter must not appear in the composed URL");
	}

	@Test
	void editingTheUrlFieldReconcilesTheQueryParameterGrid() throws Exception {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Products", "GET", "${baseUrl}/products", 0);
		vault.saveEndpoint(endpoint);

		JApiEndpointEditorPanel panel = new JApiEndpointEditorPanel();
		panel.load(vault, API_ID, version.getId(), endpoint.getId(), null);

		pathField(panel).setText("${baseUrl}/products?limit=5&sort=asc");

		List<ApiAttribute> params = queryParamsPanel(panel).getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER);
		Assertions.assertEquals(2, params.size());
		Assertions.assertEquals("limit", params.get(0).getName());
		Assertions.assertEquals("5", params.get(0).getValue());
		Assertions.assertTrue(params.get(0).isEnabled());
		Assertions.assertEquals("sort", params.get(1).getName());
		Assertions.assertEquals("asc", params.get(1).getValue());
	}

	@Test
	void removingAParameterFromTheUrlDisablesItRatherThanDeletingItsDefinition() throws Exception {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Products", "GET", "${baseUrl}/products", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "expand", "full", false));

		JApiEndpointEditorPanel panel = new JApiEndpointEditorPanel();
		panel.load(vault, API_ID, version.getId(), endpoint.getId(), null);
		Assertions.assertTrue(pathField(panel).getText().contains("expand"));

		pathField(panel).setText("${baseUrl}/products");

		List<ApiAttribute> params = queryParamsPanel(panel).getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER);
		Assertions.assertEquals(1, params.size(), "the definition must be preserved, not deleted");
		Assertions.assertEquals("expand", params.get(0).getName());
		Assertions.assertEquals("full", params.get(0).getValue(), "the value must also be preserved while disabled");
		Assertions.assertFalse(params.get(0).isEnabled());
	}

	@Test
	void disablingAParameterInTheGridRemovesItFromTheDisplayedUrl() throws Exception {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Products", "GET", "${baseUrl}/products", 0);
		vault.saveEndpoint(endpoint);
		ApiAttribute expand = new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "expand", "full", false);
		vault.saveAttribute(expand);

		JApiEndpointEditorPanel panel = new JApiEndpointEditorPanel();
		panel.load(vault, API_ID, version.getId(), endpoint.getId(), null);
		Assertions.assertTrue(pathField(panel).getText().contains("expand"));

		expand.setEnabled(false);
		queryParamsPanel(panel).setAttributes(List.of(expand));

		Assertions.assertFalse(pathField(panel).getText().contains("expand"), "disabling a row in the grid must recompose the displayed URL");
	}

	@Test
	void savePersistsTheBasePathOnlyNeverTheComposedUrl() throws Exception {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Products", "GET", "${baseUrl}/products", 0);
		vault.saveEndpoint(endpoint);
		vault.saveAttribute(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER, "limit", "10", false));

		JApiEndpointEditorPanel panel = new JApiEndpointEditorPanel();
		panel.load(vault, API_ID, version.getId(), endpoint.getId(), null);
		Assertions.assertEquals("${baseUrl}/products?limit=10", pathField(panel).getText(), "sanity check: the display is composed");

		trySave(panel);

		ApiEndpoint reloaded = vault.findEndpointById(endpoint.getId());
		Assertions.assertEquals("${baseUrl}/products", reloaded.getEndpointPath(),
				"the persisted endpointPath must be the base path only - never the composed URL with the query string embedded");
	}

	@Test
	void loadingAnEndpointWithLegacyEmbeddedQueryTextPromotesTheUncoveredParameterIntoAStructuredRow() throws Exception {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		// Legacy/unmigrated data: a literal query string embedded directly in endpointPath, no
		// structured QUERY_PARAMETER row at all - predates Phase 1's persistence invariant.
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Products", "GET", "${baseUrl}/products?type=widget", 0);
		vault.saveEndpoint(endpoint);

		JApiEndpointEditorPanel panel = new JApiEndpointEditorPanel();
		panel.load(vault, API_ID, version.getId(), endpoint.getId(), null);

		List<ApiAttribute> params = queryParamsPanel(panel).getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER);
		Assertions.assertEquals(1, params.size(), "the embedded 'type' parameter must be promoted into a structured, enabled row on load");
		Assertions.assertEquals("type", params.get(0).getName());
		Assertions.assertEquals("widget", params.get(0).getValue());
		Assertions.assertTrue(params.get(0).isEnabled());

		trySave(panel);
		ApiEndpoint reloaded = vault.findEndpointById(endpoint.getId());
		Assertions.assertEquals("${baseUrl}/products", reloaded.getEndpointPath(), "saving after the promotion must normalize the persisted path to base-only");
	}

	@Test
	void isDirtyIsFalseRightAfterLoadAndTrueAfterAnEdit() throws Exception {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));
		ApiEndpoint endpoint = new ApiEndpoint(version.getId(), null, "Products", "GET", "${baseUrl}/products", 0);
		vault.saveEndpoint(endpoint);

		JApiEndpointEditorPanel panel = new JApiEndpointEditorPanel();
		panel.load(vault, API_ID, version.getId(), endpoint.getId(), null);
		Assertions.assertFalse(panel.isDirty(), "loading must never itself be mistaken for an edit");

		nameField(panel).setText("Products (renamed)");
		Assertions.assertTrue(panel.isDirty());

		trySave(panel);
		Assertions.assertFalse(panel.isDirty(), "a successful save must clear the dirty state");
	}

	@Test
	void switchingBackToAnUnsavedEmptyEndpointStaysCleanUntouched() throws BroadSQLException {
		ApiDefinitionsVault vault = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion version = vault.createApiWithDefaultVersion(new ApiDefinition(API_ID));

		JApiEndpointEditorPanel panel = new JApiEndpointEditorPanel();
		panel.load(vault, API_ID, version.getId(), null, null);

		Assertions.assertFalse(panel.isDirty(), "opening a blank 'new endpoint' form untouched must not report dirty");
	}

	/**
	 * SPRINT XT02B, section 2.3/2.4: {@link JApiEndpointEditorPanel#save()} (invoked here through
	 * {@code trySave()}) no longer shows a success dialog - only the error-path dialogs remain, and
	 * those aren't exercised by the tests that call this helper. A plain direct call is enough now.
	 */
	private void trySave(JApiEndpointEditorPanel panel) {
		panel.trySave();
	}

	private JTextField pathField(JApiEndpointEditorPanel panel) throws Exception {
		return (JTextField) field(panel, "pathField");
	}

	private JApiAttributeTablePanel queryParamsPanel(JApiEndpointEditorPanel panel) throws Exception {
		return (JApiAttributeTablePanel) field(panel, "queryParamsPanel");
	}

	private JTextField nameField(JApiEndpointEditorPanel panel) throws Exception {
		return (JTextField) field(panel, "nameField");
	}

	private Object field(JApiEndpointEditorPanel panel, String name) throws Exception {
		Field field = JApiEndpointEditorPanel.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(panel);
	}
}
