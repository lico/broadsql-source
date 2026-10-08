package com.upandcoding.broadsql.dao.api.model;

import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * One row of {@code API_ENVIRONMENT} - a selectable server/configuration for an {@link ApiDefinition}
 * (Bruno's "environment" concept, e.g. {@code Development}/{@code Production}), independent of
 * {@link ApiVersion}. See docs/SPRINT XT02 - Universal API Client.md, section 2/3/13.1.
 *
 * <p>{@code baseUrl} is a first-class column (not an {@code API_ATTRIBUTE} variable) because it is the
 * one value every endpoint always needs resolved before anything else - see the sprint doc's section 3
 * ("`baseUrl` must be environment-resolved"). Every other per-environment value (tenant, API version
 * header, tokens, ...) is an {@code API_ATTRIBUTE} row of kind {@code VARIABLE} owned by this row's ID.
 *
 * <p>{@code sourceType}/{@code sourceKey} are the import-provenance/idempotent-reimport identity (section
 * 13.1) - both {@code null} for a manually-created environment.
 */
public class ApiEnvironment {

	private Integer id;
	private String apiId;
	private String name;
	private String baseUrl;
	private int sortOrder;
	private String sourceType;
	private String sourceKey;
	private String statusId = DatabaseDefinition.STATUS_ACTIVE;

	public ApiEnvironment() {
	}

	public ApiEnvironment(String apiId, String name, String baseUrl, int sortOrder) {
		this.apiId = apiId;
		this.name = name;
		this.baseUrl = baseUrl;
		this.sortOrder = sortOrder;
	}

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public String getApiId() {
		return apiId;
	}

	public void setApiId(String apiId) {
		this.apiId = apiId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public void setSortOrder(int sortOrder) {
		this.sortOrder = sortOrder;
	}

	public String getSourceType() {
		return sourceType;
	}

	public void setSourceType(String sourceType) {
		this.sourceType = sourceType;
	}

	public String getSourceKey() {
		return sourceKey;
	}

	public void setSourceKey(String sourceKey) {
		this.sourceKey = sourceKey;
	}

	public String getStatusId() {
		return statusId;
	}

	public void setStatusId(String statusId) {
		this.statusId = statusId;
	}
}
