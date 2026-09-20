package com.upandcoding.broadsql.dao.model.metadata;

import java.util.Objects;

public class SchemaMetadata implements Comparable {
	private String name;
	private String catalog;
	private boolean isCurrent = false;

	public SchemaMetadata() {

	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getCatalog() {
		return catalog;
	}

	public void setCatalog(String catalog) {
		this.catalog = catalog;
	}

	public boolean isCurrent() {
		return isCurrent;
	}

	public void setCurrent(boolean isCurrent) {
		this.isCurrent = isCurrent;
	}

	@Override
	public int hashCode() {
		int hash = 7;
		hash = 97 * hash + Objects.hashCode(this.name);
		hash = 97 * hash + Objects.hashCode(this.catalog);
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
		final SchemaMetadata other = (SchemaMetadata) obj;
		if (!Objects.equals(this.name, other.name)) {
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
		if (o instanceof SchemaMetadata) {
			SchemaMetadata compared = (SchemaMetadata) o;
			result = this.name.compareToIgnoreCase(compared.getName());
		}
		return (result);
	}// compareTo

}
