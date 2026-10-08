package com.upandcoding.broadsql.controller.shell.sql.error;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Vendor-specific, optional enrichment: given the exact SQL submitted to JDBC and the
 * {@link SQLException} it raised, try to establish a reliable error position. JDBC itself has no
 * portable position API (see docs/SPRINT_0912A_EXPAND_FORMAT_SQL_ERRORS.md, section 6.5) - a
 * resolver that is not confident must return {@link Optional#empty()} rather than guess; the
 * renderer then shows the error without a caret.
 */
public interface SqlErrorLocationResolver {

	Optional<SqlErrorLocation> resolve(String submittedSql, SQLException exception);
}
