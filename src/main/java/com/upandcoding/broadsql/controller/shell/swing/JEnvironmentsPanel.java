package com.upandcoding.broadsql.controller.shell.swing;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
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
 * <p>Per §5.1, an Environment's ID is immutable after creation - unlike {@link JDatabaseGroupsPanel},
 * the ID field here is only editable while creating a brand-new Environment.
 *
 * <p>SPRINT 0911D gave this tab the same Active/Inactive/All lifecycle model as Connections and
 * Database Groups: a visible "Show:" view switch, a Deactivate/Reactivate/Delete permanently action
 * set (soft delete is "Deactivate", never "Delete"), and - new in this sprint, this tab never had one
 * before - a related-connections table (active and inactive) so a dependency blocking permanent
 * deletion is always discoverable from the UI, symmetric with the Database Groups tab. Not
 * unit-testable itself (Swing, headless CI); the CRUD/lifecycle/guard logic it calls into is covered
 * by {@code TestDatabaseDefinitionsVaultEnvironments}.
 */
public class JEnvironmentsPanel extends JPanel {

	private static final Logger log = LoggerFactory.getLogger(JEnvironmentsPanel.class);

	private DatabaseDefinitionsVault vault;
	private Runnable onChange;
	private Runnable onSelectionChanged;

	private final DefaultListModel<String> listModel = new DefaultListModel<>();
	/** Every Environment, active and inactive alike - {@link DatabaseDefinitionsVault#getEnvironmentDetails()} already returns both. */
	private final Map<String, EnvironmentDefinition> environmentsById = new LinkedHashMap<>();

	private SettingsViewMode viewMode = SettingsViewMode.ACTIVE;

	/** The ID of the environment currently loaded into the form, or {@code null} for a new/unsaved one. */
	private String loadedId;

	private JList<String> list;
	private SettingsFilterBar filterBar;
	private JTextField idField;
	private JTextField descrField;
	private JTextField commentField;
	private JCheckBox productionCheckBox;
	private JCheckBox activeCheckBox;
	private JButton newButton;
	private JButton saveButton;
	private JButton deactivateButton;
	private JButton reactivateButton;
	private JButton hardDeleteButton;
	private DefaultTableModel connectionsTableModel;
	private JTable connectionsTable;

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
	 * Called whenever the current selection or view mode changes - lets {@link JSettingsFrame} keep
	 * its shared Edit/View menu enablement in sync with this tab, when it is the active one (SPRINT
	 * 0911D).
	 */
	public void setOnSelectionChanged(Runnable onSelectionChanged) {
		this.onSelectionChanged = onSelectionChanged;
	}

	/**
	 * Entry points for {@link JSettingsFrame}'s shared File/Edit/View menu, which drives this tab's
	 * actions remotely whenever it is the selected tab - mirrors how the old per-entity "Environments"
	 * menu drove this tab's buttons directly.
	 */
	public void triggerNew() {
		newEnvironment();
	}

	public void triggerSave() {
		save();
	}

	public void triggerDeactivate() {
		deactivate();
	}

	public void triggerReactivate() {
		reactivate();
	}

	public void triggerHardDelete() {
		hardDelete();
	}

	public void triggerSetViewMode(SettingsViewMode mode) {
		setViewMode(mode);
	}

	public SettingsViewMode getViewMode() {
		return viewMode;
	}

	/** @return {@code true} if exactly one row is selected and it is not the {@code $CDF} system environment. */
	public boolean hasEditableSelection() {
		String id = list.getSelectedValue();
		return id != null && !SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(id);
	}

	public boolean isSelectedActive() {
		String id = list.getSelectedValue();
		EnvironmentDefinition environment = id == null ? null : environmentsById.get(id);
		return environment != null && environment.isActive();
	}

	/**
	 * Root layout: {@link SettingsFilterBar} spans the full tab width in {@code NORTH} - outside and
	 * above the {@code JSplitPane} - with the list/form content padded and centered below it (GUI-polish
	 * corrective pass, requirement 5: same structural fix as {@link JDatabaseGroupsPanel#buildUi()} -
	 * see its javadoc for why the old per-side "Show:" row could clip "All").
	 */
	private void buildUi() {
		setLayout(new BorderLayout());

		filterBar = new SettingsFilterBar("Environments", false);
		filterBar.setOnViewChange(this::setViewMode);
		add(filterBar, BorderLayout.NORTH);

		list = new JList<>(listModel);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new JDatabaseGroupsPanel.StatusAwareListCellRenderer(id -> {
			EnvironmentDefinition environment = environmentsById.get(id);
			return environment == null || environment.isActive();
		}));
		list.addListSelectionListener(evt -> {
			if (!evt.getValueIsAdjusting()) {
				loadSelectionIntoForm();
				notifySelectionChanged();
			}
		});
		JScrollPane listScroll = new JScrollPane(list);
		listScroll.setMinimumSize(new Dimension(100, 50));
		listScroll.setBorder(BorderFactory.createLineBorder(javax.swing.UIManager.getColor("Component.borderColor")));

