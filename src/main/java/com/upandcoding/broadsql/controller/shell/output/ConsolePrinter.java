package com.upandcoding.broadsql.controller.shell.output;

import java.util.List;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import com.upandcoding.broadsql.dao.model.metadata.ColumnMetadata;
import com.upandcoding.broadsql.dao.model.metadata.PrimaryKeyMetadata;
import com.upandcoding.broadsql.dao.model.metadata.SchemaMetadata;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

public class ConsolePrinter {

	private static final int MAX_TEXT_COLUMN_WIDTH = 40;

	@Autowired
	public ShellConsole shellConsole;

	/**
	 * 
	 * @param display
	 * @param size
	 * @param maxCol
	 * @param len
	 */
	private void printArrayOnConsole(String[][] display, int size, int maxCol, int[] len) {
		int cnt = 0;
		for (int row = 0; row < size + 1; row++) {
			String rowDisp = "";
			String rowHeader = "";
			for (int col = 0; col < maxCol; col++) {
				String cell = display[col][row];
				if (cell == null || cell.trim().equals("")) {
					cell = "";
				}
				cell = StringUtils.rightPad(cell, len[col], " ");
				String colSep = " ";
				//if (row == 0) {
				colSep = "|";
				rowHeader = rowHeader + StringUtils.rightPad("", len[col], "-") + colSep;
				//}
				rowDisp = rowDisp + cell + colSep;
			}//for col
			if (rowDisp != null && !rowDisp.trim().equals("")) {
				if (row == 0) {
					shellConsole.writeln(rowHeader);
				}
				shellConsole.writeln(rowDisp);
				if (row == 0) {
					shellConsole.writeln(rowHeader);
				}
				cnt++;
			}
		}//for row
		shellConsole.writeln("");
		shellConsole.writeln("" + (cnt - 1) + " rows fetched.");
		shellConsole.writeln("");
	}

	/**
	 * Prints a list of columns
	 * @param tables(TreeSet<ColumnMetadata>) list of tables
	 * @param tableNameFilter(String) if not null, prints only the tables which name match the filter
	 */
	public void printColumns(TreeSet<ColumnMetadata> columns) {
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
			String dt = "" + column.getColumnSize();
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
			display[6][i] = "" + columnI.getColumnSize();
			i++;
		}

		printArrayOnConsole(display, columns.size(), MAX_COL, len);
	}

	/**
	 * Prints a list of primary keys
	 */
	public void printPrimaryKeys(TreeSet<PrimaryKeyMetadata> columns) {
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
	 * Prints a {@code LIB LIST}/{@code SCRIPT LIST}/{@code LIB FIND} grid - File, Alias, Description,
	 * Environment, Tags, Status, Modified, and (only when {@code includeParamsColumn}) Params. Long
	 * {@code Description}/{@code Tags} values are truncated so one long value can't stretch the whole
	 * table - the untruncated value is still available via {@code LIB SHOW}.
	 *
	 * @param rows                the entries to display, in the order they should be printed
	 * @param includeParamsColumn whether to add the LIB-only Params column (false for scripts)
	 */
	public void printCatalogList(List<CatalogEntryRow> rows, boolean includeParamsColumn) {
		int MAX_COL = includeParamsColumn ? 9 : 8;
		String[][] display = new String[MAX_COL][rows.size() + 1];
		display[0][0] = "File";
		display[1][0] = "Alias";
		display[2][0] = "Description";
		display[3][0] = "Instance";
		display[4][0] = "Environment";
		display[5][0] = "Tags";
		display[6][0] = "Status";
		display[7][0] = "Modified";
		if (includeParamsColumn) {
			display[8][0] = "Params";
		}

		int[] len = new int[MAX_COL];
		for (int col = 0; col < MAX_COL; col++) {
			len[col] = display[col][0].length();
		}

		int i = 1;
		for (CatalogEntryRow row : rows) {
			display[0][i] = orEmpty(row.getFile());
			display[1][i] = orEmpty(row.getAlias());
			display[2][i] = truncate(orEmpty(row.getDescription()));
			display[3][i] = orEmpty(row.getInstance());
			display[4][i] = orEmpty(row.getEnvironment());
			display[5][i] = truncate(orEmpty(row.getTags()));
			display[6][i] = orEmpty(row.getStatus());
			display[7][i] = orEmpty(row.getModified());
			if (includeParamsColumn) {
				display[8][i] = row.getParams() != null ? String.valueOf(row.getParams()) : "";
			}
			for (int col = 0; col < MAX_COL; col++) {
				if (display[col][i] != null && display[col][i].length() > len[col]) {
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

	private static String truncate(String value) {
		if (value.length() <= MAX_TEXT_COLUMN_WIDTH) {
			return value;
		}
		return value.substring(0, MAX_TEXT_COLUMN_WIDTH - 3) + "...";
	}
}
