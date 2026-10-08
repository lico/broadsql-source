package com.upandcoding.broadsql.controller.shell.output;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import com.upandcoding.broadsql.dao.model.metadata.ColumnMetadata;
import com.upandcoding.broadsql.dao.model.metadata.PrimaryKeyMetadata;
import com.upandcoding.broadsql.dao.model.metadata.SchemaMetadata;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

public class ConsolePrinter {


	@Autowired
	public ShellConsole shellConsole;

	/**
	 * 
	 * @param display
	 * @param size
	 * @param maxCol
	 * @param len
	 */
	/**
	 * Shared by every table-producing command ({@code LIB LIST}/{@code JS LIST}/
	 * their {@code FIND} siblings, {@code SHOW TABLES}/{@code SHOW SCHEMAS}/etc.) -
	 * a fix here fixes every one of them identically, per SPRINT 0917-01's corrective acceptance pass
	 * (defect: {@code LIB LIST}'s table was missing its bottom closing separator; the real bug was here,
	 * in the one shared renderer, not in any single command). Structure:
	 * {@code separator / header / separator / row... / separator} - the closing separator after the
	 * last data row was previously never printed at all (only {@code row == 0} ever emitted one).
	 */
	private void printArrayOnConsole(String[][] display, int size, int maxCol, int[] len) {
		printArrayOnConsole(display, size, maxCol, len, true);
	}

	/** {@code rowCount}: whether the table ends with the "N rows fetched." line (data results) or not (reference tables such as HELP's). */
	private void printArrayOnConsole(String[][] display, int size, int maxCol, int[] len, boolean rowCount) {
		// SPRINT 2409K: geometry from DisplayLayout (display only); NORMAL is the established layout, unchanged
		DisplayLayout.Plan plan = DisplayLayout.plan(len, 1, 1);
		if (plan.isVertical()) {
			printVertically(display, size, maxCol, plan, rowCount);
			return;
		}
		// The border rule itself lives in TableBorders (docs/02. guidelines/TABLE_OUTPUT_STANDARD.md)
		int[] widths = new int[maxCol];
		for (int col = 0; col < maxCol; col++) {
			widths[col] = plan.width(col);
		}
		String separator = TableBorders.separator(widths, plan.gap());
		for (int row = 0; row < size + 1; row++) {
			List<String> cells = new ArrayList<>(maxCol);
			for (int col = 0; col < maxCol; col++) {
				String cell = display[col][row];
				if (cell == null || cell.trim().equals("")) {
					cell = "";
				}
				cells.add(plan.truncates() ? DisplayLayout.fit(cell, plan.width(col)) : cell);
			}
			if (row == 0) {
				shellConsole.writeln(separator);
			}
			shellConsole.writeln(TableBorders.row(cells, widths, plan.gap()));
			if (row == 0) {
				shellConsole.writeln(separator);
			}
		}
		if (size > 0) {
			// With no data row, the separator under the header already closes the table
			shellConsole.writeln(separator);
		}
		shellConsole.writeln("");
		if (rowCount) {
			shellConsole.writeln("" + size + " rows fetched.");
			shellConsole.writeln("");
		}
	}

	/** SPRINT 2409K: COMPACT's fallback when even narrowed columns cannot fit: one {@code HEADER: value} line per column, a blank line between rows. */
	private void printVertically(String[][] display, int size, int maxCol, DisplayLayout.Plan plan, boolean rowCount) {
		int labelWidth = 0;
		for (int col = 0; col < maxCol; col++) {
			labelWidth = Math.max(labelWidth, display[col][0] == null ? 0 : display[col][0].length());
		}
		int valueWidth = Math.max(10, plan.availableColumns() - labelWidth - 2);
		for (int row = 1; row < size + 1; row++) {
			for (int col = 0; col < maxCol; col++) {
				String value = display[col][row] == null ? "" : display[col][row];
				shellConsole.writeln(StringUtils.rightPad(display[col][0], labelWidth, " ") + ": " + DisplayLayout.fit(value, valueWidth));
			}
			shellConsole.writeln("");
		}
		if (rowCount) {
			shellConsole.writeln("" + size + " rows fetched.");
			shellConsole.writeln("");
		}
	}

	/**
	 * SPRINT 2409K: prints any header + rows grid through the same shared renderer as every other table
	 * of this class (used by the {@code SHOW FK}/{@code REFERENCES}/{@code INDEXES} and
	 * {@code FIND FK}/{@code INDEX} metadata commands). A {@code null} cell prints empty.
	 */
	public void printTable(List<String> headers, List<List<String>> rows) {
		printTable(headers, rows, true);
	}

