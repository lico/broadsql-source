package com.upandcoding.broadsql.controller.shell.swing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
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
 * <p>SPRINT 0911D gave this tab the same Active/Inactive/All lifecycle model as Connections: a
 * visible "Show:" view switch (not menu-only), a Deactivate/Reactivate/Delete permanently action
 * set (soft delete is called "Deactivate", never "Delete" - "Delete" now means an actual, permanent
 * removal), and a related-connections table that includes inactive connections (with an explicit
 * Status column) so a dependency blocking permanent deletion is always discoverable from the UI. Not
 * unit-testable itself (Swing, headless CI); the CRUD/lifecycle/guard logic it calls into is covered
 * by {@code TestDatabaseDefinitionsVaultGroups}.
 */
public class JDatabaseGroupsPanel extends JPanel {

	private static final Logger log = LoggerFactory.getLogger(JDatabaseGroupsPanel.class);

	private DatabaseDefinitionsVault vault;
	private Runnable onChange;
	private Runnable onSelectionChanged;

	private final DefaultListModel<String> listModel = new DefaultListModel<>();
	/** Every Database Group, active and inactive alike - {@link DatabaseDefinitionsVault#getGroupDetails()} already returns both. */
	private final Map<String, DatabaseGroupDefinition> groupsById = new LinkedHashMap<>();

	private SettingsViewMode viewMode = SettingsViewMode.ACTIVE;

	/** The ID of the group currently loaded into the form, or {@code null} for a new/unsaved one. */
	private String loadedId;

	private JList<String> list;
	private SettingsFilterBar filterBar;
	private JTextField idField;
	private JTextField descrField;
	private JTextField commentField;
	private JCheckBox activeCheckBox;
	private JButton newButton;
	private JButton saveButton;
	private JButton deactivateButton;
	private JButton reactivateButton;
	private JButton hardDeleteButton;
	private DefaultTableModel connectionsTableModel;
	private JTable connectionsTable;

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
	 * Called whenever the current selection or view mode changes - lets {@link JSettingsFrame} keep
	 * its shared Edit/View menu enablement in sync with this tab, when it is the active one (SPRINT
	 * 0911D).
	 */
	public void setOnSelectionChanged(Runnable onSelectionChanged) {
		this.onSelectionChanged = onSelectionChanged;
	}

