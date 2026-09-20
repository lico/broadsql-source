package com.upandcoding.broadsql.dao;

/**
 * Oracle's two proprietary JDBC type codes for its timestamp-with-zone column types, as reported by
 * {@code ResultSetMetaData.getColumnType()} against an Oracle connection. Neither has a
 * {@code java.sql.Types} equivalent: Oracle predates the JDBC 4.2 {@code TIMESTAMP_WITH_TIMEZONE}
 * (2014) standard type and never migrated its driver onto it, and {@code TIMESTAMP WITH LOCAL TIME
 * ZONE} has no standard JDBC type at all. Sourced from {@code oracle.jdbc.OracleTypes}, whose exact
 * values are part of Oracle's public JDBC API and have been stable across driver versions; not taken
 * as a compile-time dependency here since BroadSQL does not bundle the Oracle driver (users supply
 * their own in {@code drivers/} - see {@code TECHNICAL_REQUIREMENTS.md}).
 *
 * <p>Both types are read the same way as {@code java.sql.Types.TIMESTAMP}/{@code
 * TIMESTAMP_WITH_TIMEZONE} elsewhere in this codebase: {@code ResultSet.getTimestamp(int)}. For
 * {@code TIMESTAMPLTZ}, the Oracle driver already returns that call adjusted to the JVM's default time
 * zone - "local" is a session-relative property of the source value, not a fixed per-row offset, so
 * there is no more information to preserve. For {@code TIMESTAMPTZ}, which does carry a real per-row
 * offset, {@code getTimestamp()} still collapses it to the JVM's default zone, the same as {@code
 * java.sql.Timestamp} always does - offset preservation would need a different Java type end to end
 * (e.g. {@code OffsetDateTime}) and a different destination column type, out of scope for treating
 * these two types as "just another timestamp" (see docs/TODO.md, "User feedback after release 5.0.8
 * deployment", item 3).
 */
public final class OracleJdbcTypes {

	/** Oracle's {@code TIMESTAMP WITH TIME ZONE} ({@code oracle.jdbc.OracleTypes.TIMESTAMPTZ}). */
	public static final int TIMESTAMPTZ = -101;

	/** Oracle's {@code TIMESTAMP WITH LOCAL TIME ZONE} ({@code oracle.jdbc.OracleTypes.TIMESTAMPLTZ}). */
	public static final int TIMESTAMPLTZ = -102;

	private OracleJdbcTypes() {
	}
}