	/**
	 * As {@link #printTable(List, List)}, for a reference table rather than a data result when {@code rowCount}
	 * is {@code false}: same layout and {@code displaymode} handling, without the "N rows fetched." line.
	 */
	public void printTable(List<String> headers, List<List<String>> rows, boolean rowCount) {
		int maxCol = headers.size();
		String[][] display = new String[maxCol][rows.size() + 1];
		int[] len = new int[maxCol];
		for (int col = 0; col < maxCol; col++) {
			display[col][0] = headers.get(col);
			len[col] = headers.get(col).length();
		}
		for (int row = 0; row < rows.size(); row++) {
			List<String> values = rows.get(row);
			for (int col = 0; col < maxCol; col++) {
				String cell = col < values.size() && values.get(col) != null ? values.get(col) : "";
				display[col][row + 1] = cell;
				len[col] = Math.max(len[col], cell.length());
			}
		}
		printArrayOnConsole(display, rows.size(), maxCol, len, rowCount);
	}

	/**
	 * Prints a list of columns, in the iteration order of {@code columns} ({@code DESCR} passes them in
	 * table order). Size is {@code precision,scale} for DECIMAL and NUMERIC columns, the column size otherwise.
	 */
	public void printColumns(Collection<ColumnMetadata> columns) {
		int MAX_COL = 7;
		String[][] display = new String[MAX_COL][columns.size() + 1];
		display[0][0] = "Name";
		display[1][0] = "Table";
		display[2][0] = "Schema";
		display[3][0] = "Data Type";
		display[4][0] = "Nullable";
		display[5][0] = "Default";
		display[6][0] = "Size";

		int[] len = new int[MAX_COL];
		len[0] = display[0][0].length();
		len[1] = display[1][0].length();
		len[2] = display[2][0].length();
		len[3] = display[3][0].length();
		len[4] = display[4][0].length();
		len[5] = display[5][0].length();
		len[6] = display[6][0].length();

		for (ColumnMetadata column : columns) {
			if (column.getName() != null && column.getName().length() > len[0]) {
				len[0] = column.getName().trim().length();
			}
			if (column.getTable() != null && column.getTable().length() > len[1]) {
				len[1] = column.getTable().trim().length();
			}
			if (column.getSchema() != null && column.getSchema().length() > len[2]) {
				len[2] = column.getSchema().trim().length();
			}
			if (column.getTypeName() != null) {
				String concat = column.getTypeName() + " (" + column.getDataType() + ")";
				if (concat.length() > len[3]) {
					len[3] = concat.trim().length();
				}
			} else {
				String dt = "" + column.getDataType();
				if (dt.length() > len[3])
					len[3] = dt.length();
			}
			if (column.getIsoNullable() != null && (column.getIsoNullable().trim()).length() > len[4]) {
				len[4] = (column.getIsoNullable().trim()).length();
			}
			if (column.getDefaultValue() != null && (column.getDefaultValue().trim()).length() > len[5]) {
				len[5] = (column.getDefaultValue().trim()).length();
			}
			String dt = sizeLabel(column);
			if (dt.length() > len[6])
				len[6] = dt.length();
		}

		int i = 1;
		for (ColumnMetadata columnI : columns) {
			String tableName = columnI.getName();
			if (StringUtils.isBlank(tableName)) {
				display[0][i] = "";
			} else {
				display[0][i] = columnI.getName().trim();
			}
			if (StringUtils.isBlank(columnI.getTable())) {
				display[1][i] = "";
			} else {
				display[1][i] = columnI.getTable();
			}
			if (StringUtils.isBlank(columnI.getSchema())) {
				display[2][i] = "";
			} else {
				display[2][i] = columnI.getSchema();
			}
			display[3][i] = columnI.getTypeName() + " (" + columnI.getDataType() + ")";
			if (StringUtils.isBlank(columnI.getIsoNullable())) {
				display[4][i] = "";
			} else {
				display[4][i] = columnI.getIsoNullable();
			}
			if (StringUtils.isBlank(columnI.getDefaultValue())) {
				display[5][i] = "";
			} else {
				display[5][i] = columnI.getDefaultValue();
			}
			display[6][i] = sizeLabel(columnI);
			i++;
		}

		printArrayOnConsole(display, columns.size(), MAX_COL, len);
	}

	/** {@code precision,scale} for the exact numeric types, where JDBC reports both; the column size otherwise. */
	static String sizeLabel(ColumnMetadata column) {
		boolean exactNumeric = column.getDataType() == Types.DECIMAL || column.getDataType() == Types.NUMERIC;
		return exactNumeric ? column.getColumnSize() + "," + column.getDecimalDigit() : "" + column.getColumnSize();
	}

