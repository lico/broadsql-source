package com.upandcoding.broadsql.dao;

import java.time.Instant;

import com.upandcoding.broadsql.dao.api.tabular.ApiResultTable;

/**
 * An immutable snapshot of the most recent {@code EXECUTE API ENDPOINT} result - the API-side sibling
 * of {@link LastQueryResult}, backing {@code PULL API RESULT TO ...} (docs/BroadSQL XT02 overnight batch
 * plan, sub-sprint 7 - "API result export and local snapshot") exactly the way {@link LastQueryResult}
 * backs {@code PULL / TO ...} for SQL. Captured by
 * {@code com.upandcoding.broadsql.controller.shell.commands.core.api.CommandApiExecuteEndpoint} right after
 * a successful execution - the same call site {@code QueryExtractorToScreen} uses to populate
 * {@link LastQueryResultHolder} today.
 *
 * <p>Deliberately the smallest structure that supports that one feature - not a formal snapshot/test-result
 * repository (see docs/TODO.md, "Result reuse inside an investigation session", for why
 * {@link LastQueryResult} itself stays deliberately narrow; the same reasoning applies here). Holds the
 * already-tabularized {@link ApiResultTable} ({@link com.upandcoding.broadsql.dao.api.tabular.ApiJsonTabularizer}'s
 * output, so {@code PULL} never re-parses JSON), the raw response body it was derived from, and
 * lightweight, purely informational execution metadata - nothing more.
 */
public final class LastApiExecutionResult {

	private final ApiResultTable table;
	private final String rawJson;
	private final String sourceApi;
	private final String endpoint;
	private final String environment;
	private final Instant executedAt;
	private final int httpStatus;

	public LastApiExecutionResult(ApiResultTable table, String rawJson, String sourceApi, String endpoint, String environment,
			Instant executedAt, int httpStatus) {
		this.table = table;
		this.rawJson = rawJson;
		this.sourceApi = sourceApi;
		this.endpoint = endpoint;
		this.environment = environment;
		this.executedAt = executedAt;
		this.httpStatus = httpStatus;
	}

	/** @return the tabularized view of {@link #getRawJson()} - see {@link ApiResultTable#isTabular()} for whether it actually has columns/rows to export. */
	public ApiResultTable getTable() {
		return table;
	}

	/** @return the exact, untouched raw response body this snapshot was captured from - may be {@code null}/empty for a bodiless response (e.g. HEAD). */
	public String getRawJson() {
		return rawJson;
	}

	/** @return the API id the endpoint was executed under (informational only) */
	public String getSourceApi() {
		return sourceApi;
	}

	/** @return the endpoint's method and name, e.g. {@code "GET List Users"} (informational only) */
	public String getEndpoint() {
		return endpoint;
	}

	/** @return the environment name the endpoint was executed against (informational only) */
	public String getEnvironment() {
		return environment;
	}

	/** @return when the execution completed (informational only) */
	public Instant getExecutedAt() {
		return executedAt;
	}

	/** @return the HTTP status code returned (informational only) */
	public int getHttpStatus() {
		return httpStatus;
	}
}
