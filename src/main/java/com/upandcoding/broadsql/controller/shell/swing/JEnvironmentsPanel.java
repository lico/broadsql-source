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
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;

/**
 * "Environments" top-level tab of {@link JSettingsFrame} - the GUI for the Environment entity of
 * {@code docs/CONNECTION_MODEL.md} §5, in the same list-on-the-left/form-on-the-right shape as the
 * "Database Groups" tab ({@link JDatabaseGroupsPanel}).
 *
 * <p>Phase 1 of the {@code docs/CONNECTION_MODEL.md} rework: before this, {@code ENVIRONMENT} had no
 * management screen of its own - only a simple, insert-only read path
 * ({@link DatabaseDefinitionsVault#getEnvironments()}) backing the Connections tab's Environment
 * combo box. This is the first full CRUD lifecycle {@code ENVIRONMENT} has ever had.
 *
 * <p>Per §5.1, an Environment's ID is immutable after creation - unlike
 * {@link JDatabaseGroupsPanel}, the ID field here is only editable while creating a brand-new
 * Environment. Deletion is a soft delete ({@code STATUS_ID} to {@code INACTIVE}) and is refused if
 * any active connection still references the environment, or if it is the last remaining active
 * environment (§5.5) - see {@link DatabaseDefinitionsVault#softDeleteEnvironment}. Not
 * unit-testable itself (Swing, headless CI); the CRUD/guard logic it calls into is covered by
 * {@code TestDatabaseDefinitionsVaultEnvironments}.
 */
public class JEnvironmentsPanel extends JPanel {

	private static final Logger log = LoggerFactory.getLogger(JEnvironmentsPanel.class);

	private DatabaseDefinitionsVault vault;
	private Runnable onChange;

	private final DefaultListModel<String> listModel = new DefaultListModel<>();
	private final Map<String, EnvironmentDefinition> environmentsById = new LinkedHashMap<>();

	/** The ID of the environment currently loaded into the form, or {@code null} for a new/unsaved one. */
	private String loadedId;

	private JList<String> list;
	private JTextField idField;
	private JTextField descrField;
	private JTextField commentField;
	private JCheckBox productionCheckBox;
	private JCheckBox activeCheckBox;
	private JButton saveButton;
	private JButton deleteButton;

	public JEnvironmentsPanel() {
		buildUi();
		showForm(false);
	}

	public void setVault(DatabaseDefinitionsVault vault) {
		this.vault = vault;
		reload();
	}

	/**
	 * Called after a successful save/delete - lets {@link JSettingsFrame} refresh the connection
	 * form's Environment combo box immediately, without restarting {@code CONFIG}.
	 */
	public void setOnChange(Runnable onChange) {
		this.onChange = onChange;
	}

	/**
	 * Entry points for {@link JSettingsFrame}'s "Environments" menu, which drives this tab's
	 * New/Save/Delete buttons remotely - mirrors how the "Connections" menu drives that tab's own
	 * buttons via package-visible methods on {@code JSettingsFrame} itself.
	 */
	public void triggerNew() {
		newEnvironment();
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
		productionCheckBox = new JCheckBox("Production", false);
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
		panel.add(productionCheckBox, c);

		c.gridx = 1;
		c.gridy = 4;
		panel.add(activeCheckBox, c);

		JButton newButton = new JButton("New");
		newButton.addActionListener(e -> newEnvironment());
		saveButton = new JButton("Save");
		saveButton.addActionListener(e -> save());
		deleteButton = new JButton("Delete");
		deleteButton.addActionListener(e -> delete());

		JPanel buttons = new JPanel();
		buttons.add(newButton);
		buttons.add(saveButton);
		buttons.add(deleteButton);

		c.gridx = 0;
		c.gridy = 5;
		c.gridwidth = 2;
		panel.add(buttons, c);

		// Absorbs any leftover vertical space so the form stays anchored to the top.
		c.gridy = 6;
		c.weighty = 1;
		panel.add(new JPanel(), c);

		return panel;
	}

	private void reload() {
		if (vault == null) {
			return;
		}
		String previouslySelected = list.getSelectedValue();
		try {
			List<EnvironmentDefinition> environments = vault.getEnvironmentDetails();
			listModel.clear();
			environmentsById.clear();
			for (EnvironmentDefinition environment : environments) {
				listModel.addElement(environment.getId());
				environmentsById.put(environment.getId(), environment);
			}
			if (previouslySelected != null && environmentsById.containsKey(previouslySelected)) {
				list.setSelectedValue(previouslySelected, true);
			} else {
				newEnvironment();
			}
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
			JOptionPane.showMessageDialog(this, "Could not load environments: " + ex.getLocalizedMessage());
		}
	}

