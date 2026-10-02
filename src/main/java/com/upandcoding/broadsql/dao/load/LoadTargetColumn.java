package com.upandcoding.broadsql.dao.load;

/**
 * One column of a {@code LOAD} target table, as reported by the database's own
 * {@link java.sql.ResultSetMetaData} - never derived from source/CSV text.
 */
public final class LoadTargetColumn {

	private final String name;
	private final int jdbcType;
	private final boolean nullable;
	private final boolean autoIncrement;
	private final boolean readOnly;

	public LoadTargetColumn(String name, int jdbcType, boolean nullable, boolean autoIncrement, boolean readOnly) {
		this.name = name;
		this.jdbcType = jdbcType;
		this.nullable = nullable;
		this.autoIncrement = autoIncrement;
		this.readOnly = readOnly;
	}

	public String getName() {
		return name;
	}

	public int getJdbcType() {
		return jdbcType;
	}

	public boolean isNullable() {
		return nullable;
	}

	public boolean isAutoIncrement() {
		return autoIncrement;
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public String getTypeName() {
		try {
			return java.sql.JDBCType.valueOf(jdbcType).getName();
		} catch (IllegalArgumentException e) {
			return "TYPE(" + jdbcType + ")";
		}
	}
}
