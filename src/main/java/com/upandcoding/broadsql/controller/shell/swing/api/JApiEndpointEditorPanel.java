package com.upandcoding.broadsql.controller.shell.swing.api;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiAuthChoice;
import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiAuthFormModel;
import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiEndpointFormModel;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiUrlQueryStringSync;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * Editor shown on the right of {@link JApiEndpointTreePanel} when an endpoint is selected -
 * docs/SPRINT XT02-sub sprint 5 - API Configuration GUI + Bruno YAML Round-trip.md, sections 23-28,
 * amended by docs/Amendment - Endpoint Aliases and Future Scriptability.md, section 7 (the General
 * tab's new {@code Alias} field). General fields plus nested Params/Headers/Variables/Auth/Body
 * sub-tabs, saving immediately on "Save endpoint" (now relocated to {@link JApiEndpointTreePanel}'s
 * own action row, grouped with "New Endpoint"/"Delete" - API Quality and UX Consolidation sprint,
 * Phase 2) - same per-node-save convention as {@link JApiFolderEditorPanel}.
 *
 * <p><b>URL / query-parameter synchronization</b> (Phase 2): {@link #pathField} displays a
 * <i>composed</i> URL - {@link #basePathTemplate} (the persisted, query-string-free path) plus the
 * currently enabled {@link #queryParamsPanel} rows plus {@link #fragment} - via
 * {@link ApiUrlQueryStringSync#compose}. Editing {@code pathField} directly re-splits it
 * ({@link #onPathFieldChanged}) and reconciles the parameter grid; editing the parameter grid
 * recomposes {@code pathField} ({@link #onQueryParamsChanged}). {@link #updatingFromUrl}/
 * {@link #updatingFromParameters} guard against the two listeners re-triggering each other, and
 * {@link #loading} guards against either firing while {@link #load} is still populating the form.
 * <b>Only {@link #basePathTemplate} (plus {@link #fragment}) is ever persisted</b> as
 * {@code ApiEndpoint.endpointPath} - never the composed display text - so the endpoint's stored path
 * and its structured {@code QUERY_PARAMETER} rows can never again contradict each other (the sprint's
 * blocking bug, fixed at the execution layer in Phase 1 - see
 * {@code com.upandcoding.broadsql.dao.api.execution.ApiEndpointRequestBuilder}).
 *
 * <p><b>Dirty tracking</b> ({@link #isDirty()}): a value-snapshot comparison captured right after
 * {@link #load} finishes populating every field, exactly like {@code JSettingsFrame}'s established
 * Connections-form pattern - never a listener-driven boolean flag, so population itself can never be
 * mistaken for a real edit.
 *
 * <p><b>Known limitation, this release</b>: a structured body ({@code form-urlencoded}/
 * {@code multipart-form}) is edited as its raw canonical JSON text rather than the
 * Name/Value/Type/Content-Type/Enabled table section 28 describes as ideal ("use a table where
 * practical") - a deliberate scope reduction for this sprint (documented in
 * {@code docs/TECHNICAL_CHANGE.md} and the sprint doc's GUI-decisions addendum), not a silent gap:
 * every field the table would show is still fully preserved (JSON round-trips the entire subtree
 * losslessly, per {@code BrunoCollectionImporter#applyBody}'s own javadoc), just edited as text.
 */
public class JApiEndpointEditorPanel extends JPanel implements ApiObjectEditor {

	private static final List<String> METHODS = List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
	private static final List<String> BODY_MODES = List.of("None", "json", "text", "xml", "form-urlencoded", "multipart-form");

	private ApiDefinitionsVault vault;
	private String apiId;
	private Integer apiVersionId;
	private Integer endpointId;
	private Runnable onSaved;

	private JLabel idLabel;
	private JTextField nameField;
	private JComboBox<String> methodCombo;
	private JTextField pathField;
	private JComboBox<FolderOption> folderCombo;
	private JTextField aliasField;

	private JApiAttributeTablePanel queryParamsPanel;
	private JApiAttributeTablePanel pathParamsPanel;
	private JApiAttributeTablePanel headersPanel;
	private JApiAttributeTablePanel variablesPanel;
	private JApiAuthenticationPanel authPanel;
	private JComboBox<String> bodyModeCombo;
	private CardLayout bodyCardLayout;
	private JPanel bodyCardsPanel;
	private JTextArea bodyTextArea;

	// URL/query-parameter synchronization state - see class javadoc.
	private String basePathTemplate = "";
	private String fragment;
	private boolean updatingFromUrl;
	private boolean updatingFromParameters;
	private boolean loading;

	// Dirty-state tracking - see class javadoc.
	private String[] formSnapshot;

	public JApiEndpointEditorPanel() {
		buildUi();
	}

	public void setOnSaved(Runnable onSaved) {
		this.onSaved = onSaved;
	}

	private void buildUi() {
		setLayout(new BorderLayout());
		add(buildGeneralPanel(), BorderLayout.NORTH);

		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Params", buildParamsPanel());
		headersPanel = new JApiAttributeTablePanel(true);
		tabs.addTab("Headers", headersPanel);
		variablesPanel = new JApiAttributeTablePanel(true);
		tabs.addTab("Variables", variablesPanel);
		authPanel = new JApiAuthenticationPanel(true);
		tabs.addTab("Auth", authPanel);
		tabs.addTab("Body", buildBodyPanel());
		add(tabs, BorderLayout.CENTER);
	}

	private JPanel buildGeneralPanel() {
		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(5, 6, 5, 6);
		c.fill = GridBagConstraints.HORIZONTAL;

		idLabel = new JLabel("(unsaved)");
		idLabel.setToolTipText("System-managed identifier - not editable. Used by RUN when no alias is set.");
		nameField = new JTextField(24);
		methodCombo = new JComboBox<>(METHODS.toArray(new String[0]));
		pathField = new JTextField(30);
		pathField.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				onPathFieldChanged();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				onPathFieldChanged();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				onPathFieldChanged();
			}
		});
		folderCombo = new JComboBox<>();
		aliasField = new JTextField(20);
		aliasField.setToolTipText("Optional stable name for future BSQL CALL/script usage. BroadSQL aliases are preserved when imported API definitions are updated.");

		int row = 0;
		c.gridx = 0;
		c.gridy = row;
		panel.add(new JLabel("ID"), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(idLabel, c);

		row++;
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		panel.add(new JLabel("Name"), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(nameField, c);

		row++;
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		panel.add(new JLabel("Method"), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(methodCombo, c);

		row++;
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		panel.add(new JLabel("URL / Path"), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(pathField, c);

		row++;
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		panel.add(new JLabel("Folder"), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(folderCombo, c);

		row++;
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		panel.add(new JLabel("BroadSQL Alias"), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(aliasField, c);

		return panel;
	}

	private JPanel buildParamsPanel() {
		JPanel panel = new JPanel(new GridLayout(2, 1, 0, 8));
		// SPRINT XT02A (URL-Native API Execution), section 9.2: query/path parameters get the extra
		// Required/Type/Default/Allowed Values columns - RUN's parameter binder/validator (ApiParameterBinder)
		// reads exactly these same ApiAttribute fields, so what is edited here is what RUN actually uses.
		queryParamsPanel = new JApiAttributeTablePanel(false, true);
		queryParamsPanel.setOnNameValueEnabledChange(this::onQueryParamsChanged);
		pathParamsPanel = new JApiAttributeTablePanel(false, true);
		JPanel queryWrapper = new JPanel(new BorderLayout());
		queryWrapper.setBorder(BorderFactory.createTitledBorder("Query parameters"));
		queryWrapper.add(queryParamsPanel, BorderLayout.CENTER);
		JPanel pathWrapper = new JPanel(new BorderLayout());
		pathWrapper.setBorder(BorderFactory.createTitledBorder("Path parameters"));
		pathWrapper.add(pathParamsPanel, BorderLayout.CENTER);
		panel.add(queryWrapper);
		panel.add(pathWrapper);
		return panel;
	}

	private JPanel buildBodyPanel() {
		JPanel panel = new JPanel(new BorderLayout());
		bodyModeCombo = new JComboBox<>(BODY_MODES.toArray(new String[0]));
		bodyModeCombo.addActionListener(e -> bodyCardLayout.show(bodyCardsPanel, "None".equals(bodyModeCombo.getSelectedItem()) ? "none" : "text"));
		JPanel top = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
		top.add(new JLabel("Body type:"));
		top.add(bodyModeCombo);
		panel.add(top, BorderLayout.NORTH);

		bodyCardLayout = new CardLayout();
		bodyCardsPanel = new JPanel(bodyCardLayout);
		bodyCardsPanel.add(new JPanel(), "none");
		bodyTextArea = new JTextArea(10, 40);
		bodyCardsPanel.add(new JScrollPane(bodyTextArea), "text");
		panel.add(bodyCardsPanel, BorderLayout.CENTER);
		return panel;
	}

	/** @param endpointId {@code null} means "create a new endpoint in {@code initialGroupId}". */
	public void load(ApiDefinitionsVault vault, String apiId, int apiVersionId, Integer endpointId, Integer initialGroupId) throws BroadSQLException {
		loading = true;
		try {
			this.vault = vault;
			this.apiId = apiId;
			this.apiVersionId = apiVersionId;
			this.endpointId = endpointId;

			List<FolderOption> options = new ArrayList<>();
			options.add(new FolderOption(null, "(top level)"));
			for (ApiEndpointGroup candidate : vault.getGroupsForVersion(apiVersionId)) {
				options.add(new FolderOption(candidate.getId(), candidate.getName()));
			}
			folderCombo.setModel(new DefaultComboBoxModel<>(options.toArray(new FolderOption[0])));

			ApiEndpointFormModel model;
			if (endpointId == null) {
				model = ApiEndpointFormModel.newEndpoint(initialGroupId);
				basePathTemplate = "";
				fragment = null;
				queryParamsPanel.setAttributes(List.of());
				pathParamsPanel.setAttributes(List.of());
				headersPanel.setAttributes(List.of());
				variablesPanel.setAttributes(List.of());
				authPanel.setModel(ApiAuthFormModel.inheritDefault());
				bodyModeCombo.setSelectedItem("None");
				bodyTextArea.setText("");
			} else {
				ApiEndpoint endpoint = vault.findEndpointById(endpointId);
				model = ApiEndpointFormModel.fromEndpoint(endpoint);
				String ownerId = String.valueOf(endpointId);
				List<ApiAttribute> queryParams = vault.getAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.QUERY_PARAMETER);
				// Reconciles any query text still embedded literally in the persisted path (legacy/
				// unmigrated data predating Phase 1's persistence invariant) into the structured model,
				// so editing and re-saving an old endpoint normalizes it - see class javadoc.
				loadPathAndReconcileQueryParams(model.getPath(), queryParams);
				pathParamsPanel.setAttributes(vault.getAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.PATH_PARAMETER));
				headersPanel.setAttributes(vault.getAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.HEADER));
				variablesPanel.setAttributes(vault.getAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.VARIABLE));
				ApiAuthConfig auth = vault.getAuth(ApiOwnerType.ENDPOINT, ownerId);
				authPanel.setModel(ApiAuthFormModel.fromAuth(auth, auth == null ? List.of() : vault.getAttributes(ApiOwnerType.AUTH, String.valueOf(auth.getId()), ApiAttributeKind.PROPERTY)));
				bodyModeCombo.setSelectedItem(model.getBodyMode() == null ? "None" : model.getBodyMode());
				bodyTextArea.setText(model.getBodyContent());
			}
			idLabel.setText(endpointId == null ? "(unsaved)" : String.valueOf(endpointId));
			nameField.setText(model.getName());
			methodCombo.setSelectedItem(model.getMethod());
			aliasField.setText(model.getAlias());
			selectFolder(model.getGroupId());
			refreshComposedPathField();
		} finally {
			loading = false;
			captureSnapshot();
		}
	}

	/**
	 * Splits {@code rawPath} (the persisted {@code endpointPath}, which may still carry an embedded
	 * query string from before Phase 1's fix) and reconciles any embedded occurrence with no
	 * structured counterpart into a new, implicit, enabled {@code QUERY_PARAMETER} row - so opening an
	 * old endpoint for editing surfaces the previously-hidden duplication, and saving it normalizes
	 * the persisted path to base-path-only going forward (see class javadoc).
	 */
	private void loadPathAndReconcileQueryParams(String rawPath, List<ApiAttribute> structuredQueryParams) {
		ApiUrlQueryStringSync.UrlParts parts = ApiUrlQueryStringSync.split(rawPath);
		basePathTemplate = parts.basePath;
		fragment = parts.fragment;

		if (parts.queryParams.isEmpty()) {
			queryParamsPanel.setAttributes(structuredQueryParams);
			return;
		}
		Map<String, Integer> structuredCountByName = new HashMap<>();
		for (ApiAttribute attr : structuredQueryParams) {
			structuredCountByName.merge(attr.getName(), 1, Integer::sum);
		}
		Map<String, Integer> seenCountByName = new HashMap<>();
		List<ApiAttribute> reconciled = new ArrayList<>(structuredQueryParams);
		for (ApiUrlQueryStringSync.RawQueryParam param : parts.queryParams) {
			int occurrenceIndex = seenCountByName.merge(param.name, 1, Integer::sum) - 1;
			if (occurrenceIndex >= structuredCountByName.getOrDefault(param.name, 0)) {
				String value = param.value == null ? "" : param.value;
				reconciled.add(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpointId), ApiAttributeKind.QUERY_PARAMETER, param.name, value, false));
			}
		}
		queryParamsPanel.setAttributes(reconciled);
	}

	private void selectFolder(Integer groupId) {
		for (int i = 0; i < folderCombo.getItemCount(); i++) {
			FolderOption option = folderCombo.getItemAt(i);
			if (Objects.equals(option.id, groupId)) {
				folderCombo.setSelectedIndex(i);
				return;
			}
		}
	}

	/** {@code pathField} re-split into {@link #basePathTemplate}/{@link #fragment}, and the query-parameter grid reconciled - user-driven URL edits only (guarded against the {@link #loading}/{@link #updatingFromParameters} echoes). */
	private void onPathFieldChanged() {
		if (loading || updatingFromParameters) {
			return;
		}
		updatingFromUrl = true;
		try {
			ApiUrlQueryStringSync.UrlParts parts = ApiUrlQueryStringSync.split(pathField.getText());
			basePathTemplate = parts.basePath;
			fragment = parts.fragment;
			reconcileParametersFromUrl(parts.queryParams);
		} finally {
			updatingFromUrl = false;
		}
	}

	/**
	 * Reconciles the query-parameter grid against {@code urlParams}, matched by name and occurrence
	 * index within that name's group (duplicate names are supported by the model - see
	 * {@code ApiUrlQueryStringSync}'s javadoc): an existing row whose occurrence is still present in
	 * the URL has its value updated and is (re-)enabled; an existing row whose occurrence is no longer
	 * present is <i>disabled</i>, never deleted (its definition is preserved, symmetric with the
	 * Enabled checkbox - section 24); a URL occurrence with no existing row at all becomes a new,
	 * enabled row, appended in URL order.
	 */
	private void reconcileParametersFromUrl(List<ApiUrlQueryStringSync.RawQueryParam> urlParams) {
		List<ApiAttribute> current = queryParamsPanel.getCommittedAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpointId), ApiAttributeKind.QUERY_PARAMETER);
		Map<String, List<ApiUrlQueryStringSync.RawQueryParam>> urlByName = ApiUrlQueryStringSync.groupByName(urlParams);
		Map<String, Integer> assignedCountByName = new HashMap<>();

		List<ApiAttribute> result = new ArrayList<>();
		for (ApiAttribute attr : current) {
			List<ApiUrlQueryStringSync.RawQueryParam> urlForName = urlByName.getOrDefault(attr.getName(), List.of());
			int assigned = assignedCountByName.getOrDefault(attr.getName(), 0);
			if (assigned < urlForName.size()) {
				String value = urlForName.get(assigned).value == null ? "" : urlForName.get(assigned).value;
				attr.setValue(value);
				attr.setEnabled(true);
			} else {
				attr.setEnabled(false);
			}
			assignedCountByName.put(attr.getName(), assigned + 1);
			result.add(attr);
		}
		for (Map.Entry<String, List<ApiUrlQueryStringSync.RawQueryParam>> entry : urlByName.entrySet()) {
			int assigned = assignedCountByName.getOrDefault(entry.getKey(), 0);
			List<ApiUrlQueryStringSync.RawQueryParam> occurrences = entry.getValue();
			for (int i = assigned; i < occurrences.size(); i++) {
				String value = occurrences.get(i).value == null ? "" : occurrences.get(i).value;
				result.add(new ApiAttribute(ApiOwnerType.ENDPOINT, String.valueOf(endpointId), ApiAttributeKind.QUERY_PARAMETER, entry.getKey(), value, false));
			}
		}
		queryParamsPanel.setAttributes(result);
	}

	/** The query-parameter grid changed (add/remove/edit/toggle) - recompose {@code pathField}'s displayed text, guarded against the {@link #loading}/{@link #updatingFromUrl} echoes. */
	private void onQueryParamsChanged() {
		if (loading || updatingFromUrl) {
			return;
		}
		updatingFromParameters = true;
		try {
			refreshComposedPathField();
		} finally {
			updatingFromParameters = false;
		}
	}

	private void refreshComposedPathField() {
		List<ApiAttribute> enabled = queryParamsPanel.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpointId), ApiAttributeKind.QUERY_PARAMETER)
				.stream().filter(ApiAttribute::isEnabled).toList();
		pathField.setText(ApiUrlQueryStringSync.compose(basePathTemplate, enabled, fragment));
	}

	/** The path actually persisted as {@code ApiEndpoint.endpointPath} - base path plus fragment, <b>never</b> the composed display text (see class javadoc's persistence invariant). */
	private String persistedPath() {
		return fragment == null || fragment.isEmpty() ? basePathTemplate : basePathTemplate + "#" + fragment;
	}

	@Override
	public boolean isDirty() {
		return formSnapshot != null && !Arrays.equals(formSnapshot, currentSnapshotValues());
	}

	private void captureSnapshot() {
		formSnapshot = currentSnapshotValues();
	}

	private String[] currentSnapshotValues() {
		FolderOption selectedFolder = (FolderOption) folderCombo.getSelectedItem();
		return new String[] {
				nameField.getText(),
				(String) methodCombo.getSelectedItem(),
				basePathTemplate,
				fragment,
				aliasField.getText(),
				selectedFolder == null ? null : String.valueOf(selectedFolder.id),
				queryParamsPanel.snapshotKey(),
				pathParamsPanel.snapshotKey(),
				headersPanel.snapshotKey(),
				variablesPanel.snapshotKey(),
				authPanel.getModel().snapshotKey(),
				(String) bodyModeCombo.getSelectedItem(),
				bodyTextArea.getText(),
		};
	}

	/**
	 * SPRINT XT02B, section 5: Rename now stages the new name into this editor's Name field, marking
	 * it dirty, rather than persisting immediately - {@link JApiEndpointTreePanel#rename()} calls
	 * {@link #load} first (if this endpoint was not already the one shown) then this, then leaves
	 * persistence to the normal Save/Discard/Cancel path like any other field edit. Deliberately does
	 * not call {@link #captureSnapshot()} - the point is for {@link #isDirty()} to become {@code true}.
	 */
	public void stageRenameTo(String newName) {
		nameField.setText(newName);
	}

	@Override
	public boolean trySave() {
		return save();
	}

	private boolean save() {
		ApiEndpointFormModel model = endpointId == null ? ApiEndpointFormModel.newEndpoint(null) : ApiEndpointFormModel.fromEndpoint(vaultFindEndpointSafely());
		model.setName(nameField.getText());
		model.setMethod((String) methodCombo.getSelectedItem());
		model.setPath(persistedPath());
		FolderOption selectedFolder = (FolderOption) folderCombo.getSelectedItem();
		model.setGroupId(selectedFolder == null ? null : selectedFolder.id);
		model.setAlias(aliasField.getText());
		String bodyMode = (String) bodyModeCombo.getSelectedItem();
		model.setBodyMode("None".equals(bodyMode) ? null : bodyMode);
		model.setBodyContent("None".equals(bodyMode) ? null : bodyTextArea.getText());

		List<String> errors = new ArrayList<>(model.validate());
		ApiAuthFormModel authModel = authPanel.getModel();
		errors.addAll(authModel.validate());
		if (!errors.isEmpty()) {
			JOptionPane.showMessageDialog(this, String.join("\n", errors));
			return false;
		}

		try {
			ApiEndpoint endpoint = endpointId == null ? new ApiEndpoint(apiVersionId, model.getGroupId(), model.getName(), model.getMethod(), model.getPath(), 0)
					: vault.findEndpointById(endpointId);
			model.applyTo(endpoint);
			vault.saveEndpoint(endpoint);
			endpointId = endpoint.getId();
			idLabel.setText(String.valueOf(endpointId));
			String ownerId = String.valueOf(endpointId);

			vault.replaceAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.QUERY_PARAMETER, queryParamsPanel.getCommittedAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.QUERY_PARAMETER));
			vault.replaceAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.PATH_PARAMETER, pathParamsPanel.getCommittedAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.PATH_PARAMETER));
			vault.replaceAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.HEADER, headersPanel.getCommittedAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.HEADER));
			vault.replaceAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.VARIABLE, variablesPanel.getCommittedAttributes(ApiOwnerType.ENDPOINT, ownerId, ApiAttributeKind.VARIABLE));

			if (authModel.getChoice() == ApiAuthChoice.INHERIT) {
				vault.deleteAuth(ApiOwnerType.ENDPOINT, ownerId);
			} else if (authModel.getChoice() != ApiAuthChoice.UNSUPPORTED) {
				vault.saveAuth(new ApiAuthConfig(ApiOwnerType.ENDPOINT, ownerId, authModel.toAuthType()));
				ApiAuthConfig saved = vault.getAuth(ApiOwnerType.ENDPOINT, ownerId);
				vault.replaceAttributes(ApiOwnerType.AUTH, String.valueOf(saved.getId()), ApiAttributeKind.PROPERTY, authModel.toProperties(saved.getId()));
			}

			captureSnapshot();
			// SPRINT XT02B, section 2.3/2.4: no success popup on an ordinary successful save - dirty
			// state simply clears (via captureSnapshot() above) and the frame's title/Save button
			// reflect it. Errors above still show a dialog, unchanged.
			if (onSaved != null) {
				onSaved.run();
			}
			return true;
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
			return false;
		}
	}

	private ApiEndpoint vaultFindEndpointSafely() {
		try {
			return vault.findEndpointById(endpointId);
		} catch (BroadSQLException ex) {
			return new ApiEndpoint();
		}
	}

	private static final class FolderOption {
		final Integer id;
		final String label;

		FolderOption(Integer id, String label) {
			this.id = id;
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}
}
