package com.upandcoding.broadsql.controller.shell.sql;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The shared, DBMS-agnostic SQL keyword vocabulary - single source of truth for anything that needs
 * to recognize "is this word a SQL keyword" (SPRINT 0917-02, Intelligent TAB Auto-Completion, section
 * 6, folded in with {@link SqlFormatterService}'s own pre-existing set rather than keeping two
 * independently maintained keyword lists - see that class, which now reads from here instead of its
 * own private copy).
 */
public final class SqlKeywords {

	private SqlKeywords() {
	}

	public static final Set<String> ALL = Set.copyOf(new LinkedHashSet<>(java.util.List.of(
			"SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS",
			"NATURAL", "ON", "GROUP", "BY", "ORDER", "HAVING", "UNION", "ALL", "DISTINCT", "AS", "INTO", "VALUES",
			"SET", "UPDATE", "DELETE", "INSERT", "MERGE", "USING", "WHEN", "MATCHED", "THEN", "ELSE", "END", "CASE",
			"IS", "IN", "EXISTS", "BETWEEN", "LIKE", "WITH", "ASC", "DESC", "LIMIT", "OFFSET", "FETCH", "FIRST",
			"NEXT", "ROWS", "ONLY", "NULL", "TRUE", "FALSE", "OVER", "PARTITION", "WINDOW", "FOR", "RETURNING",
			"INTERSECT", "EXCEPT", "MINUS",
			// SPRINT 0917-02, section 6: DDL/DML vocabulary the formatter never needed but completion does.
			"CREATE", "ALTER", "DROP", "TABLE", "VIEW", "INDEX", "COLUMN", "CONSTRAINT", "PRIMARY", "KEY", "FOREIGN",
			"REFERENCES", "DEFAULT", "UNIQUE", "CHECK", "CASCADE", "TRUNCATE", "GRANT", "REVOKE", "COMMIT", "ROLLBACK",
			"SAVEPOINT", "TRANSACTION")));
}
