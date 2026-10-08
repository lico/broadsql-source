package com.upandcoding.broadsql.dao.api.execution;

/**
 * How an API execution result's body is displayed on screen - API Quality and UX Consolidation
 * sprint, sections 32-41. {@code LIST} is the default (a complete, structure-preserving vertical
 * view via {@link com.upandcoding.broadsql.dao.api.tabular.ApiJsonListRenderer} - chosen because API
 * responses often have too many/too wide fields for a table to stay readable, and BroadSQL must never
 * silently hide returned data). {@code TABLE} remains fully available as an explicit choice (the
 * table-when-possible rendering that was the only behavior before this sprint). {@code RAW} is
 * unchanged from before this sprint - pretty-printed JSON (or raw text for a non-JSON body),
 * regardless of shape.
 *
 * <p>Deliberately does not affect {@code PULL API RESULT} - that capture stays tabular-based via
 * {@link com.upandcoding.broadsql.dao.api.tabular.ApiJsonTabularizer}, independent of the on-screen
 * display mode (section 41).
 */
public enum ApiResultDisplayMode {
	LIST, TABLE, RAW
}