	private void loadSelectionIntoForm() {
		String id = list.getSelectedValue();
		if (id == null) {
			return;
		}
		EnvironmentDefinition environment = environmentsById.get(id);
		loadedId = environment.getId();
		idField.setText(environment.getId());
		// Read-only: per docs/CONNECTION_MODEL.md §5.1, an Environment's ID is immutable after creation.
		idField.setEditable(false);
		descrField.setText(environment.getDescr());
		commentField.setText(environment.getComment());
		productionCheckBox.setSelected(environment.isProduction());
		activeCheckBox.setSelected(environment.isActive());
		showForm(true);
		updateSystemEnvironmentProtection(id);
	}

	private void newEnvironment() {
		list.clearSelection();
		loadedId = null;
		idField.setText("");
		idField.setEditable(true);
		descrField.setText("");
		commentField.setText("");
		productionCheckBox.setSelected(false);
		activeCheckBox.setSelected(true);
		showForm(false);
		updateSystemEnvironmentProtection(null);
	}

	private void showForm(boolean hasSelection) {
		deleteButton.setEnabled(hasSelection);
	}

	/**
	 * Locks this tab against modifying or deleting the {@code $CDF} Environment - the environment
	 * BroadSQL auto-registers for its own connection to the Connections Definition File, a system row
	 * rather than a user-managed one. Mirrors {@code JSettingsFrame#updateConnectionEditability}'s
	 * protection of the {@code $CDF} connection itself, for the same reason. The ID field is already
	 * locked for any existing row regardless (§5.1's immutable-ID rule), so only the remaining fields
	 * need it here.
	 */
	private void updateSystemEnvironmentProtection(String id) {
		boolean isSystemEnvironment = SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(id);
		descrField.setEditable(!isSystemEnvironment);
		commentField.setEditable(!isSystemEnvironment);
		productionCheckBox.setEnabled(!isSystemEnvironment);
		activeCheckBox.setEnabled(!isSystemEnvironment);
		saveButton.setEnabled(!isSystemEnvironment);
		if (isSystemEnvironment) {
			deleteButton.setEnabled(false);
		}
	}// updateSystemEnvironmentProtection

	private void save() {
		if (SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(loadedId)) {
			// Authoritative guard, not just the disabled Save button/field lock above: also protects
			// against JSettingsFrame's "Environments" menu calling triggerSave() directly.
			JOptionPane.showMessageDialog(this, "The '" + SpringPropertiesConfig.CDF_ID + "' Environment is a system entry and cannot be modified.");
			return;
		}
		String id = idField.getText();
		if (StringUtils.isBlank(id)) {
			JOptionPane.showMessageDialog(this, "ID is required.");
			return;
		}
		EnvironmentDefinition environment = new EnvironmentDefinition(id.trim(), descrField.getText(), productionCheckBox.isSelected(), commentField.getText(),
				activeCheckBox.isSelected() ? DatabaseDefinition.STATUS_ACTIVE : DatabaseDefinition.STATUS_INACTIVE);
		try {
			vault.saveEnvironment(environment);
			reload();
			list.setSelectedValue(environment.getId(), true);
			notifyChange();
			JOptionPane.showMessageDialog(this, "Environment '" + environment.getId() + "' saved.");
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
			// JSettingsFrame's "Environments" menu calling triggerDelete() directly.
			JOptionPane.showMessageDialog(this, "The '" + SpringPropertiesConfig.CDF_ID + "' Environment is a system entry and cannot be deleted.");
			return;
		}
		int input = JOptionPane.showConfirmDialog(this, "Deactivate environment '" + id + "'?");
		if (input != JOptionPane.YES_OPTION) {
			return;
		}
		try {
			vault.softDeleteEnvironment(id);
			reload();
			notifyChange();
		} catch (BroadSQLException ex) {
			// Expected path when active connections still reference this environment, or when it's the
			// last active one - see DatabaseDefinitionsVault#softDeleteEnvironment. Not a bug, so no
			// log.error() here.
			JOptionPane.showMessageDialog(this, ex.getLocalizedMessage());
		}
	}

	private void notifyChange() {
		if (onChange != null) {
			onChange.run();
		}
	}
}
