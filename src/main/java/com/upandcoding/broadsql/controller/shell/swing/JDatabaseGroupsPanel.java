package com.upandcoding.broadsql.controller.shell.swing;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.DatabaseGroupDefinition;

/**
 * "Database Groups" top-level tab of {@link JSettingsFrame} - the GUI for the Database Group entity
 * of {@code docs/CONNECTION_MODEL.md}, in the same list-on-the-left/form-on-the-right shape as the
 * "Connections" tab, per the user's 03/09/2026 feedback on the first version of this shape (a
 * separate "Manage Instances" dialog, since removed along with {@code JEnvironmentsDialog}).
 *
 * <p>Phase 1 of the {@code docs/CONNECTION_MODEL.md} rework: this class replaces {@code
 * JInstancesPanel}, adding the Comment field (§4) and the group-detail "Connections in this group"
 * table (§4.2) the Database Group model requires. The physical CDF table stays named {@code
 * INSTANCE} (see {@link DatabaseDefinitionsVault#saveGroup(DatabaseGroupDefinition, String)}'s
 * javadoc) - only this class, the vault methods it calls, and the model class change name.
 *
 * <p>Deletion is a soft delete ({@code STATUS_ID} to {@code INACTIVE}) and is refused outright if any
 * active connection still references the group - see
 * {@link DatabaseDefinitionsVault#softDeleteGroup}. Not unit-testable itself (Swing, headless CI);
 * the CRUD/guard logic it calls into is covered by {@code TestDatabaseDefinitionsVaultGroups}.
 */
public class JDatabaseGroupsPanel extends JPanel {

	private static final Logger log = LoggerFactory.getLogger(JDatabaseGroupsPanel.class);

	private DatabaseDefinitionsVault vault;
	private Runnable onChange;

	private final DefaultListModel<String> listModel = new DefaultListModel<>();
	private final Map<String, DatabaseGroupDefinition> groupsById = new LinkedHashMap<>();

	/** The ID of the group currently loaded into the form, or {@code null} for a new/unsaved one. */
	private String loadedId;

	private JList<String> list;
	private JTextField idField;
	private JTextField descrField;
	private JTextField commentField;
	private JCheckBox activeCheckBox;
	private JButton saveButton;
	private JButton deleteButton;
	private DefaultTableModel connectionsTableModel;

	public JDatabaseGroupsPanel() {
		buildUi();
		showForm(false);
	}

	public void setVault(DatabaseDefinitionsVault vault) {
		this.vault = vault;
		reload();
	}

	/**
	 * Called after a successful save/delete - lets {@link JSettingsFrame} refresh the connection
	 * form's Database Group combo box immediately, without restarting {@code CONFIG}.
	 */
	public void setOnChange(Runnable onChange) {
		this.onChange = onChange;
	}

	/**
	 * Entry points for {@link JSettingsFrame}'s "Database Groups" menu, which drives this tab's
	 * New/Save/Delete buttons remotely - mirrors how the "Connections" menu drives that tab's own
	 * buttons via package-visible methods on {@code JSettingsFrame} itself.
	 */
	public void triggerNew() {
		newGroup();
	}

	public void triggerSave() {
		save();
	}

	public void triggerDelete() {
		delete();
	}