	/**
	 * Entry points for {@link JSettingsFrame}'s shared File/Edit/View menu, which drives this tab's
	 * actions remotely whenever it is the selected tab - mirrors how the old per-entity "Database
	 * Groups" menu drove this tab's buttons directly.
	 */
	public void triggerNew() {
		newGroup();
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

	/** @return {@code true} if exactly one row is selected and it is not the {@code $CDF} system group. */
	public boolean hasEditableSelection() {
		String id = list.getSelectedValue();
		return id != null && !SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(id);
	}

	public boolean isSelectedActive() {
		String id = list.getSelectedValue();
		DatabaseGroupDefinition group = id == null ? null : groupsById.get(id);
		return group != null && group.isActive();
	}

	/**
	 * Root layout: {@link SettingsFilterBar} spans the full tab width in {@code NORTH} - outside and
	 * above the {@code JSplitPane} - with the list/form content padded and centered below it (GUI-polish
	 * corrective pass, requirement 4: the "Show:" row used to live inside the narrow left-hand
	 * {@code listWrapper}, inside the split pane's left side, so "All" could be clipped whenever the
	 * divider left that side narrower than the row's preferred width; it now lives above the split pane
	 * entirely, exactly mirroring {@link JSettingsFrame}'s Connections tab and
	 * {@link JEnvironmentsPanel}).
	 */
	private void buildUi() {
		setLayout(new BorderLayout());

		filterBar = new SettingsFilterBar("Database Groups", false);
		filterBar.setOnViewChange(this::setViewMode);
		add(filterBar, BorderLayout.NORTH);

		list = new JList<>(listModel);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new StatusAwareListCellRenderer(id -> {
			DatabaseGroupDefinition group = groupsById.get(id);
			return group == null || group.isActive();
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

		newButton = new JButton("New");
		newButton.addActionListener(e -> newGroup());
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
		c.gridy = 4;
		c.gridwidth = 2;
		c.insets = new Insets(14, 6, 5, 6);
		panel.add(buttons, c);
		c.insets = new Insets(5, 6, 5, 6);

		connectionsTableModel = new DefaultTableModel(new Object[] { "Environment", "Connection", "Status" }, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};
		connectionsTable = new JTable(connectionsTableModel);
		connectionsTable.setRowHeight(24);
		connectionsTable.setShowVerticalLines(false);
		connectionsTable.setDefaultRenderer(Object.class, new StatusColumnRowRenderer(connectionsTableModel, 2));
		JScrollPane connectionsScroll = new JScrollPane(connectionsTable);
		connectionsScroll.setBorder(BorderFactory.createTitledBorder("Connections in this group (active and inactive)"));
		connectionsScroll.setMinimumSize(new Dimension(200, 100));

		c.gridy = 5;
		c.weighty = 1;
		c.fill = GridBagConstraints.BOTH;
		panel.add(connectionsScroll, c);

		return panel;
	}

	/**
	 * Switches between the three mutually-exclusive views (SPRINT 0911D, requirement 2) - re-filters
	 * {@code listModel} from the already-loaded {@link #groupsById} (which always holds every group,
	 * active and inactive - {@link DatabaseDefinitionsVault#getGroupDetails()} returns both), so this
	 * never needs a fresh DAO round-trip.
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
		for (DatabaseGroupDefinition group : groupsById.values()) {
			if (matchesView(group)) {
				listModel.addElement(group.getId());
			}
		}
		if (previouslySelected != null && listModel.contains(previouslySelected)) {
			list.setSelectedValue(previouslySelected, true);
		} else {
			newGroup();
		}
	}

	private boolean matchesView(DatabaseGroupDefinition group) {
		switch (viewMode) {
		case ACTIVE:
			return group.isActive();
		case INACTIVE:
			return !group.isActive();
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
			List<DatabaseGroupDefinition> groups = vault.getGroupDetails();
			groupsById.clear();
			for (DatabaseGroupDefinition group : groups) {
				groupsById.put(group.getId(), group);
			}
			applyViewFilter();
			if (previouslySelected != null && groupsById.containsKey(previouslySelected)) {
				list.setSelectedValue(previouslySelected, true);
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
		// CONNECTIONS row using it, active or inactive. Only meaningful in the Active view - see
		// updateActionState, which locks the whole form for an inactive group (view-only, like an
		// inactive Connection).
		idField.setEditable(true);
		descrField.setText(group.getDescr());
		commentField.setText(group.getComment());
		activeCheckBox.setSelected(group.isActive());
		reloadConnectionsTable(id);
		showForm(true);
		updateActionState(group);
	}

	private void reloadConnectionsTable(String groupId) {
		connectionsTableModel.setRowCount(0);
		try {
			for (DatabaseDefinition connection : vault.getAllConnectionsForGroup(groupId)) {
				connectionsTableModel.addRow(new Object[] { connection.getEnvironment(), connection.getId(),
						DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(connection.getStatus()) ? "Active" : "Inactive" });
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
		updateActionState(null);
	}

	private void showForm(boolean hasSelection) {
		// Intentionally left minimal - updateActionState is the single place that decides what is
		// enabled, since that depends on more than just "is something selected" (view mode, system
		// group protection, active/inactive status).
	}

	/**
	 * Single place deciding what this tab's buttons can do, given the currently-loaded record (or
	 * {@code null} for "new") - the "Database Groups" analogue of
	 * {@code JSettingsFrame#updateConnectionEditability}. Locks every field against modification for
	 * both the {@code $CDF} system group (mirrors {@code JSettingsFrame}'s protection of the
	 * {@code $CDF} connection/group/environment, for the same reason) and, symmetrically with
	 * Connections, any inactive group (view-only - only Reactivate/Delete permanently apply, per
	 * SPRINT 0911D requirement 5's "no command currently available in Settings may be lost" while
	 * still following requirement 4/5's consistent lifecycle model).
	 */
	private void updateActionState(DatabaseGroupDefinition group) {
		boolean isSystemGroup = group != null && SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(group.getId());
		boolean isNew = group == null;
		boolean isActive = isNew || group.isActive();
		boolean editable = !isSystemGroup && isActive;

		idField.setEditable(editable);
		descrField.setEditable(editable);
		commentField.setEditable(editable);
		activeCheckBox.setEnabled(editable);

		newButton.setEnabled(true);
		saveButton.setEnabled(editable);
		deactivateButton.setEnabled(!isSystemGroup && !isNew && isActive);
		reactivateButton.setEnabled(!isSystemGroup && !isNew && !isActive);
		hardDeleteButton.setEnabled(!isSystemGroup && !isNew && !isActive);
	}// updateActionState

	private void save() {
		if (SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(loadedId)) {
			// Authoritative guard, not just the disabled Save button/field lock above: also protects
			// against JSettingsFrame's shared Edit menu calling triggerSave() directly.
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
		// Save keeps the group's current lifecycle status - the "Active" checkbox is read-only here,
		// deliberately: SPRINT 0911D separates Save-validation from Deactivate/Reactivate (requirement
		// 7), so status transitions go only through the dedicated Deactivate/Reactivate actions below,
		// never silently as a side effect of an unrelated edit.
		DatabaseGroupDefinition group = new DatabaseGroupDefinition(newId, descrField.getText(), commentField.getText(), DatabaseDefinition.STATUS_ACTIVE);
		try {
			vault.saveGroup(group, loadedId);
			reload();
			list.setSelectedValue(group.getId(), true);
			notifyChange();
			JOptionPane.showMessageDialog(this, "Database Group '" + group.getId() + "' saved.");
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
			JOptionPane.showMessageDialog(this, "The '" + SpringPropertiesConfig.CDF_ID + "' Database Group is a system entry and cannot be deactivated.");
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

	private void reactivate() {
		String id = list.getSelectedValue();
		if (id == null) {
			return;
		}
		try {
			vault.reactivateGroup(id);
			JOptionPane.showMessageDialog(this, "Database Group '" + id + "' reactivated.");
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
		int input = JOptionPane.showConfirmDialog(this, "Permanently delete Database Group '" + id + "'?\nThis cannot be undone.", "Delete permanently",
				JOptionPane.YES_NO_OPTION);
		if (input != JOptionPane.YES_OPTION) {
			return;
		}
		try {
			vault.hardDeleteGroup(id);
			JOptionPane.showMessageDialog(this, "Database Group '" + id + "' permanently deleted.");
			reload();
			notifyChange();
		} catch (BroadSQLException ex) {
			// Expected path when the group is still referenced - see DatabaseDefinitionsVault#hardDeleteGroup,
			// which names exactly what is blocking it. Not a bug, so no log.error() here.
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

	/**
	 * Greys out any row whose ID the given predicate reports inactive for - shared by every Settings
	 * tab's list (Database Groups here, Environments in {@link JEnvironmentsPanel}) now that "All"
	 * mixes active and inactive rows together, unlike the old single-status "Inactive connections"
	 * view this pattern originated from ({@code JSettingsFrame}'s pre-0911D {@code ConnectionListCellRenderer}).
	 */
	static final class StatusAwareListCellRenderer extends DefaultListCellRenderer {
		private final java.util.function.Predicate<String> isActive;

		StatusAwareListCellRenderer(java.util.function.Predicate<String> isActive) {
			this.isActive = isActive;
		}

		@Override
		public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
			Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			if (value != null && !isActive.test(String.valueOf(value)) && !isSelected) {
				c.setForeground(Color.GRAY);
			}
			return c;
		}
	}

	/**
	 * Greys out every cell of a table row whose Status column reads "Inactive" - used by the related
	 * connections tables in both {@link JDatabaseGroupsPanel} and {@link JEnvironmentsPanel} (SPRINT
	 * 0911D requirement 11: inactive rows greyed <b>and</b> carry an explicit textual Status, never
	 * color alone).
	 */
	static final class StatusColumnRowRenderer extends javax.swing.table.DefaultTableCellRenderer {
		private final DefaultTableModel model;
		private final int statusColumn;

		StatusColumnRowRenderer(DefaultTableModel model, int statusColumn) {
			this.model = model;
			this.statusColumn = statusColumn;
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
			Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			int modelRow = table.convertRowIndexToModel(row);
			Object status = model.getValueAt(modelRow, statusColumn);
			if (!isSelected) {
				c.setForeground("Inactive".equals(status) ? Color.GRAY : Color.BLACK);
			}
			return c;
		}
	}
}
