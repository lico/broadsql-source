package com.upandcoding.broadsql.dao.model.metadata;

import java.util.Objects;

public class TableMetadata implements Comparable {

	private String name;
	private String type;
	private String schema;
	private String remark;
	private String catalog;

	public TableMetadata() {
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getSchema() {
		return schema;
	}

	public void setSchema(String schema) {
		this.schema = schema;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}

	public String getCatalog() {
		return catalog;
	}

	public void setCatalog(String catalog) {
		this.catalog = catalog;
	}

	@Override
	public int hashCode() {
		int hash = 7;
		hash = 73 * hash + Objects.hashCode(this.name);
		hash = 73 * hash + Objects.hashCode(this.type);
		hash = 73 * hash + Objects.hashCode(this.schema);
		hash = 73 * hash + Objects.hashCode(this.catalog);
		return hash;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		final TableMetadata other = (TableMetadata) obj;
		if (!Objects.equals(this.name, other.name)) {
			return false;
		}
		if (!Objects.equals(this.type, other.type)) {
			return false;
		}
		if (!Objects.equals(this.schema, other.schema)) {
			return false;
		}
		if (!Objects.equals(this.catalog, other.catalog)) {
			return false;
		}
		return true;
	}

	@Override
	public int compareTo(Object o) {
		int result = 0;
		if (o instanceof TableMetadata) {
			TableMetadata compared = (TableMetadata) o;
			result = this.name.compareToIgnoreCase(compared.getName());
		}
		return (result);
	}// compareTo

}