	private void buildUi() {
		setLayout(new BorderLayout());

		list = new JList<>(listModel);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.addListSelectionListener(evt -> {
			if (!evt.getValueIsAdjusting()) {
				loadSelectionIntoForm();
			}
		});
		JScrollPane listScroll = new JScrollPane(list);
		listScroll.setMinimumSize(new Dimension(100, 50));

		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, buildFormPanel());
		splitPane.setDividerLocation(160);
		add(splitPane, BorderLayout.CENTER);
	}

	private JPanel buildFormPanel() {
		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 4, 4, 4);
		c.fill = GridBagConstraints.HORIZONTAL;

		idField = new JTextField(15);
		descrField = new JTextField(30);
		commentField = new JTextField(30);
		activeCheckBox = new JCheckBox("Active", true);

		c.gridx = 0;
		c.gridy = 0;
		panel.add(new JLabel("ID"), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(idField, c);

		c.gridx = 0;
		c.gridy = 1;
		c.weightx = 0;
		panel.add(new JLabel("Description"), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(descrField, c);

		c.gridx = 0;
		c.gridy = 2;
		c.weightx = 0;
		panel.add(new JLabel("Comment"), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(commentField, c);

		c.gridx = 1;
		c.gridy = 3;
		c.weightx = 1;
		panel.add(activeCheckBox, c);

		JButton newButton = new JButton("New");
		newButton.addActionListener(e -> newGroup());
		saveButton = new JButton("Save");
		saveButton.addActionListener(e -> save());
		deleteButton = new JButton("Delete");
		deleteButton.addActionListener(e -> delete());

		JPanel buttons = new JPanel();
		buttons.add(newButton);
		buttons.add(saveButton);
		buttons.add(deleteButton);

		c.gridx = 0;
		c.gridy = 4;
		c.gridwidth = 2;
		panel.add(buttons, c);

		connectionsTableModel = new DefaultTableModel(new Object[] { "Environment", "Connection" }, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};
		JTable connectionsTable = new JTable(connectionsTableModel);
		JScrollPane connectionsScroll = new JScrollPane(connectionsTable);
		connectionsScroll.setBorder(javax.swing.BorderFactory.createTitledBorder("Connections in this group"));
		connectionsScroll.setMinimumSize(new Dimension(200, 100));

		c.gridy = 5;
		c.weighty = 1;
		c.fill = GridBagConstraints.BOTH;
		panel.add(connectionsScroll, c);

		return panel;
	}

	private void reload() {
		if (vault == null) {
			return;
		}
		String previouslySelected = list.getSelectedValue();
		try {
			List<DatabaseGroupDefinition> groups = vault.getGroupDetails();
			listModel.clear();
			groupsById.clear();
			for (DatabaseGroupDefinition group : groups) {
				listModel.addElement(group.getId());
				groupsById.put(group.getId(), group);
			}
			if (previouslySelected != null && groupsById.containsKey(previouslySelected)) {
				list.setSelectedValue(previouslySelected, true);
			} else {
				newGroup();
			}
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
			JOptionPane.showMessageDialog(this, "Could not load database groups: " + ex.getLocalizedMessage());
		}
	}

	private void loadSelectionIntoForm() {
		String id = list.getSelectedValue();
		if (id == null) {
			return;
		}
		DatabaseGroupDefinition group = groupsById.get(id);
		loadedId = group.getId();
		idField.setText(group.getId());
		// Editable, not read-only: the ID can be renamed (see save()), which propagates to every
		// CONNECTIONS row using it, active or inactive.
		idField.setEditable(true);
		descrField.setText(group.getDescr());
		commentField.setText(group.getComment());
		activeCheckBox.setSelected(group.isActive());
		reloadConnectionsTable(id);
		showForm(true);
		updateSystemGroupProtection(id);
	}

	private void reloadConnectionsTable(String groupId) {
		connectionsTableModel.setRowCount(0);
		try {
			for (DatabaseDefinition connection : vault.getConnectionsForGroup(groupId)) {
				connectionsTableModel.addRow(new Object[] { connection.getEnvironment(), connection.getId() });
			}
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
		}
	}

	private void newGroup() {
		list.clearSelection();
		loadedId = null;
		idField.setText("");
		idField.setEditable(true);
		descrField.setText("");
		commentField.setText("");
		activeCheckBox.setSelected(true);
		connectionsTableModel.setRowCount(0);
		showForm(false);
		updateSystemGroupProtection(null);
	}

	private void showForm(boolean hasSelection) {
		deleteButton.setEnabled(hasSelection);
	}

	/**
	 * Locks this tab against modifying or deleting the {@code $CDF} Database Group - the group
	 * BroadSQL auto-registers for its own connection to the Connections Definition File, a system row
	 * rather than a user-managed one. Mirrors {@code JSettingsFrame#updateConnectionEditability}'s
	 * protection of the {@code $CDF} connection itself, for the same reason.
	 */
	private void updateSystemGroupProtection(String id) {
		boolean isSystemGroup = SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(id);
		idField.setEditable(!isSystemGroup);
		descrField.setEditable(!isSystemGroup);
		commentField.setEditable(!isSystemGroup);
		activeCheckBox.setEnabled(!isSystemGroup);
		saveButton.setEnabled(!isSystemGroup);
		if (isSystemGroup) {
			deleteButton.setEnabled(false);
		}
	}// updateSystemGroupProtection

	private void save() {
		if (SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(loadedId)) {
			// Authoritative guard, not just the disabled Save button/field lock above: also protects
			// against JSettingsFrame's "Database Groups" menu calling triggerSave() directly.
			JOptionPane.showMessageDialog(this, "The '" + SpringPropertiesConfig.CDF_ID + "' Database Group is a system entry and cannot be modified.");
			return;
		}
		String id = idField.getText();
		if (StringUtils.isBlank(id)) {
			JOptionPane.showMessageDialog(this, "ID is required.");
			return;
		}
		String newId = id.trim();
		boolean renaming = loadedId != null && !loadedId.equals(newId);
		if (renaming) {
			int confirm = JOptionPane.showConfirmDialog(this,
					"Renaming Database Group '" + loadedId + "' to '" + newId + "' will update every connection "
							+ "(active and inactive) currently using '" + loadedId + "'. Continue?",
					"Rename Database Group", JOptionPane.YES_NO_OPTION);
			if (confirm != JOptionPane.YES_OPTION) {
				return;
			}
		}
		DatabaseGroupDefinition group = new DatabaseGroupDefinition(newId, descrField.getText(), commentField.getText(),
				activeCheckBox.isSelected() ? DatabaseDefinition.STATUS_ACTIVE : DatabaseDefinition.STATUS_INACTIVE);
		try {
			vault.saveGroup(group, loadedId);
			reload();
			list.setSelectedValue(group.getId(), true);
			notifyChange();
			JOptionPane.showMessageDialog(this, "Database Group '" + group.getId() + "' saved.");
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void delete() {
		String id = list.getSelectedValue();
		if (id == null) {
			return;
		}
		if (SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(id)) {
			// Authoritative guard, not just the disabled Delete button above: also protects against
			// JSettingsFrame's "Database Groups" menu calling triggerDelete() directly.
			JOptionPane.showMessageDialog(this, "The '" + SpringPropertiesConfig.CDF_ID + "' Database Group is a system entry and cannot be deleted.");
			return;
		}
		int input = JOptionPane.showConfirmDialog(this, "Deactivate Database Group '" + id + "'?");
		if (input != JOptionPane.YES_OPTION) {
			return;
		}
		try {
			vault.softDeleteGroup(id);
			reload();
			notifyChange();
		} catch (BroadSQLException ex) {
			// Expected path when active connections still reference this group - see
			// DatabaseDefinitionsVault#softDeleteGroup. Not a bug, so no log.error() here.
			JOptionPane.showMessageDialog(this, ex.getLocalizedMessage());
		}
	}

	private void notifyChange() {
		if (onChange != null) {
			onChange.run();
		}
	}
}
