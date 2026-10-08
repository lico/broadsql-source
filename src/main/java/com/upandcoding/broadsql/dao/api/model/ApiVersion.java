package com.upandcoding.broadsql.dao.api.model;

import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * One row of {@code API_VERSION} - the API contract axis, orthogonal to {@link ApiEnvironment} (the
 * server/configuration axis). See docs/SPRINT XT02 - Universal API Client.md, section 13.1/12. Release 1
 * gives every {@link ApiDefinition} exactly one version, {@code isDefault=true}, named {@code "default"}
 * unless the importer supplies a real version name - so a later {@code v2}/{@code v3} split only ever
 * means adding more {@code API_VERSION} rows, never redesigning {@link ApiEndpointGroup}/
 * {@link ApiEndpoint} persistence (both already hang off {@code API_VERSION_ID}, not {@code API_ID}
 * directly).
 */
public class ApiVersion {

	public static final String DEFAULT_VERSION_NAME = "default";

	private Integer id;
	private String apiId;
	private String name = DEFAULT_VERSION_NAME;
	private boolean isDefault = true;
	private String statusId = DatabaseDefinition.STATUS_ACTIVE;

	public ApiVersion() {
	}

	public ApiVersion(String apiId, String name, boolean isDefault) {
		this.apiId = apiId;
		this.name = name;
		this.isDefault = isDefault;
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

	public boolean isDefault() {
		return isDefault;
	}

	public void setDefault(boolean isDefault) {
		this.isDefault = isDefault;
	}

	public String getStatusId() {
		return statusId;
	}

	public void setStatusId(String statusId) {
		this.statusId = statusId;
	}
}
