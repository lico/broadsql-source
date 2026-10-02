package com.upandcoding.broadsql.controller.shell.swing.api.form;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.dao.api.model.ApiAttribute;

/**
 * Shared validation for every editable variable/header/parameter list in the {@code CONFIG API} GUI
 * (SPRINT XT02 sub-sprint 5) - the one rule docs/SPRINT XT02-sub sprint 5 - API Configuration GUI + Bruno
 * YAML Round-trip.md section 30 states for this shape ("Variable name is required.") applies identically
 * to every {@link com.upandcoding.broadsql.dao.api.model.ApiAttributeKind}, so this is written once rather
 * than duplicated inside each of {@code JApiEnvironmentsPanel}/{@code JApiVariablesHeadersPanel}/
 * {@code JApiEndpointEditorPanel}'s own form models.
 *
 * <p>Deliberately does <b>not</b> enforce name uniqueness within the list - section 24 is explicit that
 * a repeated query parameter name is legal and must not be rejected ("Allow repeated query parameter
 * names. Do not enforce uniqueness by parameter name."); the same permissiveness is extended here to
 * every attribute kind for consistency, since nothing in the spec asks for uniqueness on variables or
 * headers either.
 */
public final class ApiAttributeListValidator {

	private ApiAttributeListValidator() {
	}

	/**
	 * @param attributes   the current rows (variables, headers, query parameters, or path parameters)
	 * @param itemLabel    what to call one row in an error message, e.g. {@code "Variable"}, {@code "Header"}
	 * @return one error message per row with a blank name, in list order; empty when every row is valid
	 */
	public static List<String> validate(List<ApiAttribute> attributes, String itemLabel) {
		List<String> errors = new java.util.ArrayList<>();
		for (ApiAttribute attribute : attributes) {
			if (StringUtils.isBlank(attribute.getName())) {
				errors.add(itemLabel + " name is required.");
			}
		}
		return errors;
	}
}
