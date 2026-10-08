package com.upandcoding.broadsql.dao.api.model;

import java.time.LocalDateTime;

/**
 * One row of {@code API_IMPORT_SOURCE} - records that {@code apiId} was (re)imported from an external
 * collection file, for provenance and for {@code SHOW API}-style diagnostics ("last imported from X on
 * Y"). See docs/SPRINT XT02 - Universal API Client.md, section 9/13.1. Implemented starting sub-sprint 2
 * (the Bruno importer) - this class exists in sub-sprint 1 only as the persistence shape.
 *
 * <p>Distinct from the {@code sourceType}/{@code sourceKey} columns on {@link ApiEnvironment}/
 * {@link ApiEndpointGroup}/{@link ApiEndpoint}: those identify one imported *object* for idempotent
 * re-import (UPDATE vs INSERT); this table identifies the imported *file* the whole {@link ApiDefinition}
 * came from, for re-import ("import this same file again") and for telling the user where an API's
 * definition originates.
 */
public class ApiImportSource {

	public static final String SOURCE_TYPE_BRUNO_YAML = "BRUNO_YAML";

	private Integer id;
	private String apiId;
	private String sourceType;
	private String sourceLocation;
	private LocalDateTime lastImportedAt;

	public ApiImportSource() {
	}

	public ApiImportSource(String apiId, String sourceType, String sourceLocation, LocalDateTime lastImportedAt) {
		this.apiId = apiId;
		this.sourceType = sourceType;
		this.sourceLocation = sourceLocation;
		this.lastImportedAt = lastImportedAt;
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

	public String getSourceType() {
		return sourceType;
	}

	public void setSourceType(String sourceType) {
		this.sourceType = sourceType;
	}

	public String getSourceLocation() {
		return sourceLocation;
	}

	public void setSourceLocation(String sourceLocation) {
		this.sourceLocation = sourceLocation;
	}

	public LocalDateTime getLastImportedAt() {
		return lastImportedAt;
	}

	public void setLastImportedAt(LocalDateTime lastImportedAt) {
		this.lastImportedAt = lastImportedAt;
	}
}
