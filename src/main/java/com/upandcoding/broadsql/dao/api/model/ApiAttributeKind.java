package com.upandcoding.broadsql.dao.api.model;

/**
 * Discriminates what an {@code API_ATTRIBUTE} row represents - a named/valued entry scoped to some
 * {@link ApiOwnerType} owner. See docs/SPRINT XT02 - Universal API Client.md, section 13.1/13.4.
 *
 * <p>{@link #VARIABLE} rows participate in the variable-resolution precedence chain (section 13.4);
 * {@link #HEADER}/{@link #QUERY_PARAMETER}/{@link #PATH_PARAMETER} follow the separate header-precedence
 * rule (section 5) instead; {@link #PROPERTY} rows hang off an {@code API_AUTH} row (e.g. an API key's
 * header name, a Basic username) and are never resolved as variables.
 *
 * <p>{@link #QUERY_PARAMETER}/{@link #PATH_PARAMETER} are two distinct kinds, not one generic
 * {@code PARAMETER} - a sub-sprint 2 finding (sprint doc section 17.2): a single {@code PARAMETER} kind
 * cannot tell a query parameter from a path parameter apart once persisted, which matters because they
 * resolve completely differently when a request is built (a query parameter appends to the query string;
 * a path parameter substitutes into the URL path). Since {@code ATTR_KIND} was already a free-text
 * column, this needed no schema migration - only this enum gained a second, more specific value in place
 * of the ambiguous one.
 */
public enum ApiAttributeKind {
	VARIABLE, HEADER, QUERY_PARAMETER, PATH_PARAMETER, PROPERTY
}
