package com.upandcoding.broadsql.controller.shell.swing.api.form;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.dao.api.model.ApiAliasValidator;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;

/**
 * Plain, non-Swing form model for {@code CONFIG API}'s endpoint General editor - docs/SPRINT XT02-sub
 * sprint 5 - API Configuration GUI + Bruno YAML Round-trip.md, section 23, amended by
 * docs/Amendment - Endpoint Aliases and Future Scriptability.md, section 7 (the {@code Alias} field).
 * Parameters/Headers/Variables are plain {@code List<ApiAttribute>} edited through the shared
 * {@link ApiAttributeListValidator}; Auth through {@link ApiAuthFormModel}; Body is simple enough
 * ({@code bodyMode}/{@code bodyContent}) to stay inline here rather than its own model class.
 */
public class ApiEndpointFormModel {

	private Integer id;
	private String name;
	private String method = "GET";
	private String path;
	private Integer groupId;
	private String alias;
	private String bodyMode;
	private String bodyContent;

	public static ApiEndpointFormModel newEndpoint(Integer groupId) {
		ApiEndpointFormModel model = new ApiEndpointFormModel();
		model.groupId = groupId;
		return model;
	}

	public static ApiEndpointFormModel fromEndpoint(ApiEndpoint endpoint) {
		ApiEndpointFormModel model = new ApiEndpointFormModel();
		model.id = endpoint.getId();
		model.name = endpoint.getName();
		model.method = endpoint.getMethod();
		model.path = endpoint.getEndpointPath();
		model.groupId = endpoint.getGroupId();
		model.alias = endpoint.getAlias();
		model.bodyMode = endpoint.getBodyMode();
		model.bodyContent = endpoint.getBodyContent();
		return model;
	}

	/** Applies every field this model owns onto {@code endpoint} - {@code id}/version/sort-order are the caller's responsibility, unchanged by this method. */
	public void applyTo(ApiEndpoint endpoint) {
		endpoint.setName(name);
		endpoint.setMethod(method);
		endpoint.setEndpointPath(path);
		endpoint.setGroupId(groupId);
		endpoint.setAlias(StringUtils.trimToNull(alias));
		endpoint.setBodyMode(bodyMode);
		endpoint.setBodyContent(bodyContent);
	}

	/**
	 * Structural validation only (section 30) - the amendment's alias syntax check is duplicated here
	 * (not only relied upon at save time in {@link com.upandcoding.broadsql.dao.api.ApiDefinitionsVault#saveEndpoint})
	 * so the GUI can report it immediately, before Apply, exactly like every other field on this form;
	 * the vault-level check remains the authoritative enforcement point (including the alias-uniqueness
	 * rule this method deliberately does not duplicate, since that requires a vault round-trip this
	 * plain model has no access to).
	 */
	public List<String> validate() {
		List<String> errors = new ArrayList<>();
		if (StringUtils.isBlank(name)) {
			errors.add("Endpoint name is required.");
		}
		if (StringUtils.isBlank(method)) {
			errors.add("HTTP method is required.");
		}
		if (StringUtils.isBlank(path)) {
			errors.add("Endpoint URL/path is required.");
		}
		String trimmedAlias = StringUtils.trimToNull(alias);
		if (trimmedAlias != null && !ApiAliasValidator.isValid(trimmedAlias)) {
			errors.add("Alias '" + trimmedAlias + "' is not a valid BroadSQL identifier (must match [A-Za-z_][A-Za-z0-9_]*).");
		}
		return errors;
	}

	public Integer getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public Integer getGroupId() {
		return groupId;
	}

	public void setGroupId(Integer groupId) {
		this.groupId = groupId;
	}

	public String getAlias() {
		return alias;
	}

	public void setAlias(String alias) {
		this.alias = alias;
	}

	public String getBodyMode() {
		return bodyMode;
	}

	public void setBodyMode(String bodyMode) {
		this.bodyMode = bodyMode;
	}

	public String getBodyContent() {
		return bodyContent;
	}

	public void setBodyContent(String bodyContent) {
		this.bodyContent = bodyContent;
	}
}
