package com.upandcoding.broadsql.dao.api.model;

/**
 * One row of {@code API_ATTRIBUTE} - a named/valued entry scoped to some owner, discriminated by
 * {@link ApiOwnerType} (which table/row owns it) and {@link ApiAttributeKind} (what it represents:
 * variable, header, query parameter, or auth property). See docs/SPRINT XT02 - Universal API Client.md,
 * section 13.1 for why this single table replaces what would otherwise be five near-identical
 * name/value tables.
 *
 * <p>{@code secret} marks a value that must never be rendered in diagnostics, logs, command history or
 * test output (section 1.5/6 of the sprint doc) - meaningful mainly for {@code VARIABLE}/{@code PROPERTY}
 * rows; {@code enabled} lets an imported-but-disabled Bruno header/parameter/variable be preserved
 * without taking effect, matching Bruno's own enabled/disabled toggle.
 */
public class ApiAttribute {

	/** Pipe separator for {@link #allowedValues} - matches the INI {@code apiproxynonproxyhosts}-style convention already used elsewhere in BroadSQL for a flat list stored in one column. */
	public static final String ALLOWED_VALUES_SEPARATOR = "|";

	private Integer id;
	private ApiOwnerType ownerType;
	private String ownerId;
	private ApiAttributeKind kind;
	private String name;
	private String value;
	private boolean secret;
	private boolean enabled = true;
	private int sortOrder;

	/**
	 * SPRINT XT02A (URL-Native API Execution) parameter metadata - meaningful only for
	 * {@link ApiAttributeKind#PATH_PARAMETER}/{@link ApiAttributeKind#QUERY_PARAMETER} rows, where this
	 * same row now also doubles as the endpoint parameter's definition metadata: {@link #value} is its
	 * <i>persisted value</i> (docs/SPRINT_XT02A_URL_NATIVE_API_EXECUTION.md, section 2.6), and these four
	 * fields carry {@code required}/{@code type}/{@code default}/{@code allowed values} - deliberately on
	 * this existing table rather than a second parallel one, so the parameter catalog used by {@code RUN},
	 * {@code SYNTAX}, {@code CONFIG API} and completion can never drift apart. Left at their defaults
	 * (not required, {@code string} type, no default/allowed values) for every other kind.
	 */
	private boolean required = false;
	private String paramType = "string";
	private String defaultValue;
	private String allowedValues;
	private String description;

	public ApiAttribute() {
	}

	public ApiAttribute(ApiOwnerType ownerType, String ownerId, ApiAttributeKind kind, String name, String value, boolean secret) {
		this.ownerType = ownerType;
		this.ownerId = ownerId;
		this.kind = kind;
		this.name = name;
		this.value = value;
		this.secret = secret;
	}

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public ApiOwnerType getOwnerType() {
		return ownerType;
	}

	public void setOwnerType(ApiOwnerType ownerType) {
		this.ownerType = ownerType;
	}

	public String getOwnerId() {
		return ownerId;
	}

	public void setOwnerId(String ownerId) {
		this.ownerId = ownerId;
	}

	public ApiAttributeKind getKind() {
		return kind;
	}

	public void setKind(ApiAttributeKind kind) {
		this.kind = kind;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public boolean isSecret() {
		return secret;
	}

	public void setSecret(boolean secret) {
		this.secret = secret;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public void setSortOrder(int sortOrder) {
		this.sortOrder = sortOrder;
	}

	public boolean isRequired() {
		return required;
	}

	public void setRequired(boolean required) {
		this.required = required;
	}

	public String getParamType() {
		return paramType;
	}

	public void setParamType(String paramType) {
		this.paramType = paramType;
	}

	public String getDefaultValue() {
		return defaultValue;
	}

	public void setDefaultValue(String defaultValue) {
		this.defaultValue = defaultValue;
	}

	/** Raw pipe-separated allowed-values string (e.g. {@code "mail|orders|profile"}), or {@code null}/blank if unrestricted. Use {@link #getAllowedValuesList()} for the parsed form. */
	public String getAllowedValues() {
		return allowedValues;
	}

	public void setAllowedValues(String allowedValues) {
		this.allowedValues = allowedValues;
	}

	/** {@link #getAllowedValues()} split on {@link #ALLOWED_VALUES_SEPARATOR}, trimmed, empty entries dropped - an empty list means unrestricted. */
	public java.util.List<String> getAllowedValuesList() {
		if (allowedValues == null || allowedValues.isBlank()) {
			return java.util.List.of();
		}
		java.util.List<String> result = new java.util.ArrayList<>();
		for (String candidate : allowedValues.split("\\|")) {
			String trimmed = candidate.trim();
			if (!trimmed.isEmpty()) {
				result.add(trimmed);
			}
		}
		return result;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	/**
	 * Redacts {@link #value} whenever {@link #secret} is set - SPRINT XT02 sub-sprint 3's secret-handling
	 * requirement ("do not rely on developers remembering not to log authentication objects"; docs/
	 * SPRINT XT02 - Universal API Client.md, section 19). A secret {@code PROPERTY} attribute (a Bearer
	 * token, an OAuth client secret, ...) can therefore never leak through an incidental
	 * {@code log.debug(attribute)}, exception message, or test failure dump - this is the one, permanent
	 * fix, not something every call site has to remember.
	 */
	@Override
	public String toString() {
		return "ApiAttribute{ownerType=" + ownerType + ", ownerId=" + ownerId + ", kind=" + kind + ", name=" + name
				+ ", value=" + (secret ? "******" : value) + ", enabled=" + enabled + ", sortOrder=" + sortOrder + "}";
	}
}
