package com.upandcoding.broadsql.dao.model;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

public class DatabaseDefinition {

	public static final String STATUS_ACTIVE = "ACTIVE";
	public static final String STATUS_INACTIVE = "INACTIVE";

	private String id;
	private String dbType; // values: Oracle, MySQL, Derby, Domino
	private String dbDriver; // Database Driver if any (technical)
	private String url; // Connector database (technical)
	private String userName; // user name
	private String userPassword; // user password
	private String dbName; // database
	private String encrypted;
	private String environment;
	private String databaseGroup;
	private String comment;
	private String status;

	public DatabaseDefinition() {
	}

	public DatabaseDefinition(String sId) {
		this.id = sId;
	}

	public DatabaseDefinition(String sId, String sType, String sDriver, String sUrl, String uName, String uPwd, String dName) {
		this.id = sId;
		this.dbType = sType;
		this.dbDriver = sDriver;
		this.url = sUrl;
		this.userName = uName;
		this.userPassword = uPwd;
		this.dbName = dName;
	}

	public boolean isNotNull() {
		boolean result;
		result = StringUtils.isNotBlank(id);
		return (result);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		final DatabaseDefinition other = (DatabaseDefinition) obj;
		if ((this.id == null) ? (other.id != null) : !this.id.equals(other.id)) {
			return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		int hash = 3;
		hash = 59 * hash + (this.id != null ? this.id.hashCode() : 0);
		return hash;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getDbType() {
		return dbType;
	}

	public void setDbType(String dbType) {
		this.dbType = dbType;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public void setUserName(String uName) {
		this.userName = uName;
	}

	public String getUserName() {
		return (this.userName);
	}

	public void setUserPassword(String uPwd) {
		this.userPassword = uPwd;
	}

	public String getUserPassword() {
		return (this.userPassword);
	}

	public void setDbName(String dName) {
		this.dbName = dName;
	}

	public String getDbName() {
		return (this.dbName);
	}

	public void setDbDriver(String dDriver) {
		this.dbDriver = dDriver;
	}

	public String getDbDriver() {
		return (this.dbDriver);
	}

	public String getConnectorDatabase() {
		return (this.url);
	}

	public String getEncrypted() {
		return encrypted;
	}

	public void setEncrypted(String encrypted) {
		this.encrypted = encrypted;
	}

	public String getDatabaseGroup() {
		return databaseGroup;
	}

	public void setDatabaseGroup(String databaseGroup) {
		this.databaseGroup = databaseGroup;
	}

	public String getEnvironment() {
		return environment;
	}

	public void setEnvironment(String environment) {
		this.environment = environment;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getComment() {
		return comment;
	}

	public void setComment(String comment) {
		this.comment = comment;
	}

	public String toString() {
		return ToStringBuilder.reflectionToString(this, ToStringStyle.SHORT_PREFIX_STYLE);
	}

}