	/**
	 * Prints a list of primary keys
	 */
	public void printPrimaryKeys(Collection<PrimaryKeyMetadata> columns) {
		int MAX_COL = 5;
		String[][] display = new String[MAX_COL][columns.size() + 1];
		display[0][0] = "Name";
		display[1][0] = "Table";
		display[2][0] = "Schema";
		display[3][0] = "PK Name";
		display[4][0] = "Key Seq";

		int[] len = new int[MAX_COL];
		len[0] = display[0][0].length();
		len[1] = display[1][0].length();
		len[2] = display[2][0].length();
		len[3] = display[3][0].length();
		len[4] = display[4][0].length();

		for (PrimaryKeyMetadata column : columns) {
			if (column.getName() != null && column.getName().length() > len[0]) {
				len[0] = column.getName().trim().length();
			}
			if (column.getTable() != null && column.getTable().length() > len[1]) {
				len[1] = column.getTable().trim().length();
			}
			if (column.getSchema() != null && column.getSchema().length() > len[2]) {
				len[2] = column.getSchema().trim().length();
			}
			if (column.getPkName() != null) {
				String concat = column.getPkName() + " (" + column.getPkName() + ")";
				if (concat.length() > len[3]) {
					len[3] = concat.trim().length();
				}
			} else {
				String dt = "" + column.getPkName();
				if (dt.length() > len[3])
					len[3] = dt.length();
			}
			String dt = "" + column.getKeySec();
			if (dt.length() > len[4])
				len[4] = dt.length();
		}

		int i = 1;
		for (PrimaryKeyMetadata columnI : columns) {
			String tableName = columnI.getName();
			if (StringUtils.isBlank(tableName)) {
				display[0][i] = "";
			} else {
				display[0][i] = columnI.getName().trim();
			}
			if (StringUtils.isBlank(columnI.getTable())) {
				display[1][i] = "";
			} else {
				display[1][i] = columnI.getTable();
			}
			if (StringUtils.isBlank(columnI.getSchema())) {
				display[2][i] = "";
			} else {
				display[2][i] = columnI.getSchema();
			}
			if (StringUtils.isBlank(columnI.getPkName())) {
				display[3][i] = "";
			} else {
				display[3][i] = columnI.getPkName();
			}
			display[4][i] = "" + columnI.getKeySec();
			i++;
		}

		printArrayOnConsole(display, columns.size(), MAX_COL, len);
	}

	/**
	 * Prints a list of tables
	 * @param tables(TreeSet<TableMetadata>) list of tables
	 * @param tableNameFilter(String) if not null, prints only the tables which name match the filter
	 */
	public void printTableList(TreeSet<TableMetadata> tables) {
		int MAX_COL = 5;
		String[][] display = new String[5][tables.size() + 1];
		display[0][0] = "Table";
		display[1][0] = "Schema";
		display[2][0] = "Type";
		display[3][0] = "Catalog";
		display[4][0] = "Remark";

		int[] len = new int[MAX_COL];
		len[0] = display[0][0].length();
		len[1] = display[1][0].length();
		len[2] = display[2][0].length();
		len[3] = display[3][0].length();
		len[4] = display[4][0].length();

		for (TableMetadata table : tables) {
			if (table.getName() != null && table.getName().length() > len[0]) {
				len[0] = table.getName().trim().length();
			}
			if (table.getSchema() != null && table.getSchema().length() > len[1]) {
				len[1] = table.getSchema().trim().length();
			}
			if (table.getType() != null && table.getType().length() > len[2]) {
				len[2] = table.getType().trim().length();
			}
			if (table.getCatalog() != null && table.getCatalog().length() > len[3]) {
				len[3] = table.getCatalog().trim().length();
			}
			if (table.getRemark() != null && (table.getRemark().trim()).length() > len[4]) {
				len[4] = (table.getRemark().trim()).length();
			}
		}

		int i = 1;
		for (TableMetadata table : tables) {
			String tableName = table.getName();
			//if (StringUtils.isBlank(tableNameFilter) || tableName.contains(tableNameFilter)) {
			if (StringUtils.isBlank(tableName)) {
				display[0][i] = "";
			} else {
				display[0][i] = table.getName().trim();
			}
			if (StringUtils.isBlank(table.getSchema())) {
				display[1][i] = "";
			} else {
				display[1][i] = table.getSchema();
			}
			if (StringUtils.isBlank(table.getType())) {
				display[2][i] = "";
			} else {
				display[2][i] = table.getType();
			}
			if (StringUtils.isBlank(table.getCatalog())) {
				display[3][i] = "";
			} else {
				display[3][i] = table.getCatalog();
			}
			if (StringUtils.isBlank(table.getRemark())) {
				display[4][i] = "";
			} else {
				display[4][i] = table.getRemark();
			}
			i++;
		}

		printArrayOnConsole(display, tables.size(), MAX_COL, len);
	}

