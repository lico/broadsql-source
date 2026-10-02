package com.upandcoding.broadsql.controller.shell.swing.api.form;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * Plain, non-Swing form model for {@code CONFIG API}'s "Environments" tab (docs/SPRINT XT02-sub sprint 5
 * - API Configuration GUI + Bruno YAML Round-trip.md, sections 9-11) - the testable half of
 * {@code JApiEnvironmentsPanel}. Deliberately holds {@code baseUrl} as its own field, prominent and
 * separate from the {@link #getVariables()} list (section 10: "Do not make users hunt through a variable
 * table for the primary server URL"), even though internally it is saved through
 * {@link com.upandcoding.broadsql.dao.api.ApiDefinitionsVault#setEnvironmentBaseUrl} as an ordinary variable
 * too - this model's job is only to hold what the GUI shows, not to duplicate that persistence decision.
 */
public class ApiEnvironmentFormModel {

	private Integer id;
	private String name;
	private String baseUrl;
	private List<ApiAttribute> variables = new ArrayList<>();

	public static ApiEnvironmentFormModel newEnvironment() {
		return new ApiEnvironmentFormModel();
	}

	public static ApiEnvironmentFormModel fromEnvironment(ApiEnvironment env, List<ApiAttribute> variables) {
		ApiEnvironmentFormModel model = new ApiEnvironmentFormModel();
		model.id = env.getId();
		model.name = env.getName();
		model.baseUrl = env.getBaseUrl();
		// The baseUrl variable also appears in the ENVIRONMENT/VARIABLE attribute list (it is a real
		// variable, per the class javadoc) - excluded here so it is never shown twice in the "other
		// variables" table below the prominent Base URL field.
		for (ApiAttribute attr : variables) {
			if (!"baseUrl".equalsIgnoreCase(attr.getName())) {
				model.variables.add(attr);
			}
		}
		return model;
	}

	public List<String> validate() {
		List<String> errors = new ArrayList<>();
		if (StringUtils.isBlank(name)) {
			errors.add("Environment name is required.");
		}
		errors.addAll(ApiAttributeListValidator.validate(variables, "Variable"));
		return errors;
	}

	/** {@code owner*}-fields on the returned attributes are populated using {@code environmentId} - call only once the environment itself has a generated ID. */
	public List<ApiAttribute> variablesForOwner(Integer environmentId) {
		List<ApiAttribute> result = new ArrayList<>();
		for (ApiAttribute attr : variables) {
			ApiAttribute copy = new ApiAttribute(ApiOwnerType.ENVIRONMENT, String.valueOf(environmentId), ApiAttributeKind.VARIABLE, attr.getName(), attr.getValue(), attr.isSecret());
			copy.setEnabled(attr.isEnabled());
			copy.setSortOrder(attr.getSortOrder());
			result.add(copy);
		}
		return result;
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

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public List<ApiAttribute> getVariables() {
		return variables;
	}

	public void setVariables(List<ApiAttribute> variables) {
		this.variables = variables;
	}
}
