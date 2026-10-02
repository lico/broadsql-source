package com.upandcoding.broadsql.dao.model;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

public class DatabaseDriver {
	private String name;
	private List<String> className = new ArrayList<>();
	private String version;
	private boolean jdbcCompliant;
	private String vendor;
	private String jarFile;

	public void addClassName(String className) {
		this.className.add(className);
	}
	
	public void addClassName(List<String> classNameLst) {
		this.className.addAll(classNameLst);
	}

	public List<String> getClassName() {
		return className;
	}

	public void setClassName(List<String> className) {
		this.className = className;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public boolean isJdbcCompliant() {
		return jdbcCompliant;
	}

	public void setJdbcCompliant(boolean jdbcCompliant) {
		this.jdbcCompliant = jdbcCompliant;
	}

	public String getVendor() {
		return vendor;
	}

	public void setVendor(String vendor) {
		this.vendor = vendor;
	}

	public String getJarFile() {
		return jarFile;
	}

	public void setJarFile(String jarFile) {
		this.jarFile = jarFile;
	}

	public String toString() {
		return ToStringBuilder.reflectionToString(this, ToStringStyle.SHORT_PREFIX_STYLE);
	}

	@Override
    public int hashCode() {
	    final int prime = 31;
	    int result = 1;
	    result = prime * result + ((name == null) ? 0 : name.hashCode());
	    result = prime * result + ((vendor == null) ? 0 : vendor.hashCode());
	    result = prime * result + ((version == null) ? 0 : version.hashCode());
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
	    DatabaseDriver other = (DatabaseDriver) obj;
	    if (name == null) {
		    if (other.name != null)
			    return false;
	    } else if (!name.equals(other.name))
		    return false;
	    if (vendor == null) {
		    if (other.vendor != null)
			    return false;
	    } else if (!vendor.equals(other.vendor))
		    return false;
	    if (version == null) {
		    if (other.version != null)
			    return false;
	    } else if (!version.equals(other.version))
		    return false;
	    return true;
    }
	
	
}
