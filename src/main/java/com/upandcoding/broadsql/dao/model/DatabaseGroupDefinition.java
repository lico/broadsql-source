package com.upandcoding.broadsql.dao.model;

import org.apache.commons.lang3.StringUtils;

/**
 * One row of the CDF's {@code INSTANCE} reference table - a database group/product (e.g. {@code TAT},
 * {@code CP}), as opposed to a deployment stage. An ID (immutable after creation), a free-text
 * description, a free-text comment, and a soft-delete status.
 *
 * <p>Named {@code DatabaseGroupDefinition} per {@code docs/CONNECTION_MODEL.md}'s "Database Group"
 * terminology (Phase 1 of that rework); the physical table stays named {@code INSTANCE} to avoid a
 * second live-schema rename so soon after the {@code LANDSCAPE}/{@code ENVIRONMENT} one - see
 * {@code docs/TECHNICAL_CHANGE.md}. This class replaces the former {@code InstanceDefinition}, adding
 * the {@code comment} field the Database Group model requires.
 */
public class DatabaseGroupDefinition {

	private String id;
	private String descr;
	private String comment;
	private String statusId = DatabaseDefinition.STATUS_ACTIVE;

	public DatabaseGroupDefinition() {
	}

	public DatabaseGroupDefinition(String id, String descr, String comment, String statusId) {
		this.id = id;
		this.descr = descr;
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
