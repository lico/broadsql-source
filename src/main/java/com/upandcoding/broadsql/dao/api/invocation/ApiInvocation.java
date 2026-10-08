package com.upandcoding.broadsql.dao.api.invocation;

import java.util.List;
import java.util.Map;

import com.upandcoding.broadsql.dao.api.execution.ApiResultDisplayMode;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;

/**
 * A fully-resolved, structured API execution request - SPRINT XT02A (URL-Native API Execution),
 * section 17. Deliberately independent of the CLI: everything needed to execute has already been
 * decided (which endpoint, every path/query parameter's final value) before this object exists, so it
 * is reusable later by {@code .bsql} scripts, data-driven execution, test scenarios, and batch API
 * operations (section 17/24), not just interactive {@code RUN}.
 */
public final class ApiInvocation {

	private final String apiId;
	private final ApiEndpoint endpoint;
	private final Map<String, String> pathParameterValues;
	private final List<Map.Entry<String, String>> queryParameters;
	private final ApiResultDisplayMode displayMode;

	public ApiInvocation(String apiId, ApiEndpoint endpoint, Map<String, String> pathParameterValues,
			List<Map.Entry<String, String>> queryParameters, ApiResultDisplayMode displayMode) {
		this.apiId = apiId;
		this.endpoint = endpoint;
		this.pathParameterValues = pathParameterValues;
		this.queryParameters = queryParameters;
		this.displayMode = displayMode;
	}

	public String getApiId() {
		return apiId;
	}

	public ApiEndpoint getEndpoint() {
		return endpoint;
	}

	public Map<String, String> getPathParameterValues() {
		return pathParameterValues;
	}

	public List<Map.Entry<String, String>> getQueryParameters() {
		return queryParameters;
	}

	public ApiResultDisplayMode getDisplayMode() {
		return displayMode;
	}
}
