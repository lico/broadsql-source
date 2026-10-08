package com.upandcoding.broadsql.controller.shell.swing.api;

import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * {@code CONFIG API}'s "Variables &amp; Headers" tab - docs/SPRINT XT02-sub sprint 5 - API Configuration
 * GUI + Bruno YAML Round-trip.md, section 19: API/collection-level variables and headers, split into two
 * visible sections. Both are {@link JApiAttributeTablePanel} instances - Variables with the Secret
 * column, Headers without (section 19 lists "Secret where applicable" for headers, but no current
 * BroadSQL header actually carries one - the shared table always exposes the column when asked; here it
 * is deliberately requested for headers too, matching the spec text over the narrower current usage).
 *
 * <p>"Do not place authentication-generated headers here automatically" (section 19) - trivially true,
 * since this panel only ever shows/saves the {@code API_ATTRIBUTE} rows the user themselves put here;
 * runtime-generated Authorization headers never become {@code ApiAttribute} rows in the first place.
 */
public class JApiVariablesHeadersPanel extends JPanel {

	private ApiDefinitionsVault vault;
	private String apiId;

	private JApiAttributeTablePanel variablesPanel;
	private JApiAttributeTablePanel headersPanel;

	public JApiVariablesHeadersPanel() {
		buildUi();
	}

	private void buildUi() {
		setLayout(new GridLayout(2, 1, 0, 8));
		variablesPanel = new JApiAttributeTablePanel(true);
		headersPanel = new JApiAttributeTablePanel(true);

		JPanel variablesWrapper = new JPanel(new java.awt.BorderLayout());
		variablesWrapper.setBorder(BorderFactory.createTitledBorder("Variables"));
		variablesWrapper.add(variablesPanel, java.awt.BorderLayout.CENTER);

		JPanel headersWrapper = new JPanel(new java.awt.BorderLayout());
		headersWrapper.setBorder(BorderFactory.createTitledBorder("Headers"));
		headersWrapper.add(headersPanel, java.awt.BorderLayout.CENTER);

		add(variablesWrapper);
		add(headersWrapper);
	}

	public void setContext(ApiDefinitionsVault vault, String apiId) throws BroadSQLException {
		this.vault = vault;
		this.apiId = apiId;
		reload();
	}

	public void reload() throws BroadSQLException {
		if (vault == null || apiId == null) {
			return;
		}
		variablesPanel.setAttributes(vault.getAttributes(ApiOwnerType.API, apiId, ApiAttributeKind.VARIABLE));
		headersPanel.setAttributes(vault.getAttributes(ApiOwnerType.API, apiId, ApiAttributeKind.HEADER));
	}

	/** Persists both tables' current contents - called from {@code JApiSettingsFrame}'s "Save API". */
	public void apply() throws BroadSQLException {
		if (vault == null || apiId == null) {
			return;
		}
		vault.replaceAttributes(ApiOwnerType.API, apiId, ApiAttributeKind.VARIABLE, variablesPanel.getCommittedAttributes(ApiOwnerType.API, apiId, ApiAttributeKind.VARIABLE));
		vault.replaceAttributes(ApiOwnerType.API, apiId, ApiAttributeKind.HEADER, headersPanel.getCommittedAttributes(ApiOwnerType.API, apiId, ApiAttributeKind.HEADER));
	}

	/** A deterministic snapshot of both tables' current content - the value-snapshot dirty-tracking building block (API Quality and UX Consolidation sprint, Phase 2). */
	public String snapshotKey() {
		return variablesPanel.snapshotKey() + "|" + headersPanel.snapshotKey();
	}
}
