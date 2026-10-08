package com.upandcoding.broadsql.dao.api.model;

/**
 * One row of {@code API_AUTH} - which authentication mechanism applies to one owner ({@link ApiDefinition},
 * {@link ApiEnvironment} or {@link ApiEndpoint}; see {@link ApiOwnerType}). See docs/SPRINT XT02 -
 * Universal API Client.md, section 13.1. Implemented starting sub-sprint 3 - this class exists in
 * sub-sprint 1 only as the persistence shape.
 *
 * <p>Carries no per-type configuration itself: every authentication type's settings (header name,
 * username, token URL, ...) are {@code API_ATTRIBUTE} rows of kind {@code PROPERTY} owned by this row's
 * ID, so a future authentication type never needs a schema change - only a new {@link ApiAuthType} value
 * and a new set of property names.
 */
public class ApiAuthConfig {

	private Integer id;
	private ApiOwnerType ownerType;
	private String ownerId;
	private ApiAuthType authType = ApiAuthType.NONE;

	public ApiAuthConfig() {
	}

	public ApiAuthConfig(ApiOwnerType ownerType, String ownerId, ApiAuthType authType) {
		this.ownerType = ownerType;
		this.ownerId = ownerId;
		this.authType = authType;
	}

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public ApiOwnerType getOwnerType() {
		return ownerType;
	}

	public void setOwnerType(ApiOwnerType ownerType) {
		this.ownerType = ownerType;
	}

	public String getOwnerId() {
		return ownerId;
	}

	public void setOwnerId(String ownerId) {
		this.ownerId = ownerId;
	}

	public ApiAuthType getAuthType() {
		return authType;
	}

	public void setAuthType(ApiAuthType authType) {
		this.authType = authType;
	}
}
