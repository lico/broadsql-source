package com.upandcoding.broadsql.controller.shell.sql;

import java.util.List;

/**
 * Resolves the columns of a relation (table or view, optionally schema-qualified exactly as it
 * appeared in the {@code FROM}/{@code JOIN} clause) in database ordinal order, for
 * {@link SqlWildcardExpander}. The real implementation ({@code CommandExpand}) delegates to
 * {@code DatabaseConnection#getMetaData(String)} - the existing metadata service every other
 * command already uses - rather than a new metadata subsystem; this seam exists only so the
 * expander's parsing/safety logic can be unit-tested against fabricated relations without a real
 * database connection.
 */
@FunctionalInterface
public interface SqlRelationColumnResolver {

	/**
	 * @param relationReference the relation exactly as written in the query (e.g. {@code "CUSTOMER"}
	 *                          or {@code "SCHEMA.CUSTOMER"})
	 * @return column names in ordinal order; never null/empty on success
	 * @throws Exception if the relation cannot be resolved (not found, ambiguous, metadata error) -
	 *                    {@link SqlWildcardExpander} treats any exception as "cannot resolve this
	 *                    relation" and leaves the current SQL unchanged
	 */
	List<String> resolveColumns(String relationReference) throws Exception;
}
