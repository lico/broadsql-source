package com.upandcoding.broadsql.controller.shell.output;

/**
 * One row of a {@code LIB LIST}/{@code SCRIPT LIST}/{@code LIB FIND} grid (see
 * {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}, section 4a). Plain data handed to
 * {@link ConsolePrinter#printCatalogList} by the command classes that build it from a
 * {@code com.upandcoding.broadsql.controller.shell.commands.core.catalog.FileCatalog} - kept in the output
 * package, not the catalog package, so this printer keeps depending on plain data rather than on the
 * commands layer, same direction as every other {@code ConsolePrinter} method.
 */
public class CatalogEntryRow {

	private final String file;
	private final String alias;
	private final String description;
	private final String instance;
	private final String environment;
	private final String tags;
	private final String status;
	private final String modified;
	private final Integer params; // null when not applicable (scripts, or an archives listing)

	public CatalogEntryRow(String file, String alias, String description, String instance, String environment, String tags, String status, String modified,
			Integer params) {
		this.file = file;
		this.alias = alias;
		this.description = description;
		this.instance = instance;
		this.environment = environment;
		this.tags = tags;
		this.status = status;
		this.modified = modified;
		this.params = params;
	}

	public String getFile() {
		return file;
	}

	public String getAlias() {
		return alias;
	}

	public String getDescription() {
		return description;
	}

	public String getInstance() {
		return instance;
	}

	public String getEnvironment() {
		return environment;
	}

	public String getTags() {
		return tags;
	}

	public String getStatus() {
		return status;
	}

	public String getModified() {
		return modified;
	}

	public Integer getParams() {
		return params;
	}
}
