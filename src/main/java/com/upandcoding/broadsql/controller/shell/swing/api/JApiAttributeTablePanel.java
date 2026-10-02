package com.upandcoding.broadsql.controller.shell.swing.api;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * The one shared, reusable {@code Name / Value [/ Secret] [/ parameter metadata] / Enabled} table
 * every list of variables, headers, or parameters in {@code CONFIG API} uses (docs/SPRINT XT02-sub
 * sprint 5 - API Configuration GUI + Bruno YAML Round-trip.md, sections 11/19/24/25/26) - environment
 * variables, API/folder/endpoint variables and headers, endpoint query/path parameters. Written once
 * here rather than duplicated per screen, matching this codebase's general "one place, not five
 * near-identical copies" convention (the same reasoning {@link ApiAttribute} itself already follows
 * for its five owner/kind combinations).
 *
 * <p>Query/path parameters intentionally never enable the Secret column (they have no meaningful secret
 * semantics in this model - {@link com.upandcoding.broadsql.dao.api.bruno.BrunoCollectionImporter} never sets
 * one), and section 24 explicitly requires two <em>separate</em> tables for query vs. path parameters
 * rather than a combined one - so the endpoint Parameters tab uses two instances of this same class, one
 * per kind, exactly like Variables and Headers each get their own.
 *
 * <p><b>Parameter metadata columns</b> (SPRINT XT02A, URL-Native API Execution, section 9.2) - Required/
 * Type/Default/Allowed Values - are opt-in via the second constructor argument, used only by the two
 * query/path parameter instances; Variables/Headers keep their original three/four-column shape
 * unchanged. This is the same {@link ApiAttribute} row either way (section 2.6: one persisted model,
 * not a second parallel one) - {@link #getAttributes} always round-trips every field the constructor
 * enabled, whether or not this panel instance edits it, so a value set through another path (e.g. a
 * Bruno re-import) is never silently dropped just because this table doesn't expose a column for it.
 *
 * <p>Secret masking (section 11: "Provide an intentional reveal/edit mechanism") is a single "Show
 * values" checkbox toggling every secret cell in the table at once, rather than a per-row reveal control
 * - simple, and consistent with never rendering a secret value in an incidental/passive view (unchecked
 * by default on every screen this appears on).
 *
 * <p>Not unit-testable itself (Swing, headless CI) - the value it holds is validated through
 * {@link com.upandcoding.broadsql.controller.shell.swing.api.form.ApiAttributeListValidator}, which is fully
 * covered by {@code TestApiAttributeListValidator}, matching this codebase's established Swing-panel
 * testing precedent.
 */
public class JApiAttributeTablePanel extends JPanel {

	private static final String MASK = "••••••••";

	private final DefaultTableModel tableModel;
	private final JTable table;
	private final boolean secretColumn;
	private final boolean parameterMetadata;
	private final int secretColumnIndex;
	private final int requiredColumnIndex;
	private final int typeColumnIndex;
	private final int defaultColumnIndex;
	private final int allowedColumnIndex;
	private final int enabledColumnIndex;
	private JCheckBox revealSecretsCheckBox;
	private Runnable onChange = () -> {
	};
	private Runnable onNameValueEnabledChange = () -> {
	};

	public JApiAttributeTablePanel(boolean secretColumn) {
		this(secretColumn, false);
	}

	public JApiAttributeTablePanel(boolean secretColumn, boolean parameterMetadata) {
		this.secretColumn = secretColumn;
		this.parameterMetadata = parameterMetadata;
		List<String> columns = new ArrayList<>();
		columns.add("Name"); // 0
		columns.add("Value"); // 1
		int next = 2;
		if (secretColumn) {
			secretColumnIndex = next++;
			columns.add("Secret");
		} else {
			secretColumnIndex = -1;
		}
		if (parameterMetadata) {
			requiredColumnIndex = next++;
			columns.add("Required");
			typeColumnIndex = next++;
			columns.add("Type");
			defaultColumnIndex = next++;
			columns.add("Default");
			allowedColumnIndex = next++;
			columns.add("Allowed Values");
		} else {
			requiredColumnIndex = -1;
			typeColumnIndex = -1;
			defaultColumnIndex = -1;
			allowedColumnIndex = -1;
		}
		enabledColumnIndex = next;
		columns.add("Enabled");

		tableModel = new DefaultTableModel(columns.toArray(), 0) {
			@Override
			public Class<?> getColumnClass(int columnIndex) {
				String name = getColumnName(columnIndex);
				return ("Secret".equals(name) || "Required".equals(name) || "Enabled".equals(name)) ? Boolean.class : String.class;
			}

			@Override
			public boolean isCellEditable(int row, int column) {
				return true;
			}
		};
		// Fires on every mutation - cell edit, row add/remove - including a programmatic setAttributes()
		// call (a load()), which callers wanting change notifications only for real user edits must guard
		// against themselves (API Quality and UX Consolidation sprint, Phase 2 - see
		// JApiEndpointEditorPanel's URL/parameter synchronization for the guarded consumer).
		tableModel.addTableModelListener(e -> {
			onChange.run();
			if (affectsNameValueOrEnabled(e)) {
				onNameValueEnabledChange.run();
			}
		});
		table = new JTable(tableModel);
		table.setRowHeight(22);
		if (secretColumn) {
			table.getColumnModel().getColumn(1).setCellRenderer(maskingRenderer());
		}
		buildUi();
	}

	/** Notified on every table-model mutation (cell edit, row add/remove) - see the constructor's note on when this also fires for a programmatic {@link #setAttributes} call. */
	public void setOnChange(Runnable onChange) {
		this.onChange = onChange == null ? () -> {
		} : onChange;
	}

	/**
	 * Like {@link #setOnChange} but skipped for edits confined to metadata columns (Secret, Required, Type,
	 * Default, Allowed Values): fires for row insert/delete/whole-table changes and for edits of Name, Value
	 * or Enabled - the only columns a URL composed from the rows can depend on.
	 */
	public void setOnNameValueEnabledChange(Runnable listener) {
		this.onNameValueEnabledChange = listener == null ? () -> {
		} : listener;
	}

	private boolean affectsNameValueOrEnabled(TableModelEvent e) {
		if (e.getType() != TableModelEvent.UPDATE || e.getColumn() == TableModelEvent.ALL_COLUMNS) {
			return true;
		}
		return e.getColumn() == 0 || e.getColumn() == 1 || e.getColumn() == enabledColumnIndex;
	}

	private void buildUi() {
		setLayout(new BorderLayout());
		JScrollPane scroll = new JScrollPane(table);
		scroll.setBorder(BorderFactory.createLineBorder(javax.swing.UIManager.getColor("Component.borderColor")));
		add(scroll, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		JButton addButton = new JButton("+");
		addButton.setToolTipText("Add row");
		addButton.addActionListener(e -> addRow());
		JButton removeButton = new JButton("-");
		removeButton.setToolTipText("Remove selected row");
		removeButton.addActionListener(e -> removeSelectedRow());
		buttons.add(addButton);
		buttons.add(removeButton);
		if (secretColumn) {
			revealSecretsCheckBox = new JCheckBox("Show values");
			revealSecretsCheckBox.addActionListener(e -> table.repaint());
			buttons.add(revealSecretsCheckBox);
		}
		add(buttons, BorderLayout.SOUTH);
	}

	private DefaultTableCellRenderer maskingRenderer() {
		return new DefaultTableCellRenderer() {
			@Override
			public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
				boolean isSecret = secretColumn && Boolean.TRUE.equals(tbl.getValueAt(row, secretColumnIndex));
				boolean reveal = revealSecretsCheckBox != null && revealSecretsCheckBox.isSelected();
				Object displayValue = (isSecret && !reveal && value != null && !String.valueOf(value).isEmpty()) ? MASK : value;
				return super.getTableCellRendererComponent(tbl, displayValue, isSelected, hasFocus, row, column);
			}
		};
	}

	private void addRow() {
		Object[] row = new Object[tableModel.getColumnCount()];
		row[0] = "";
		row[1] = "";
		if (secretColumn) {
			row[secretColumnIndex] = Boolean.FALSE;
		}
		if (parameterMetadata) {
			row[requiredColumnIndex] = Boolean.FALSE;
			row[typeColumnIndex] = "string";
			row[defaultColumnIndex] = "";
			row[allowedColumnIndex] = "";
		}
		row[enabledColumnIndex] = Boolean.TRUE;
		tableModel.addRow(row);
	}

	private void removeSelectedRow() {
		int row = table.getSelectedRow();
		if (row >= 0) {
			// Commit any in-progress cell edit first, otherwise removing the row it belongs to can leave
			// the JTable's editor state referencing a row index that no longer exists.
			commitCurrentEdit();
			tableModel.removeRow(row);
		}
	}

	public void setAttributes(List<ApiAttribute> attributes) {
		tableModel.setRowCount(0);
		for (ApiAttribute attr : attributes) {
			Object[] row = new Object[tableModel.getColumnCount()];
			row[0] = attr.getName();
			row[1] = attr.getValue();
			if (secretColumn) {
				row[secretColumnIndex] = attr.isSecret();
			}
			if (parameterMetadata) {
				row[requiredColumnIndex] = attr.isRequired();
				row[typeColumnIndex] = attr.getParamType();
				row[defaultColumnIndex] = attr.getDefaultValue();
				row[allowedColumnIndex] = attr.getAllowedValues();
			}
			row[enabledColumnIndex] = attr.isEnabled();
			tableModel.addRow(row);
		}
	}

	/**
	 * Commits any in-progress cell edit. Only for lifecycle operations (save, remove row, reconcile)
	 * that need the final value - never call it from a table-model callback: {@code JTable.editingStopped}
	 * invokes {@code setValueAt} <i>before</i> removing the editor, so {@code isEditing()} is still true
	 * inside the resulting model event and a nested {@code stopCellEditing()} re-enters
	 * {@code editingStopped} indefinitely (the {@code StackOverflowError} on toggling Required).
	 */
	public void commitCurrentEdit() {
		if (table.isEditing()) {
			table.getCellEditor().stopCellEditing();
		}
	}

	/** {@link #commitCurrentEdit()} then {@link #getAttributes}: the value to persist. */
	public List<ApiAttribute> getCommittedAttributes(ApiOwnerType ownerType, String ownerId, ApiAttributeKind kind) {
		commitCurrentEdit();
		return getAttributes(ownerType, ownerId, kind);
	}

	/**
	 * A pure read of the table model: never touches the cell editor, so it is safe from any
	 * table-model callback. An in-progress (uncommitted) edit is not included; use
	 * {@link #getCommittedAttributes} when persisting.
	 */
	public List<ApiAttribute> getAttributes(ApiOwnerType ownerType, String ownerId, ApiAttributeKind kind) {
		List<ApiAttribute> result = new ArrayList<>();
		for (int row = 0; row < tableModel.getRowCount(); row++) {
			String name = (String) tableModel.getValueAt(row, 0);
			String value = (String) tableModel.getValueAt(row, 1);
			boolean secret = secretColumn && Boolean.TRUE.equals(tableModel.getValueAt(row, secretColumnIndex));
			boolean enabled = Boolean.TRUE.equals(tableModel.getValueAt(row, enabledColumnIndex));
			ApiAttribute attr = new ApiAttribute(ownerType, ownerId, kind, name, value, secret);
			attr.setEnabled(enabled);
			attr.setSortOrder(row);
			if (parameterMetadata) {
				attr.setRequired(Boolean.TRUE.equals(tableModel.getValueAt(row, requiredColumnIndex)));
				String type = (String) tableModel.getValueAt(row, typeColumnIndex);
				attr.setParamType(type == null || type.isBlank() ? "string" : type.trim());
				attr.setDefaultValue((String) tableModel.getValueAt(row, defaultColumnIndex));
				attr.setAllowedValues((String) tableModel.getValueAt(row, allowedColumnIndex));
			}
			result.add(attr);
		}
		return result;
	}

	/**
	 * A deterministic, owner-independent string representation of every row's current content - the
	 * value-snapshot dirty-tracking building block ({@code JSettingsFrame}'s established
	 * snapshot-after-populate pattern, reused here rather than a listener-driven boolean flag) for
	 * whichever panel embeds this table. Deliberately not based on {@link ApiAttribute} equality
	 * ({@link ApiAttribute} has no {@code equals}/{@code hashCode}) or on {@link #getAttributes}
	 * (which requires an owner type/id irrelevant to a pure content comparison).
	 *
	 * <p><b>Never commits/stops an active cell editor</b> (SPRINT XT02B acceptance correction) - unlike
	 * {@link #getAttributes}/{@link #removeSelectedRow}, which legitimately need the final, committed
	 * value before persisting or removing a row, this method is also polled purely passively every
	 * ~500ms by {@code JApiSettingsFrame}'s title/Save-menu dirty indicator {@code Timer}. Calling
	 * {@code stopCellEditing()} from there forcibly ended whatever cell edit the user had just started
	 * (a double-click to edit a Value cell) within half a second, every time, regardless of filtering -
	 * the reported "editing mode appears to exit immediately" symptom. The currently-edited cell's
	 * in-progress value is read directly from the live editor component instead, via {@code
	 * getCellEditorValue()}, so the dirty snapshot still reflects an unsaved in-progress edit without
	 * ever touching the editor's open/closed state.
	 */
	public String snapshotKey() {
		int editingRow = table.isEditing() ? table.getEditingRow() : -1;
		int editingColumn = table.isEditing() ? table.getEditingColumn() : -1;
		StringBuilder key = new StringBuilder();
		for (int row = 0; row < tableModel.getRowCount(); row++) {
			for (int col = 0; col < tableModel.getColumnCount(); col++) {
				Object value = (row == editingRow && col == editingColumn)
						? table.getCellEditor().getCellEditorValue()
						: tableModel.getValueAt(row, col);
				key.append(value).append('');
			}
			key.append('');
		}
		return key.toString();
	}
}
