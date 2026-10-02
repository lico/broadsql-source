package com.upandcoding.broadsql.dao.model;

import org.apache.commons.lang3.StringUtils;

/**
 * One row of the CDF's {@code TYPE} reference table - a database type name (e.g. {@code Oracle},
 * {@code PostgreSQL}), the JDBC driver class it defaults to, a connection mode (only meaningful for
 * Apache Derby's two access modes, {@code Embedded}/{@code Client} - every other type uses
 * {@code Default}), a free-text description, and a soft-delete status.
 *
 * <p>See {@code docs/SUPPORTED_DATABASES.md} for the full catalog of recognized type names this backs,
 * and {@code CommandSyncTypeCatalog} for the command that reconciles a CDF's {@code TYPE} table against
 * it.
 */
public class TypeDefinition {

	public static final String MODE_DEFAULT = "Default";

	private String id;
	private String descr;
	private String mode = MODE_DEFAULT;
	private String driver;
	private String statusId = DatabaseDefinition.STATUS_ACTIVE;

	public TypeDefinition() {
	}

	public TypeDefinition(String id, String descr, String mode, String driver, String statusId) {
		this.id = id;
		this.descr = descr;
		this.mode = mode;
		this.driver = driver;
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

	public String getMode() {
		return mode;
	}

	public void setMode(String mode) {
		this.mode = mode;
	}

	public String getDriver() {
		return driver;
	}

	public void setDriver(String driver) {
		this.driver = driver;
	}

	public String getStatusId() {
		return statusId;
	}

	public void setStatusId(String statusId) {
		this.statusId = statusId;
	}
}
