package com.upandcoding.broadsql.controller.shell.swing;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.model.UserScriptLine;

/**
 * "Login Scripts" tab of the CONFIG screen ({@link JSettingsFrame}) - the GUI proposed in
 * docs/TODO.md item 13. Before this, the only way to create or edit a connection's login script
 * ({@code USERS_SCRIPT}, run automatically on connect by
 * {@code CommandInterpreter.executeUserScripts()}) was raw SQL against the CDF.
 *
 * <p>Scoped per-connection, matching the engine's actual (not merely named) behavior - see
 * {@link DatabaseDefinitionsVault#getUserLoginScript}. Reorder is up/down buttons rather than
 * drag-and-drop, and Save replaces the whole script at once (see
 * {@link DatabaseDefinitionsVault#saveUserScriptLines}) rather than per-row CRUD.
 *
 * <p>Not unit-testable itself (Swing, headless CI); the persistence it calls into is covered by
 * {@code TestDatabaseDefinitionsVaultUserScripts}.
 */
public class JUserScriptsPanel extends JPanel {

	private static final Logger log = LoggerFactory.getLogger(JUserScriptsPanel.class);

	private DatabaseDefinitionsVault vault;
	private String connectionId;
	private final ScriptTableModel tableModel = new ScriptTableModel();

	private JTable table;
	private JLabel statusLabel;
	private JButton addButton;
	private JButton deleteButton;
	private JButton moveUpButton;
	private JButton moveDownButton;
	private JButton saveButton;

	public JUserScriptsPanel() {
		buildUi();
		setConnectionId(null);
	}

	public void setVault(DatabaseDefinitionsVault vault) {
		this.vault = vault;
	}

	/**
	 * Switches the panel to the given connection's script (or clears/disables it if {@code id} is
	 * blank, e.g. while a not-yet-saved new connection is being entered).
	 */
	public void setConnectionId(String id) {
		this.connectionId = StringUtils.isNotBlank(id) ? id : null;
		if (this.connectionId == null) {
			tableModel.setRows(new ArrayList<>());
			statusLabel.setText("Select and save a connection first.");
		} else {
			reload();
		}
		boolean enabled = this.connectionId != null;
		addButton.setEnabled(enabled);
		saveButton.setEnabled(enabled);
		updateRowActionButtons();
	}

