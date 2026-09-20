package com.upandcoding.broadsql.controller.shell.scripts;

public class ScriptCommand {

	private String query = null;

	public ScriptCommand(String query) {
		this.query = query;
	}

	public ScriptCommand(StringBuffer query) {
		this(query.toString());
	}

	public String getQuery() {
		return query;
	}

	public void setQuery(String query) {
		this.query = query;
	}

}
