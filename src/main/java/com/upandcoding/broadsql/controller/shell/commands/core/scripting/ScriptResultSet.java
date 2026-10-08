package com.upandcoding.broadsql.controller.shell.commands.core.scripting;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Eagerly materialized rows of a {@code SELECT} run from a {@code JS} script (see
 * {@code docs/LIGHT_SCRIPTING.md}, rule 4): the whole {@link ResultSet} is read up front into a
 * {@link List} of {@link LinkedHashMap} rows, column-label keyed in select order, so a script can loop
 * over it with a plain {@code for} loop ({@code rows.get(i)}, {@code rows.size()}) without holding a
 * live JDBC cursor open. No cursor/streaming support - simplest possible model, not suitable for a
 * huge result set.
 *
 * <p>Independent of any particular connection or command - used by {@link ScriptConnection#execute}.
 */
public class ScriptResultSet {

	private final List<LinkedHashMap<String, Object>> rows = new ArrayList<>();

	/** Reads {@code resultSet} fully, from its current position to exhaustion. Does not close it. */
	public static ScriptResultSet readAll(ResultSet resultSet) throws BroadSQLException {
		ScriptResultSet result = new ScriptResultSet();
		try {
			ResultSetMetaData metaData = resultSet.getMetaData();
			int columnCount = metaData.getColumnCount();
			String[] labels = new String[columnCount];
			for (int i = 0; i < columnCount; i++) {
				labels[i] = metaData.getColumnLabel(i + 1);
			}
			while (resultSet.next()) {
				LinkedHashMap<String, Object> row = new LinkedHashMap<>();
				for (int i = 0; i < columnCount; i++) {
					row.put(labels[i], resultSet.getObject(i + 1));
				}
				result.rows.add(row);
			}
		} catch (SQLException e) {
			throw new BroadSQLException(e);
		}
		return result;
	}

	public int size() {
		return rows.size();
	}

	public LinkedHashMap<String, Object> get(int index) {
		return rows.get(index);
	}

	/** The underlying row list, for callers that want a plain Java collection instead of index access. */
	public List<LinkedHashMap<String, Object>> getRows() {
		return rows;
	}
}