	private void buildUi() {
		setLayout(new BorderLayout(4, 4));

		statusLabel = new JLabel(" ");
		add(statusLabel, BorderLayout.NORTH);

		table = new JTable(tableModel);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getSelectionModel().addListSelectionListener(evt -> updateRowActionButtons());
		// Enabled is a checkbox and never needs more than a fixed, narrow width - capping it (rather
		// than just setting a preferred width) stops AUTO_RESIZE_ALL_COLUMNS from ever handing it the
		// growing window's extra space instead of the SQL command/comment columns.
		table.getColumnModel().getColumn(0).setMinWidth(55);
		table.getColumnModel().getColumn(0).setMaxWidth(60);
		table.getColumnModel().getColumn(0).setPreferredWidth(60);
		table.getColumnModel().getColumn(1).setPreferredWidth(420);
		table.getColumnModel().getColumn(2).setPreferredWidth(220);
		add(new JScrollPane(table), BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
		addButton = new JButton("Add line");
		addButton.addActionListener(e -> addLine());
		deleteButton = new JButton("Delete line");
		deleteButton.addActionListener(e -> deleteLine());
		moveUpButton = new JButton("Move up");
		moveUpButton.addActionListener(e -> moveLine(-1));
		moveDownButton = new JButton("Move down");
		moveDownButton.addActionListener(e -> moveLine(1));
		saveButton = new JButton("Save script");
		saveButton.addActionListener(e -> save());
		buttons.add(addButton);
		buttons.add(deleteButton);
		buttons.add(moveUpButton);
		buttons.add(moveDownButton);
		buttons.add(saveButton);
		add(buttons, BorderLayout.SOUTH);
	}

	private void reload() {
		if (vault == null || connectionId == null) {
			return;
		}
		try {
			tableModel.setRows(vault.getUserScriptLines(vault.getFileName(), vault.getPassword(), connectionId));
			statusLabel.setText("Login script for '" + connectionId + "' - runs in the order shown, top to bottom.");
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
			JOptionPane.showMessageDialog(this, "Could not load the login script: " + ex.getLocalizedMessage());
		}
		updateRowActionButtons();
	}

	private void addLine() {
		tableModel.addRow(new UserScriptLine(connectionId, "", 0, UserScriptLine.STATUS_ACTIVE, null));
		int newRow = tableModel.getRowCount() - 1;
		table.getSelectionModel().setSelectionInterval(newRow, newRow);
	}

	private void deleteLine() {
		int row = table.getSelectedRow();
		if (row >= 0) {
			tableModel.removeRow(row);
		}
		updateRowActionButtons();
	}

	private void moveLine(int delta) {
		int row = table.getSelectedRow();
		if (row < 0) {
			return;
		}
		int target = row + delta;
		if (target < 0 || target >= tableModel.getRowCount()) {
			return;
		}
		tableModel.swapRows(row, target);
		table.getSelectionModel().setSelectionInterval(target, target);
	}

	private void save() {
		if (connectionId == null) {
			return;
		}
		if (table.isEditing()) {
			table.getCellEditor().stopCellEditing();
		}
		try {
			vault.saveUserScriptLines(connectionId, tableModel.getRows());
			reload();
			JOptionPane.showMessageDialog(this, "Login script for '" + connectionId + "' saved.");
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void updateRowActionButtons() {
		boolean hasConnection = connectionId != null;
		int row = table == null ? -1 : table.getSelectedRow();
		deleteButton.setEnabled(hasConnection && row >= 0);
		moveUpButton.setEnabled(hasConnection && row > 0);
		moveDownButton.setEnabled(hasConnection && row >= 0 && row < tableModel.getRowCount() - 1);
	}

	private static final class ScriptTableModel extends AbstractTableModel {

		private static final String[] COLUMNS = { "Enabled", "SQL command", "Comment" };

		private List<UserScriptLine> rows = new ArrayList<>();

		void setRows(List<UserScriptLine> rows) {
			this.rows = new ArrayList<>(rows);
			fireTableDataChanged();
		}

		List<UserScriptLine> getRows() {
			return rows;
		}

		void addRow(UserScriptLine line) {
			rows.add(line);
			fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
		}

		void removeRow(int index) {
			rows.remove(index);
			fireTableRowsDeleted(index, index);
		}

		void swapRows(int a, int b) {
			Collections.swap(rows, a, b);
			fireTableRowsUpdated(Math.min(a, b), Math.max(a, b));
		}

		@Override
		public int getRowCount() {
			return rows.size();
		}

		@Override
		public int getColumnCount() {
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column) {
			return COLUMNS[column];
		}

		@Override
		public Class<?> getColumnClass(int columnIndex) {
			return columnIndex == 0 ? Boolean.class : String.class;
		}

		@Override
		public boolean isCellEditable(int rowIndex, int columnIndex) {
			return true;
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			UserScriptLine line = rows.get(rowIndex);
			switch (columnIndex) {
			case 0:
				return line.isActive();
			case 1:
				return line.getSqlCommand();
			case 2:
				return line.getSqlComment();
			default:
				return null;
			}
		}

		@Override
		public void setValueAt(Object value, int rowIndex, int columnIndex) {
			UserScriptLine line = rows.get(rowIndex);
			switch (columnIndex) {
			case 0:
				line.setStatusId(Boolean.TRUE.equals(value) ? UserScriptLine.STATUS_ACTIVE : UserScriptLine.STATUS_INACTIVE);
				break;
			case 1:
				line.setSqlCommand(String.valueOf(value));
				break;
			case 2:
				line.setSqlComment(String.valueOf(value));
				break;
			default:
				break;
			}
			fireTableCellUpdated(rowIndex, columnIndex);
		}
	}
}
