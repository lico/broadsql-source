package com.upandcoding.broadsql.dao.api.model;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * One row of the CDF's {@code API} table - the top-level "remote HTTP service" object, the API-world
 * analog of {@code CONNECTIONS} for databases. See docs/SPRINT XT02 - Universal API Client.md, section
 * 13.1. {@code id} is a user-chosen, immutable-after-creation identifier, same convention as
 * {@code CONNECTIONS.ID}/{@code INSTANCE.ID}.
 *
 * <p>{@code probeMethod}/{@code probePath} configure the authentication-test request (sub-sprint 3/4);
 * {@code defaultVersionId} points at the {@link ApiVersion} row endpoints attach to when no explicit
 * version is being worked with (Release 1 has exactly one version per API - see
 * {@link ApiVersion#isDefault()}).
 */
public class ApiDefinition {

	private String id;
	private String name;
	private String descr;
	private String comment;
	private Integer defaultVersionId;
	private String probeMethod;
	private String probePath;
	private String statusId = DatabaseDefinition.STATUS_ACTIVE;

	public ApiDefinition() {
	}

	public ApiDefinition(String id) {
		this.id = id;
	}

	public boolean isNotNull() {
		return StringUtils.isNotBlank(id);
	}

	public boolean isActive() {
		return DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(statusId);
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescr() {
		return descr;
	}

	public void setDescr(String descr) {
		this.descr = descr;
	}

	public String getComment() {
		return comment;
	}

	public void setComment(String comment) {
		this.comment = comment;
	}

	public Integer getDefaultVersionId() {
		return defaultVersionId;
	}

	public void setDefaultVersionId(Integer defaultVersionId) {
		this.defaultVersionId = defaultVersionId;
	}

	public String getProbeMethod() {
		return probeMethod;
	}

	public void setProbeMethod(String probeMethod) {
		this.probeMethod = probeMethod;
	}

	public String getProbePath() {
		return probePath;
	}

	public void setProbePath(String probePath) {
		this.probePath = probePath;
	}

	public String getStatusId() {
		return statusId;
	}

	public void setStatusId(String statusId) {
		this.statusId = statusId;
	}
}
