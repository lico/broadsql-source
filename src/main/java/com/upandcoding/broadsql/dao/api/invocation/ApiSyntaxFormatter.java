package com.upandcoding.broadsql.dao.api.invocation;

import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * Generates {@code SYNTAX <alias>;} output directly from the endpoint's own stored metadata - SPRINT
 * XT02A (URL-Native API Execution), section 3.4: "Do not maintain a manually duplicated syntax string
 * that can drift from execution metadata." Consumes exactly the {@code PATH_PARAMETER}/
 * {@code QUERY_PARAMETER} {@link ApiAttribute} rows {@code RUN} itself resolves against
 * ({@link ApiParameterBinder}), so the two can never disagree about what an endpoint needs.
 */
public final class ApiSyntaxFormatter {

	private ApiSyntaxFormatter() {
	}

	public static String format(ApiDefinitionsVault vault, ApiEndpoint endpoint) throws BroadSQLException {
		ApiPathTemplate template = ApiPathTemplate.parse(endpoint.getEndpointPath());
		List<ApiAttribute> pathParams = vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.PATH_PARAMETER);
		List<ApiAttribute> queryParams = vault.getAttributes(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()), ApiAttributeKind.QUERY_PARAMETER);

		StringBuilder sb = new StringBuilder();
		String label = endpoint.getAlias() != null && !endpoint.getAlias().isBlank() ? endpoint.getAlias() : endpoint.getName();
		sb.append(label).append('\n');
		sb.append(endpoint.getMethod()).append(' ').append(template.toCanonicalDisplay()).append("\n\n");

		List<String> pathParamNames = template.parameterNamesInOrder();
		boolean anyRequired = !pathParamNames.isEmpty() || queryParams.stream().anyMatch(ApiAttribute::isRequired);
		if (anyRequired) {
			sb.append("Required:\n");
			for (String name : pathParamNames) {
				ApiAttribute meta = findByName(pathParams, name);
				sb.append("  :").append(name).append("\tPATH\t").append(describe(meta)).append('\n');
			}
			for (ApiAttribute q : queryParams) {
				if (q.isRequired()) {
					sb.append("  ").append(q.getName()).append("\tQUERY\t").append(describe(q)).append('\n');
				}
			}
			sb.append('\n');
		}

		List<ApiAttribute> optionalQuery = queryParams.stream().filter(q -> !q.isRequired()).toList();
		if (!optionalQuery.isEmpty()) {
			sb.append("Optional query parameters:\n");
			for (ApiAttribute q : optionalQuery) {
				List<String> allowed = q.getAllowedValuesList();
				sb.append("  ").append(q.getName());
				if (!allowed.isEmpty()) {
					sb.append("\t").append(String.join(" | ", allowed));
				} else if (q.getDescription() != null && !q.getDescription().isBlank()) {
					sb.append("\t").append(q.getDescription());
				}
				sb.append('\n');
			}
			sb.append('\n');
		}

		sb.append("Examples:\n");
		sb.append("  RUN ").append(endpoint.getMethod().equalsIgnoreCase("GET") ? "" : endpoint.getMethod() + " ")
				.append(exampleLiteralUrl(template, pathParams)).append(";\n");

		if (!pathParamNames.isEmpty()) {
			sb.append("\nParameterized:\n");
			for (String name : pathParamNames) {
				sb.append("  VAR ").append(name.toUpperCase()).append("=<value>;\n");
			}
			sb.append("  RUN ").append(endpoint.getMethod().equalsIgnoreCase("GET") ? "" : endpoint.getMethod() + " ")
					.append(template.toCanonicalDisplay()).append(";\n");
		}
		return sb.toString().stripTrailing();
	}

	private static String exampleLiteralUrl(ApiPathTemplate template, List<ApiAttribute> pathParams) {
		if (template.segmentCount() == 0) {
			return "/";
		}
		StringBuilder url = new StringBuilder();
		for (int i = 0; i < template.segmentCount(); i++) {
			url.append('/');
			String name = template.parameterName(i);
			if (name == null) {
				url.append(template.segment(i));
			} else {
				ApiAttribute meta = findByName(pathParams, name);
				String example = meta != null && meta.getValue() != null && !meta.getValue().isBlank() ? meta.getValue()
						: meta != null && meta.getDefaultValue() != null && !meta.getDefaultValue().isBlank() ? meta.getDefaultValue()
						: "123";
				url.append(example);
			}
		}
		return url.toString();
	}

	private static String describe(ApiAttribute meta) {
		if (meta == null || meta.getDescription() == null || meta.getDescription().isBlank()) {
			return meta != null ? meta.getParamType() : "";
		}
		return meta.getDescription();
	}

	private static ApiAttribute findByName(List<ApiAttribute> attributes, String name) {
		for (ApiAttribute attr : attributes) {
			if (attr.getName() != null && attr.getName().equalsIgnoreCase(name)) {
				return attr;
			}
		}
		return null;
	}
}
