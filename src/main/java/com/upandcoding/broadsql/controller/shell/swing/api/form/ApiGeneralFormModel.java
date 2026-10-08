package com.upandcoding.broadsql.controller.shell.swing.api.form;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.dao.api.model.ApiDefinition;

/**
 * Plain, non-Swing form model for {@code CONFIG API}'s "General" tab (docs/SPRINT XT02-sub sprint 5 -
 * API Configuration GUI + Bruno YAML Round-trip.md, sections 6/8) - the testable half of
 * {@code JApiGeneralPanel}, per this codebase's established "thin Swing shell around a testable form
 * model" precedent ({@code JEnvironmentsPanel}'s own javadoc). {@code id} is editable only while creating
 * a brand-new API (section 6 gives no base-URL field here - base URL belongs to the environment).
 */
public class ApiGeneralFormModel {

	private String id;
	private String name;
	private String description;

	public static ApiGeneralFormModel newApi() {
		return new ApiGeneralFormModel();
	}

	public static ApiGeneralFormModel fromApi(ApiDefinition api) {
		ApiGeneralFormModel model = new ApiGeneralFormModel();
		model.id = api.getId();
		model.name = api.getName();
		model.description = api.getDescr();
		return model;
	}

	/** Applies this model's editable fields onto {@code api} - {@code id} is set only when {@code api} is a brand-new object (its own {@code id} still {@code null}), matching section 6's "the ID field is only editable while creating a brand-new API". */
	public void applyTo(ApiDefinition api) {
		if (api.getId() == null) {
			api.setId(id);
		}
		api.setName(name);
		api.setDescr(description);
	}

	/** @param isNewApi whether this model represents an API not yet saved - {@code id} is required only then, since it is immutable afterward. */
	public List<String> validate(boolean isNewApi) {
		List<String> errors = new ArrayList<>();
		if (isNewApi && StringUtils.isBlank(id)) {
			errors.add("API ID is required.");
		}
		if (StringUtils.isBlank(name)) {
			errors.add("API name is required.");
		}
		return errors;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}
}
