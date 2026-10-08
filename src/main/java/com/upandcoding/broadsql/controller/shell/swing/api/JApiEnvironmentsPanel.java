package com.upandcoding.broadsql.controller.shell.swing.api;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiEnvironmentFormModel;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * {@code CONFIG API}'s "Environments" tab - docs/SPRINT XT02-sub sprint 5 - API Configuration GUI +
 * Bruno YAML Round-trip.md, sections 9-11: master/detail, Base URL kept prominent and separate from the
 * variable table (section 10), New/Rename/Duplicate/Delete actions. "Duplicate" is the workflow the spec
 * calls out by name as important (section 9's Development-&gt;Production example) - wired directly to
 * {@link ApiDefinitionsVault#duplicateEnvironment}.
 *
 * <p>Unlike the database Environment tab ({@code JEnvironmentsPanel}), an API environment has no
 * separate Active/Inactive browsing view in the spec - "Delete environment" is presented as one
 * destructive action, so this tab's Delete button performs {@code deactivate} then
 * {@code hardDelete} together after a single confirmation, exactly like the main frame's "Delete API"
 * does for the API level.
 *
 * <p><b>API Quality and UX Consolidation sprint, Phase 2</b>: real unsaved-edit protection
 * ({@link #confirmDiscardCurrentIfDirty}) now guards the list's selection change and the explicit "New
 * environment" button, via the same {@link UnsavedChangesDialog} the endpoint tree uses. Dirty state is
 * a value-snapshot comparison, captured right after the form is populated - never a listener-driven
 * flag. Choosing "Save" from that prompt persists the environment being navigated away from but
 * deliberately does not also refresh the environment list ({@link #trySave()} - the list-refreshing
 * {@link #reload()} stays reserved for the plain "Save environment" button click) - re-running
 * {@code reload()} mid-navigation would reselect the just-saved item and re-fire this same listener
 * re-entrantly, fighting the navigation the user actually asked for; the one accepted trade-off is that
 * a brand-new environment saved this way does not appear in the list until the next {@code reload()}
 * (any subsequent save/delete/duplicate), even though it is already correctly persisted.
 */
public class JApiEnvironmentsPanel extends JPanel {

	private ApiDefinitionsVault vault;
	private String apiId;
	private Runnable onChange;

	private final DefaultListModel<ApiEnvironment> listModel = new DefaultListModel<>();
	private final Map<Integer, ApiEnvironment> environmentsById = new LinkedHashMap<>();

	private JList<ApiEnvironment> list;
	private JTextField nameField;
	private JTextField baseUrlField;
	private JApiAttributeTablePanel variablesPanel;
	private JButton newButton;
	private JButton duplicateButton;
	private JButton deleteButton;

	private Integer loadedId;
	private String[] formSnapshot;
	private boolean loadingSelection;
	private ApiEnvironment lastConfirmedSelection;

	public JApiEnvironmentsPanel() {
		buildUi();
	}

	public void setContext(ApiDefinitionsVault vault, String apiId) {
		this.vault = vault;
		this.apiId = apiId;
		reload();
	}

	public void setOnChange(Runnable onChange) {
		this.onChange = onChange;
	}

	private void buildUi() {
		setLayout(new BorderLayout());

		list = new JList<>(listModel);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> jList, Object value, int index, boolean isSelected, boolean cellHasFocus) {
				super.getListCellRendererComponent(jList, value, index, isSelected, cellHasFocus);
				if (value instanceof ApiEnvironment env) {
					setText(env.getName());
				}
				return this;
			}
		});
		list.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting() && !loadingSelection) {
				handleSelectionChanged();
			}
		});
		JScrollPane listScroll = new JScrollPane(list);
		listScroll.setMinimumSize(new Dimension(140, 50));

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, buildFormPanel());
		split.setDividerLocation(160);
		add(split, BorderLayout.CENTER);

		JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
		newButton = new JButton("New environment");
		newButton.addActionListener(e -> newEnvironmentButtonClicked());
		duplicateButton = new JButton("Duplicate");
		duplicateButton.addActionListener(e -> duplicateSelected());
		deleteButton = new JButton("Delete environment");
		deleteButton.addActionListener(e -> deleteSelected());
		listButtons.add(newButton);
		listButtons.add(duplicateButton);
		listButtons.add(deleteButton);
		add(listButtons, BorderLayout.SOUTH);
	}

	private JPanel buildFormPanel() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(5, 6, 5, 6);
		c.fill = GridBagConstraints.HORIZONTAL;

		nameField = new JTextField(24);
		baseUrlField = new JTextField(30);
		variablesPanel = new JApiAttributeTablePanel(true);

		c.gridx = 0;
		c.gridy = 0;
		panel.add(new JLabel("Name"), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(nameField, c);

		c.gridx = 0;
		c.gridy = 1;
		c.weightx = 0;
		panel.add(new JLabel("Base URL"), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(baseUrlField, c);

		// SPRINT XT02B, section 2: no per-object "Save environment" button any more - field edits here
		// are persisted only through the single global API > Save / Ctrl+S (see JApiSettingsFrame's
		// ApiConfigSaveCoordinator), or via the Save/Discard/Cancel navigation guard when leaving a
		// dirty environment. save()/trySave() below are unchanged - just no longer independently
		// button-triggered.

		c.gridx = 0;
		c.gridy = 2;
		c.gridwidth = 2;
		c.weighty = 1;
		c.fill = GridBagConstraints.BOTH;
		JPanel variablesWrapper = new JPanel(new BorderLayout());
		variablesWrapper.setBorder(BorderFactory.createTitledBorder("Variables"));
		variablesWrapper.add(variablesPanel, BorderLayout.CENTER);
		panel.add(variablesWrapper, c);

		return panel;
	}

	private void reload() {
		if (vault == null || apiId == null) {
			return;
		}
		Integer previouslySelected = loadedId;
		loadingSelection = true;
		try {
			listModel.clear();
			environmentsById.clear();
			for (ApiEnvironment env : vault.getEnvironmentsForApi(apiId)) {
				if (isActive(env)) {
					environmentsById.put(env.getId(), env);
					listModel.addElement(env);
				}
			}
			if (previouslySelected != null && environmentsById.containsKey(previouslySelected)) {
				list.setSelectedValue(environmentsById.get(previouslySelected), true);
			} else if (!listModel.isEmpty()) {
				list.setSelectedIndex(0);
			} else {
				clearFormForNewEnvironment();
			}
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "Could not load environments: " + ex.getLocalizedMessage());
		} finally {
			loadingSelection = false;
		}
		lastConfirmedSelection = list.getSelectedValue();
		loadSelectionIntoForm();
	}

	private void handleSelectionChanged() {
		if (!confirmDiscardCurrentIfDirty()) {
			loadingSelection = true;
			try {
				list.setSelectedValue(lastConfirmedSelection, true);
			} finally {
				loadingSelection = false;
			}
			return;
		}
		lastConfirmedSelection = list.getSelectedValue();
		loadSelectionIntoForm();
	}

	/** {@code null}/{@code true} when nothing is dirty - proceed. {@code SAVE} persists the environment being navigated away from (see class javadoc for why this deliberately skips {@link #reload()}); {@code CANCEL} means the caller must abort. */
	private boolean confirmDiscardCurrentIfDirty() {
		if (formSnapshot == null || !isDirty()) {
			return true;
		}
		UnsavedChangesDialog.Decision decision = UnsavedChangesDialog.ask(this, "environment");
		return switch (decision) {
			case SAVE -> trySave();
			case DISCARD -> true;
			case CANCEL -> false;
		};
	}

	private boolean isDirty() {
		return formSnapshot != null && !Arrays.equals(formSnapshot, currentSnapshotValues());
	}

	private void captureSnapshot() {
		formSnapshot = currentSnapshotValues();
	}

	private String[] currentSnapshotValues() {
		return new String[] { nameField.getText(), baseUrlField.getText(), variablesPanel.snapshotKey() };
	}

	/** SPRINT XT02B, section 5: exposed for the Environment menu's "New" item - same action the button already triggered. */
	public void newEnvironmentButtonClicked() {
		if (!confirmDiscardCurrentIfDirty()) {
			return;
		}
		list.clearSelection();
		clearFormForNewEnvironment();
	}

	private void loadSelectionIntoForm() {
		ApiEnvironment env = list.getSelectedValue();
		if (env == null) {
			return;
		}
		loadedId = env.getId();
		try {
			ApiEnvironmentFormModel model = ApiEnvironmentFormModel.fromEnvironment(env, vault.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE));
			nameField.setText(model.getName());
			baseUrlField.setText(model.getBaseUrl());
			variablesPanel.setAttributes(model.getVariables());
			captureSnapshot();
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void clearFormForNewEnvironment() {
		loadedId = null;
		nameField.setText("");
		baseUrlField.setText("");
		variablesPanel.setAttributes(List.of());
		captureSnapshot();
	}

	/** {@code true} if the currently-shown environment (if any) has unsaved edits - for {@code JApiSettingsFrame}'s {@code ApiConfigSaveCoordinator}, which must consider this tab too. */
	public boolean isCurrentEditorDirty() {
		return isDirty();
	}

	/**
	 * SPRINT XT02B, section 5: persists the environment currently in the form, then refreshes the list
	 * (safe here: not mid-navigation) - for {@code ApiConfigSaveCoordinator}'s single global Save/
	 * Ctrl+S, which replaces the old per-object "Save environment" button this method's shape used to
	 * back directly.
	 */
	public boolean trySaveCurrentEditor() {
		boolean ok = trySave();
		if (ok) {
			reload();
		}
		return ok;
	}

	/**
	 * Validates and persists the environment currently in the form, without refreshing the list - see
	 * class javadoc for why the navigation guard needs this distinction from {@link #trySaveCurrentEditor()}.
	 *
	 * @return {@code true} if persisted (validation passed); {@code false} otherwise (an error dialog
	 *         has already been shown).
	 */
	private boolean trySave() {
		if (vault == null || apiId == null) {
			return false;
		}
		ApiEnvironmentFormModel model = loadedId == null ? ApiEnvironmentFormModel.newEnvironment() : ApiEnvironmentFormModel.fromEnvironment(environmentsById.get(loadedId), List.of());
		model.setName(nameField.getText());
		model.setBaseUrl(baseUrlField.getText());
		model.setVariables(variablesPanel.getCommittedAttributes(ApiOwnerType.ENVIRONMENT, loadedId == null ? null : String.valueOf(loadedId), ApiAttributeKind.VARIABLE));
		List<String> errors = model.validate();
		if (!errors.isEmpty()) {
			JOptionPane.showMessageDialog(this, String.join("\n", errors));
			return false;
		}
		try {
			ApiEnvironment env = loadedId == null ? new ApiEnvironment(apiId, model.getName(), null, environmentsById.size()) : environmentsById.get(loadedId);
			env.setName(model.getName());
			// Saves the variables-table content and the Base URL field together, in the order that keeps
			// the dual-written baseUrl attribute intact - see
			// ApiDefinitionsVault#saveEnvironmentVariablesAndBaseUrl's javadoc (SPRINT XT02 verification
			// finding 2: calling setEnvironmentBaseUrl followed by a plain replaceAttributes here used to
			// silently delete the baseUrl attribute the former had just written).
			if (env.getId() == null) {
				vault.saveEnvironment(env);
			}
			vault.saveEnvironmentVariablesAndBaseUrl(env, model.getBaseUrl(), model.variablesForOwner(env.getId()));
			loadedId = env.getId();
			captureSnapshot();
			notifyChange();
			return true;
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
			return false;
		}
	}

	/** SPRINT XT02B, section 5: exposed for the Environment menu's "Duplicate" item - same action the button already triggered, now also guarded (see class javadoc) since a structural action can otherwise silently discard an in-progress field edit. */
	public void duplicateSelected() {
		if (!confirmDiscardCurrentIfDirty()) {
			return;
		}
		ApiEnvironment env = list.getSelectedValue();
		if (env == null) {
			return;
		}
		String newName = JOptionPane.showInputDialog(this, "New environment name:", env.getName() + " copy");
		if (newName == null || newName.isBlank()) {
			return;
		}
		try {
			ApiEnvironment copy = vault.duplicateEnvironment(env.getId(), newName.trim());
			loadedId = copy.getId();
			reload();
			notifyChange();
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	/** SPRINT XT02B, section 5: exposed for the Environment menu's "Delete" item - same action the button already triggered, now also guarded (see class javadoc). */
	public void deleteSelected() {
		if (!confirmDiscardCurrentIfDirty()) {
			return;
		}
		ApiEnvironment env = list.getSelectedValue();
		if (env == null) {
			return;
		}
		int input = JOptionPane.showConfirmDialog(this, "Delete environment '" + env.getName() + "'?\nThis cannot be undone.", "Delete environment", JOptionPane.YES_NO_OPTION);
		if (input != JOptionPane.YES_OPTION) {
			return;
		}
		try {
			vault.deactivateEnvironment(env.getId());
			vault.hardDeleteEnvironment(env.getId());
			loadedId = null;
			reload();
			notifyChange();
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private boolean isActive(ApiEnvironment env) {
		return com.upandcoding.broadsql.dao.model.DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(env.getStatusId());
	}

	private void notifyChange() {
		if (onChange != null) {
			onChange.run();
		}
	}
}
