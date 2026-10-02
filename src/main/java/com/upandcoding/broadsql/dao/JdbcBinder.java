package com.upandcoding.broadsql.dao;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import com.upandcoding.broadsql.controller.shell.scripts.ScriptValue;

/**
 * SPRINT 0110A: binds SQL scripting values to the {@code ?} markers BroadSQL generated for {@code ${name}}
 * references (spec section 9.2): {@code String} with {@code setString}, {@code Long} {@code setLong},
 * {@code BigDecimal} {@code setBigDecimal}, {@code Double}/{@code Float} {@code setDouble}/{@code setFloat},
 * {@code Boolean} {@code setBoolean}, {@code java.time} values {@code setObject} (JDBC 4.2), a typed NULL
 * {@code setNull} with its stored JDBC type, an untyped NULL {@code setNull(i, Types.NULL)}. Never text.
 */
public final class JdbcBinder {

	private JdbcBinder() {
	}

	/** Binds {@code values} to parameters {@code 1..n} of {@code statement}. */
	public static void bindAll(PreparedStatement statement, List<ScriptValue> values) throws SQLException {
		for (int i = 0; i < values.size(); i++) {
			bind(statement, i + 1, values.get(i));
		}
	}

	public static void bind(PreparedStatement statement, int index, ScriptValue value) throws SQLException {
		Object v = value.getValue();
		if (v == null) {
			statement.setNull(index, value.isUntypedNull() ? Types.NULL : value.getJdbcType());
		} else if (v instanceof String) {
			statement.setString(index, (String) v);
		} else if (v instanceof Long) {
			statement.setLong(index, (Long) v);
		} else if (v instanceof BigDecimal) {
			statement.setBigDecimal(index, (BigDecimal) v);
		} else if (v instanceof Double) {
			statement.setDouble(index, (Double) v);
		} else if (v instanceof Float) {
			statement.setFloat(index, (Float) v);
		} else if (v instanceof Boolean) {
			statement.setBoolean(index, (Boolean) v);
		} else {
			statement.setObject(index, v);
		}
	}
}
