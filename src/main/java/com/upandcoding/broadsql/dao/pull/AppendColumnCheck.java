package com.upandcoding.broadsql.dao.pull;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * SPRINT 1005C (#202): the one compatibility rule of a plain {@code MODE APPEND} (no {@code KEY}), shared by
 * the H2 and spreadsheet writers. The final result's columns and the existing destination's columns must have
 * the same count, the same names and the same order. Nothing else is compared: no data type, no SQL. Names are
 * compared case-insensitively, the convention {@code MODE APPEND KEY(...)} already uses for an existing H2
 * table. Writers call {@link #check} before they modify the destination, so a mismatch never adds a row.
 */
public final class AppendColumnCheck {

	private AppendColumnCheck() {
	}

	/** The result's column labels, in order. */
	public static List<String> resultColumns(ResultSetMetaData metaData) throws SQLException {
		List<String> labels = new ArrayList<>(metaData.getColumnCount());
		for (int i = 1; i <= metaData.getColumnCount(); i++) {
			labels.add(metaData.getColumnLabel(i));
		}
		return labels;
	}

	/**
	 * @param queryColumns       the final result's column labels, in order
	 * @param destinationColumns the existing destination's column names (H2 columns, or a tab's header cells), in order
	 * @throws BroadSQLException naming the first difference: the column counts, or the first column whose name differs
	 */
	public static void check(List<String> queryColumns, List<String> destinationColumns) throws BroadSQLException {
		if (queryColumns.size() != destinationColumns.size()) {
			throw new BroadSQLException("APPEND refused: destination has " + destinationColumns.size() + " columns but query returns "
					+ queryColumns.size() + ". APPEND requires the same columns, with the same names, in the same order.");
		}
		for (int i = 0; i < queryColumns.size(); i++) {
			String query = queryColumns.get(i);
			String destination = destinationColumns.get(i);
			if (query == null || destination == null || !query.equalsIgnoreCase(destination)) {
				throw new BroadSQLException("APPEND refused: column " + (i + 1) + " differs.\nQuery: " + query + "\nDestination: " + destination
						+ "\nAPPEND requires the same columns, with the same names, in the same order.");
			}
		}
	}
}
