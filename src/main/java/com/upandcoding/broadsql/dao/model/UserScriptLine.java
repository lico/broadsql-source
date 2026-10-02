package com.upandcoding.broadsql.dao.model;

import org.apache.commons.lang3.StringUtils;

/**
 * One row of the CDF's {@code USERS_SCRIPT} table (see docs/TODO.md item 13): a single SQL
 * statement run automatically on connect, as part of a given connection's ordered login script.
 *
 * <p>{@code USERS_SCRIPT.STATUS_ID} uses a different, older code pair ({@code "30"}/{@code "90"})
 * than every other CDF reference table ({@code CONNECTIONS}/{@code INSTANCE}/{@code ENVIRONMENT} all
 * use {@code "ACTIVE"}/{@code "INACTIVE"}, see {@link DatabaseDefinition#STATUS_ACTIVE}). Existing
 * installations already have real {@code USERS_SCRIPT} rows stored with {@code "30"}, so this class
 * keeps that convention rather than normalizing it - normalizing would silently orphan any script
 * line an existing CDF already has.
 */
public class UserScriptLine {

	public static final String STATUS_ACTIVE = "30";
	public static final String STATUS_INACTIVE = "90";

	private String serverId;
	private String sqlCommand;
	private int sqlOrder;
	private String statusId = STATUS_ACTIVE;
	private String sqlComment;

	public UserScriptLine() {
	}

	public UserScriptLine(String serverId, String sqlCommand, int sqlOrder, String statusId, String sqlComment) {
		this.serverId = serverId;
		this.sqlCommand = sqlCommand;
		this.sqlOrder = sqlOrder;
		this.statusId = statusId;
		this.sqlComment = sqlComment;
	}

	public boolean isActive() {
		return STATUS_ACTIVE.equals(statusId);
	}

	public boolean isNotBlank() {
		return StringUtils.isNotBlank(sqlCommand);
	}

	public String getServerId() {
		return serverId;
	}

	public void setServerId(String serverId) {
		this.serverId = serverId;
	}

	public String getSqlCommand() {
		return sqlCommand;
	}

	public void setSqlCommand(String sqlCommand) {
		this.sqlCommand = sqlCommand;
	}

	public int getSqlOrder() {
		return sqlOrder;
	}

	public void setSqlOrder(int sqlOrder) {
		this.sqlOrder = sqlOrder;
	}

	public String getStatusId() {
		return statusId;
	}

	public void setStatusId(String statusId) {
		this.statusId = statusId;
	}

	public String getSqlComment() {
		return sqlComment;
	}

	public void setSqlComment(String sqlComment) {
		this.sqlComment = sqlComment;
	}
}
