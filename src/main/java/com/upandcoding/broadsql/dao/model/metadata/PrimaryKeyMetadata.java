package com.upandcoding.broadsql.dao.model.metadata;

public class PrimaryKeyMetadata implements Comparable {

	// See
	// https://docs.oracle.com/javase/7/docs/api/java/sql/DatabaseMetaData.html#getPrimaryKeys(java.lang.String,%20java.lang.String,%20java.lang.String)
	private String name; // COLUMN_NAME
	private String table; // TABLE_NAME
	private String catalog; // TABLE_CAT
	private String schema; // TABLE_SCHEM
	private int keySec; // KEY_SEQ
	private String pkName; // PK_NAME

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getTable() {
		return table;
	}

	public void setTable(String table) {
		this.table = table;
	}

	public String getCatalog() {
		return catalog;
	}

	public void setCatalog(String catalog) {
		this.catalog = catalog;
	}

	public String getSchema() {
		return schema;
	}

	public void setSchema(String schema) {
		this.schema = schema;
	}

	public int getKeySec() {
		return keySec;
	}

	public void setKeySec(int keySec) {
		this.keySec = keySec;
	}

	public String getPkName() {
		return pkName;
	}

	public void setPkName(String pkName) {
		this.pkName = pkName;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((catalog == null) ? 0 : catalog.hashCode());
		result = prime * result + keySec;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((pkName == null) ? 0 : pkName.hashCode());
		result = prime * result + ((schema == null) ? 0 : schema.hashCode());
		result = prime * result + ((table == null) ? 0 : table.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		PrimaryKeyMetadata other = (PrimaryKeyMetadata) obj;
		if (catalog == null) {
			if (other.catalog != null)
				return false;
		} else if (!catalog.equals(other.catalog))
			return false;
		if (keySec != other.keySec)
			return false;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		if (pkName == null) {
			if (other.pkName != null)
				return false;
		} else if (!pkName.equals(other.pkName))
			return false;
		if (schema == null) {
			if (other.schema != null)
				return false;
		} else if (!schema.equals(other.schema))
			return false;
		if (table == null) {
			if (other.table != null)
				return false;
		} else if (!table.equals(other.table))
			return false;
		return true;
	}

	@Override
	public int compareTo(Object o) {
		int result = 0;
		if (o instanceof PrimaryKeyMetadata) {
			PrimaryKeyMetadata compared = (PrimaryKeyMetadata) o;
			result = this.name.compareToIgnoreCase(compared.getName());
		}
		return (result);
	}

}
