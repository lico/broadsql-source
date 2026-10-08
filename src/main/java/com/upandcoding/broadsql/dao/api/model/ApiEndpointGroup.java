package com.upandcoding.broadsql.dao.api.model;

import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * One row of {@code API_ENDPOINT_GROUP} - a folder in the hierarchical endpoint tree Bruno's own
 * collections use (e.g. {@code Jira > Issues > Search}). See docs/SPRINT XT02 - Universal API Client.md,
 * section 4/13.1. {@code parentGroupId} is {@code null} for a top-level folder; arbitrary nesting depth
 * is supported since a group only ever references its immediate parent, never a fixed-depth path.
 *
 * <p>{@code sourceType}/{@code sourceKey} are the import-provenance identity (section 13.1) - both
 * {@code null} for a manually-created group.
 */
public class ApiEndpointGroup {

	private Integer id;
	private Integer apiVersionId;
	private Integer parentGroupId;
	private String name;
	private int sortOrder;
	private String sourceType;
	private String sourceKey;
	private String statusId = DatabaseDefinition.STATUS_ACTIVE;

	public ApiEndpointGroup() {
	}

	public ApiEndpointGroup(Integer apiVersionId, Integer parentGroupId, String name, int sortOrder) {
		this.apiVersionId = apiVersionId;
		this.parentGroupId = parentGroupId;
		this.name = name;
		this.sortOrder = sortOrder;
	}

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public Integer getApiVersionId() {
		return apiVersionId;
	}

	public void setApiVersionId(Integer apiVersionId) {
		this.apiVersionId = apiVersionId;
	}

	public Integer getParentGroupId() {
		return parentGroupId;
	}

	public void setParentGroupId(Integer parentGroupId) {
		this.parentGroupId = parentGroupId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
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
