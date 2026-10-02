package com.upandcoding.broadsql.controller.shell.scripts;

import java.math.BigDecimal;
import java.sql.Types;

/**
 * SPRINT 0110A: the immutable value of one SQL scripting variable ({@code LET}, script arguments): one
 * scalar Java value or SQL NULL, with the JDBC type and the type name it was obtained with. A value is never
 * modified in place; a new assignment replaces it (spec section 7.2).
 *
 * <p>Java values are exactly those of the specification's type table: {@link String}, {@link Long},
 * {@link BigDecimal}, {@link Double}, {@link Float}, {@link Boolean}, {@code LocalDate}, {@code LocalTime},
 * {@code LocalDateTime}, {@code OffsetTime}, {@code OffsetDateTime}. A NULL is untyped (the {@code NULL}
 * literal, JDBC type {@link Types#NULL}) or typed (SQL NULL read from a query column, which keeps that
 * column's JDBC type for a later {@code setNull}).
 */
public final class ScriptValue {

	private final Object value;
	private final int jdbcType;
	private final String typeName;
	private final String origin;

	private ScriptValue(Object value, int jdbcType, String typeName, String origin) {
		this.value = value;
		this.jdbcType = jdbcType;
		this.typeName = typeName;
		this.origin = origin;
	}

	public static ScriptValue ofLong(long value) {
		return new ScriptValue(Long.valueOf(value), Types.BIGINT, "BIGINT", "literal");
	}

	public static ScriptValue ofDecimal(BigDecimal value) {
		return new ScriptValue(value, Types.DECIMAL, "DECIMAL", "literal");
	}

	public static ScriptValue ofString(String value) {
		return new ScriptValue(value, Types.VARCHAR, "VARCHAR", "literal");
	}

	public static ScriptValue ofBoolean(boolean value) {
		return new ScriptValue(Boolean.valueOf(value), Types.BOOLEAN, "BOOLEAN", "literal");
	}

	/** The {@code NULL} literal: defined, untyped. */
	public static ScriptValue untypedNull() {
		return new ScriptValue(null, Types.NULL, "NULL", "literal");
	}

	/**
	 * A value read from a query column ({@code LET x = SELECT ...}): {@code value} {@code null} is a NULL typed
	 * with {@code jdbcType}; {@code typeName} is the driver's type name.
	 */
	public static ScriptValue fromQuery(Object value, int jdbcType, String typeName, String connectionId) {
		return new ScriptValue(value, jdbcType, typeName, connectionId == null ? "query" : "query on " + connectionId);
	}

	/** The Java value, {@code null} for SQL NULL. */
	public Object getValue() {
		return value;
	}

	public boolean isNull() {
		return value == null;
	}

	/** A NULL obtained from the {@code NULL} literal (bound with {@code setNull(i, Types.NULL)}). */
	public boolean isUntypedNull() {
		return value == null && jdbcType == Types.NULL;
	}

	/** The JDBC type ({@link Types}) the value was obtained with; {@link Types#NULL} for an untyped NULL. */
	public int getJdbcType() {
		return jdbcType;
	}

	/** The type shown by {@code SHOW SCRIPT VARIABLES} and confirmation lines. */
	public String getTypeName() {
		return typeName;
	}

	/** Diagnostic origin: {@code literal}, {@code query}, {@code query on <connection>}. */
	public String getOrigin() {
		return origin;
	}

	@Override
	public String toString() {
		return ScriptValueText.render(this) + " (" + typeName + ")";
	}
}