		JPanel content = new JPanel(new BorderLayout());
		content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, buildFormPanel());
		splitPane.setDividerLocation(220);
		content.add(splitPane, BorderLayout.CENTER);
		add(content, BorderLayout.CENTER);
	}

	private JPanel buildFormPanel() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(5, 6, 5, 6);
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

		newButton = new JButton("New");
		newButton.addActionListener(e -> newEnvironment());
		saveButton = new JButton("Save");
		saveButton.addActionListener(e -> save());
		deactivateButton = new JButton("Deactivate");
		deactivateButton.addActionListener(e -> deactivate());
		reactivateButton = new JButton("Reactivate");
		reactivateButton.addActionListener(e -> reactivate());
		hardDeleteButton = new JButton("Delete permanently");
		hardDeleteButton.addActionListener(e -> hardDelete());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		buttons.add(newButton);
		buttons.add(saveButton);
		buttons.add(deactivateButton);
		buttons.add(reactivateButton);
		buttons.add(hardDeleteButton);

		c.gridx = 0;
		c.gridy = 5;
		c.gridwidth = 2;
		c.insets = new Insets(14, 6, 5, 6);
		panel.add(buttons, c);
		c.insets = new Insets(5, 6, 5, 6);

		connectionsTableModel = new DefaultTableModel(new Object[] { "Database Group", "Connection", "Status" }, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};
		connectionsTable = new JTable(connectionsTableModel);
		connectionsTable.setRowHeight(24);
		connectionsTable.setShowVerticalLines(false);
		connectionsTable.setDefaultRenderer(Object.class, new JDatabaseGroupsPanel.StatusColumnRowRenderer(connectionsTableModel, 2));
		JScrollPane connectionsScroll = new JScrollPane(connectionsTable);
		connectionsScroll.setBorder(BorderFactory.createTitledBorder("Connections in this environment (active and inactive)"));
		connectionsScroll.setMinimumSize(new Dimension(200, 100));

		c.gridx = 0;
		c.gridy = 6;
		c.weighty = 1;
		c.fill = GridBagConstraints.BOTH;
		panel.add(connectionsScroll, c);

		return panel;
	}

	/**
	 * Switches between the three mutually-exclusive views (SPRINT 0911D, requirement 2) - re-filters
	 * {@code listModel} from the already-loaded {@link #environmentsById} (which always holds every
	 * environment, active and inactive - {@link DatabaseDefinitionsVault#getEnvironmentDetails()}
	 * returns both), so this never needs a fresh DAO round-trip.
	 */
	private void setViewMode(SettingsViewMode mode) {
		viewMode = mode;
		filterBar.setViewMode(mode);
		applyViewFilter();
		notifySelectionChanged();
	}

	private void applyViewFilter() {
		String previouslySelected = list.getSelectedValue();
		listModel.clear();
		for (EnvironmentDefinition environment : environmentsById.values()) {
			if (matchesView(environment)) {
				listModel.addElement(environment.getId());
			}
		}
		if (previouslySelected != null && listModel.contains(previouslySelected)) {
			list.setSelectedValue(previouslySelected, true);
		} else {
			newEnvironment();
		}
	}

	private boolean matchesView(EnvironmentDefinition environment) {
		switch (viewMode) {
		case ACTIVE:
			return environment.isActive();
		case INACTIVE:
			return !environment.isActive();
		default:
			return true;
		}
	}

	private void reload() {
		if (vault == null) {
			return;
		}
		String previouslySelected = list.getSelectedValue();
		try {
			List<EnvironmentDefinition> environments = vault.getEnvironmentDetails();
			environmentsById.clear();
			for (EnvironmentDefinition environment : environments) {
				environmentsById.put(environment.getId(), environment);
			}
			applyViewFilter();
			if (previouslySelected != null && environmentsById.containsKey(previouslySelected)) {
				list.setSelectedValue(previouslySelected, true);
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
		reloadConnectionsTable(id);
		showForm(true);
		updateActionState(environment);
	}

	private void reloadConnectionsTable(String environmentId) {
		connectionsTableModel.setRowCount(0);
		try {
			for (DatabaseDefinition connection : vault.getAllConnectionsForEnvironment(environmentId)) {
				connectionsTableModel.addRow(new Object[] { connection.getDatabaseGroup(), connection.getId(),
						DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(connection.getStatus()) ? "Active" : "Inactive" });
			}
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
		}
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
		connectionsTableModel.setRowCount(0);
		showForm(false);
		updateActionState(null);
	}

	private void showForm(boolean hasSelection) {
		// Intentionally left minimal - updateActionState is the single place that decides what is
		// enabled, since that depends on more than just "is something selected".
	}

	/**
	 * Single place deciding what this tab's buttons can do, given the currently-loaded record (or
	 * {@code null} for "new") - mirrors {@code JDatabaseGroupsPanel#updateActionState}. The ID field is
	 * already locked for any existing row regardless (§5.1's immutable-ID rule), so only the remaining
	 * fields need the editability check here.
	 */
	private void updateActionState(EnvironmentDefinition environment) {
		boolean isSystemEnvironment = environment != null && SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(environment.getId());
		boolean isNew = environment == null;
		boolean isActive = isNew || environment.isActive();
		boolean editable = !isSystemEnvironment && isActive;

		descrField.setEditable(editable);
		commentField.setEditable(editable);
		productionCheckBox.setEnabled(editable);
		activeCheckBox.setEnabled(editable);

		newButton.setEnabled(true);
		saveButton.setEnabled(editable);
		deactivateButton.setEnabled(!isSystemEnvironment && !isNew && isActive);
		reactivateButton.setEnabled(!isSystemEnvironment && !isNew && !isActive);
		hardDeleteButton.setEnabled(!isSystemEnvironment && !isNew && !isActive);
	}// updateActionState

	private void save() {
		if (SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(loadedId)) {
			// Authoritative guard, not just the disabled Save button/field lock above: also protects
			// against JSettingsFrame's shared Edit menu calling triggerSave() directly.
			JOptionPane.showMessageDialog(this, "The '" + SpringPropertiesConfig.CDF_ID + "' Environment is a system entry and cannot be modified.");
			return;
		}
		String id = idField.getText();
		if (StringUtils.isBlank(id)) {
			JOptionPane.showMessageDialog(this, "ID is required.");
			return;
		}
		// Save keeps the environment's current lifecycle status - status transitions go only through
		// the dedicated Deactivate/Reactivate actions below (SPRINT 0911D, requirement 7: Save
		// validation must stay separate from Deactivate/Reactivate validation).
		EnvironmentDefinition environment = new EnvironmentDefinition(id.trim(), descrField.getText(), productionCheckBox.isSelected(), commentField.getText(),
				DatabaseDefinition.STATUS_ACTIVE);
		try {
			vault.saveEnvironment(environment);
			reload();
			list.setSelectedValue(environment.getId(), true);
			notifyChange();
			JOptionPane.showMessageDialog(this, "Environment '" + environment.getId() + "' saved.");
		} catch (BroadSQLException ex) {
			// Failed save must never discard the form or move the selection (SPRINT 0911D, requirement
			// 5) - just report the error and leave everything exactly as entered.
			log.error(ex.getLocalizedMessage());
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void deactivate() {
		String id = list.getSelectedValue();
		if (id == null) {
			return;
		}
		if (SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(id)) {
			JOptionPane.showMessageDialog(this, "The '" + SpringPropertiesConfig.CDF_ID + "' Environment is a system entry and cannot be deactivated.");
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

	private void reactivate() {
		String id = list.getSelectedValue();
		if (id == null) {
			return;
		}
		try {
			vault.reactivateEnvironment(id);
			JOptionPane.showMessageDialog(this, "Environment '" + id + "' reactivated.");
			reload();
			list.setSelectedValue(id, true);
			notifyChange();
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void hardDelete() {
		String id = list.getSelectedValue();
		if (id == null) {
			return;
		}
		int input = JOptionPane.showConfirmDialog(this, "Permanently delete environment '" + id + "'?\nThis cannot be undone.", "Delete permanently",
				JOptionPane.YES_NO_OPTION);
		if (input != JOptionPane.YES_OPTION) {
			return;
		}
		try {
			vault.hardDeleteEnvironment(id);
			JOptionPane.showMessageDialog(this, "Environment '" + id + "' permanently deleted.");
			reload();
			notifyChange();
		} catch (BroadSQLException ex) {
			// Expected path when the environment is still referenced - see
			// DatabaseDefinitionsVault#hardDeleteEnvironment, which names exactly what is blocking it.
			// Not a bug, so no log.error() here.
			JOptionPane.showMessageDialog(this, ex.getLocalizedMessage());
		}
	}

	private void notifyChange() {
		if (onChange != null) {
			onChange.run();
		}
	}

	private void notifySelectionChanged() {
		if (onSelectionChanged != null) {
			onSelectionChanged.run();
		}
	}
}
