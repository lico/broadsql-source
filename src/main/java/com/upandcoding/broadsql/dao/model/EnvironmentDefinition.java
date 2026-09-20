package com.upandcoding.broadsql.dao.model;

import org.apache.commons.lang3.StringUtils;

/**
 * One row of the CDF's {@code ENVIRONMENT} reference table - a deployment stage (e.g. {@code DEV},
 * {@code QA}, {@code PROD}), as opposed to a database group/product. An ID (immutable after creation,
 * unique case-insensitively), a free-text description, a {@code Production} flag, a free-text comment,
 * and a soft-delete status.
 *
 * <p>Phase 1 of the {@code docs/CONNECTION_MODEL.md} rework (see {@code docs/TECHNICAL_CHANGE.md}):
 * this class gives {@code ENVIRONMENT} its own full CRUD lifecycle for the first time - before this,
 * {@code ENVIRONMENT} only had a simple, insert-only read path
 * ({@code DatabaseDefinitionsVault#getEnvironments()}).
 */
public class EnvironmentDefinition {

	private String id;
	private String descr;
	private boolean production;
	private String comment;
	private String statusId = DatabaseDefinition.STATUS_ACTIVE;

	public EnvironmentDefinition() {
	}

	public EnvironmentDefinition(String id, String descr, boolean production, String comment, String statusId) {
		this.id = id;
		this.descr = descr;
		this.production = production;
		this.comment = comment;
		this.statusId = statusId;
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

	public String getDescr() {
		return descr;
	}

	public void setDescr(String descr) {
		this.descr = descr;
	}

	public boolean isProduction() {
		return production;
	}

	public void setProduction(boolean production) {
		this.production = production;
	}

	public String getComment() {
		return comment;
	}

	public void setComment(String comment) {
		this.comment = comment;
	}

	public String getStatusId() {
		return statusId;
	}

	public void setStatusId(String statusId) {
		this.statusId = statusId;
	}
}
