package com.upandcoding.broadsql.controller.shell.swing.api;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiAuthChoice;
import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiAuthFormModel;
import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiFolderFormModel;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * Editor shown on the right of {@link JApiEndpointTreePanel} when a folder is selected -
 * docs/SPRINT XT02-sub sprint 5 - API Configuration GUI + Bruno YAML Round-trip.md, section 22: Name,
 * Parent folder, Variables, Authentication. Saves immediately on its own "Save folder" button, the same
 * per-node-save convention {@link JApiEnvironmentsPanel} and this codebase's existing
 * {@code JDatabaseGroupsPanel}/{@code JEnvironmentsPanel} already use, rather than deferring to the main
 * frame's Apply - a folder tree can be arbitrarily deep, and saving each node as it is edited keeps this
 * consistent with the API's own persistence semantics one row at a time.
 *
 * <p>Implements {@link ApiObjectEditor} (API Quality and UX Consolidation sprint, Phase 2) so
 * {@link JApiEndpointTreePanel}'s unsaved-changes navigation guard can address this editor generically,
 * alongside {@link JApiEndpointEditorPanel}. {@link #isDirty()} is a value-snapshot comparison captured
 * right after {@link #load} finishes populating every field - never a listener-driven flag.
 */
public class JApiFolderEditorPanel extends JPanel implements ApiObjectEditor {

	private ApiDefinitionsVault vault;
	private Integer apiVersionId;
	private Integer groupId;
	private Runnable onSaved;

	private JTextField nameField;
	private JComboBox<FolderOption> parentCombo;
	private JApiAttributeTablePanel variablesPanel;
	private JApiAuthenticationPanel authPanel;

	private String[] formSnapshot;

	public JApiFolderEditorPanel() {
		buildUi();
	}

	public void setOnSaved(Runnable onSaved) {
		this.onSaved = onSaved;
	}

	private void buildUi() {
		setLayout(new BorderLayout());
		JPanel general = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(5, 6, 5, 6);
		c.fill = GridBagConstraints.HORIZONTAL;

		nameField = new JTextField(24);
		parentCombo = new JComboBox<>();

		c.gridx = 0;
		c.gridy = 0;
		general.add(new JLabel("Name"), c);
		c.gridx = 1;
		c.weightx = 1;
		general.add(nameField, c);

		c.gridx = 0;
		c.gridy = 1;
		c.weightx = 0;
		general.add(new JLabel("Parent folder"), c);
		c.gridx = 1;
		c.weightx = 1;
		general.add(parentCombo, c);

		// SPRINT XT02B, section 2: no per-object "Save folder" button any more - this editor's own
		// field edits are now persisted only through the single global API > Save / Ctrl+S (see
		// JApiSettingsFrame's ApiConfigSaveCoordinator), or via the Save/Discard/Cancel navigation
		// guard when leaving a dirty folder. save()/trySave() below are unchanged - just no longer
		// independently button-triggered.
		add(general, BorderLayout.NORTH);

		JTabbedPane tabs = new JTabbedPane();
		variablesPanel = new JApiAttributeTablePanel(true);
		authPanel = new JApiAuthenticationPanel(true);
		tabs.addTab("Variables", variablesPanel);
		tabs.addTab("Authentication", authPanel);
		add(tabs, BorderLayout.CENTER);
	}

	/** @param groupId {@code null} means "create a new folder under {@code initialParentId}". */
	public void load(ApiDefinitionsVault vault, int apiVersionId, Integer groupId, Integer initialParentId) throws BroadSQLException {
		this.vault = vault;
		this.apiVersionId = apiVersionId;
		this.groupId = groupId;

		List<FolderOption> options = new ArrayList<>();
		options.add(new FolderOption(null, "(top level)"));
		for (ApiEndpointGroup candidate : vault.getGroupsForVersion(apiVersionId)) {
			if (groupId == null || !candidate.getId().equals(groupId)) {
				options.add(new FolderOption(candidate.getId(), candidate.getName()));
			}
		}
		parentCombo.setModel(new DefaultComboBoxModel<>(options.toArray(new FolderOption[0])));

		ApiFolderFormModel model;
		if (groupId == null) {
			model = ApiFolderFormModel.newFolder(initialParentId);
			variablesPanel.setAttributes(List.of());
			authPanel.setModel(ApiAuthFormModel.inheritDefault());
		} else {
			ApiEndpointGroup group = vault.findGroupById(groupId);
			model = ApiFolderFormModel.fromGroup(group);
			variablesPanel.setAttributes(vault.getAttributes(ApiOwnerType.GROUP, String.valueOf(groupId), ApiAttributeKind.VARIABLE));
			ApiAuthConfig auth = vault.getAuth(ApiOwnerType.GROUP, String.valueOf(groupId));
			authPanel.setModel(ApiAuthFormModel.fromAuth(auth, auth == null ? List.of() : vault.getAttributes(ApiOwnerType.AUTH, String.valueOf(auth.getId()), ApiAttributeKind.PROPERTY)));
		}
		nameField.setText(model.getName());
		selectParent(model.getParentGroupId());
		captureSnapshot();
	}

	/**
	 * SPRINT XT02B, section 5: Rename now stages the new name into this editor's Name field, marking
	 * it dirty, rather than persisting immediately - {@link JApiEndpointTreePanel#rename()} calls
	 * {@link #load} first (if this folder was not already the one shown) then this, then leaves
	 * persistence to the normal Save/Discard/Cancel path like any other field edit. Deliberately does
	 * not call {@link #captureSnapshot()} - the point is for {@link #isDirty()} to become {@code true}.
	 */
	public void stageRenameTo(String newName) {
		nameField.setText(newName);
	}

	@Override
	public boolean isDirty() {
		return formSnapshot != null && !Arrays.equals(formSnapshot, currentSnapshotValues());
	}

	private void captureSnapshot() {
		formSnapshot = currentSnapshotValues();
	}

	private String[] currentSnapshotValues() {
		FolderOption selectedParent = (FolderOption) parentCombo.getSelectedItem();
		return new String[] {
				nameField.getText(),
				selectedParent == null ? null : String.valueOf(selectedParent.id),
				variablesPanel.snapshotKey(),
				authPanel.getModel().snapshotKey(),
		};
	}

	@Override
	public boolean trySave() {
		return save();
	}

	private void selectParent(Integer parentGroupId) {
		for (int i = 0; i < parentCombo.getItemCount(); i++) {
			FolderOption option = parentCombo.getItemAt(i);
			if (java.util.Objects.equals(option.id, parentGroupId)) {
				parentCombo.setSelectedIndex(i);
				return;
			}
		}
	}

	private boolean save() {
		ApiFolderFormModel model = groupId == null ? ApiFolderFormModel.newFolder(null) : ApiFolderFormModel.fromGroup(vaultFindGroupSafely());
		model.setName(nameField.getText());
		FolderOption selectedParent = (FolderOption) parentCombo.getSelectedItem();
		model.setParentGroupId(selectedParent == null ? null : selectedParent.id);
		List<String> errors = model.validate();
		if (!errors.isEmpty()) {
			JOptionPane.showMessageDialog(this, String.join("\n", errors));
			return false;
		}
		try {
			ApiEndpointGroup group = groupId == null ? new ApiEndpointGroup(apiVersionId, model.getParentGroupId(), model.getName(), 0) : vault.findGroupById(groupId);
			group.setName(model.getName());
			group.setParentGroupId(model.getParentGroupId());
			vault.saveEndpointGroup(group);
			groupId = group.getId();

			String ownerId = String.valueOf(group.getId());
			vault.replaceAttributes(ApiOwnerType.GROUP, ownerId, ApiAttributeKind.VARIABLE, variablesPanel.getCommittedAttributes(ApiOwnerType.GROUP, ownerId, ApiAttributeKind.VARIABLE));

			ApiAuthFormModel authModel = authPanel.getModel();
			List<String> authErrors = authModel.validate();
			if (!authErrors.isEmpty()) {
				JOptionPane.showMessageDialog(this, String.join("\n", authErrors));
				return false;
			}
			applyAuth(ownerId, authModel);

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

	private void applyAuth(String ownerId, ApiAuthFormModel authModel) throws BroadSQLException {
		if (authModel.getChoice() == ApiAuthChoice.INHERIT) {
			vault.deleteAuth(ApiOwnerType.GROUP, ownerId);
			return;
		}
		if (authModel.getChoice() == ApiAuthChoice.UNSUPPORTED) {
			return; // read-only imported data - never re-saved as-is
		}
		vault.saveAuth(new ApiAuthConfig(ApiOwnerType.GROUP, ownerId, authModel.toAuthType()));
		ApiAuthConfig saved = vault.getAuth(ApiOwnerType.GROUP, ownerId);
		vault.replaceAttributes(ApiOwnerType.AUTH, String.valueOf(saved.getId()), ApiAttributeKind.PROPERTY, authModel.toProperties(saved.getId()));
	}

	private ApiEndpointGroup vaultFindGroupSafely() {
		try {
			return vault.findGroupById(groupId);
		} catch (BroadSQLException ex) {
			return new ApiEndpointGroup();
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