	/**
	 * Prints a list of schemas
	 * @param schemas(TreeSet<TableMetadata>) list of schemas
	 * @param nameFilter(String) if not null, prints only the schemas which name match the filter
	 */
	public void printSchemas(TreeSet<SchemaMetadata> schemas) {
		int MAX_COL = 3;
		String[][] display = new String[3][schemas.size() + 1];
		display[0][0] = "Schema";
		display[1][0] = "Catalog";
		display[2][0] = "Current";

		int[] len = new int[MAX_COL];
		len[0] = display[0][0].length();
		len[1] = display[1][0].length();
		len[2] = display[2][0].length();

		for (SchemaMetadata schema : schemas) {
			if (schema.getName() != null && schema.getName().length() > len[0]) {
				len[0] = schema.getName().trim().length();
			}
			if (schema.getCatalog() != null && schema.getCatalog().length() > len[1]) {
				len[1] = schema.getCatalog().trim().length();
			}
		}

		int i = 1;
		for (SchemaMetadata schemaIt : schemas) {
			String schemaName = schemaIt.getName();
			if (StringUtils.isBlank(schemaName)) {
				display[0][i] = "";
			} else {
				display[0][i] = schemaIt.getName().trim();
			}
			if (StringUtils.isBlank(schemaIt.getCatalog())) {
				display[1][i] = "";
			} else {
				display[1][i] = schemaIt.getCatalog();
			}
			if (schemaIt.isCurrent()) {
				display[2][i] = "X";
			} else {
				display[2][i] = "";
			}
			i++;
		}

		printArrayOnConsole(display, schemas.size(), MAX_COL, len);
	}

	/**
	 * Prints a list of catalogs
	 * @param schemas(TreeSet<String>) list of catalogs
	 * @param nameFilter(String) if not null, prints only the catalogs which name match the filter
	 */
	public void printCatalogs(TreeSet<String> catalogs) {
		int MAX_COL = 1;
		String[][] display = new String[2][catalogs.size() + 1];
		display[0][0] = "Catalog";

		int[] len = new int[MAX_COL];
		len[0] = display[0][0].length();

		for (String catalog : catalogs) {
			if (catalog != null && catalog.length() > len[0]) {
				len[0] = catalog.trim().length();
			}
		}

		int i = 1;
		for (String cat : catalogs) {
			if (StringUtils.isBlank(cat)) {
				display[0][i] = "";
			} else {
				display[0][i] = cat.trim();
			}
			i++;
		}

		printArrayOnConsole(display, catalogs.size(), MAX_COL, len);
	}

	/**
	 * Prints a {@code LIB LIST}/{@code JS LIST}/{@code *FIND} grid in its compact catalog form: File,
	 * [Alias], Group (the entry's {@code @instance} tag - the file-format key predates, and is kept
	 * distinct from, the current "Database Group" model terminology), Environment, Status, Modified.
	 * The Alias column is shown only when at least one row has an alias: the Scripts Library has no
	 * aliases (SPRINT 1909S), while the experimental {@code JS} catalog still does. Description, Tags and
	 * Params are deliberately not columns (unbounded width / not needed to scan a catalog): they remain in
	 * {@link CatalogEntryRow} and are still available via {@code LIB SHOW} and the BroadSQL Editor.
	 *
	 * @param rows the entries to display, in the order they should be printed
	 */
	public void printCatalogList(List<CatalogEntryRow> rows) {
		boolean showAlias = false;
		for (CatalogEntryRow row : rows) {
			if (row.getAlias() != null && !row.getAlias().isBlank()) {
				showAlias = true;
				break;
			}
		}
		final int MAX_COL = showAlias ? 6 : 5;
		String[][] display = new String[MAX_COL][rows.size() + 1];
		String[] headers = showAlias
				? new String[] { "File", "Alias", "Group", "Environment", "Status", "Modified" }
				: new String[] { "File", "Group", "Environment", "Status", "Modified" };
		for (int col = 0; col < MAX_COL; col++) {
			display[col][0] = headers[col];
		}

		int[] len = new int[MAX_COL];
		for (int col = 0; col < MAX_COL; col++) {
			len[col] = display[col][0].length();
		}

		int i = 1;
		for (CatalogEntryRow row : rows) {
			String[] cells = showAlias
					? new String[] { orEmpty(row.getFile()), orEmpty(row.getAlias()), orEmpty(row.getInstance()), orEmpty(row.getEnvironment()),
							orEmpty(row.getStatus()), orEmpty(row.getModified()) }
					: new String[] { orEmpty(row.getFile()), orEmpty(row.getInstance()), orEmpty(row.getEnvironment()), orEmpty(row.getStatus()),
							orEmpty(row.getModified()) };
			for (int col = 0; col < MAX_COL; col++) {
				display[col][i] = cells[col];
				if (display[col][i].length() > len[col]) {
					len[col] = display[col][i].length();
				}
			}
			i++;
		}

		printArrayOnConsole(display, rows.size(), MAX_COL, len);
	}

	private static String orEmpty(String value) {
		return value == null ? "" : value;
	}

}
