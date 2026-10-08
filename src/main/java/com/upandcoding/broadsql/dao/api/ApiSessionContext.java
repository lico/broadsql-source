package com.upandcoding.broadsql.dao.api;

/**
 * The active API/environment pair for the current interactive session, set by {@code CONNECT API
 * <api>:<environment>;} and cleared by {@code DISCONNECT API;} (SPRINT XT02-7B). Deliberately holds
 * only identifiers, never a cached {@link ApiEnvironment} object: every consumer (the shell prompt,
 * {@code RUN}, {@code SHOW ENDPOINTS}) re-resolves the live environment by name on each use, exactly
 * as {@code CommandApiExecuteEndpoint} already does on every invocation, so a later rename or edit of
 * the environment through {@code CONFIG API} can never leave this context stale.
 *
 * <p>Entirely separate from {@code LastApiExecutionResult} (the last execution's captured response),
 * the SQL {@code DatabaseConnection} (the independent, coexisting database connection), and the
 * persisted API/environment catalog itself.
 */
public final class ApiSessionContext {

	private final String apiId;
	private final String environmentName;

	public ApiSessionContext(String apiId, String environmentName) {
		this.apiId = apiId;
		this.environmentName = environmentName;
	}

	public String getApiId() {
		return apiId;
	}

	public String getEnvironmentName() {
		return environmentName;
	}
}
