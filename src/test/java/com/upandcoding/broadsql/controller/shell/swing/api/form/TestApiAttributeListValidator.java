package com.upandcoding.broadsql.controller.shell.swing.api.form;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

class TestApiAttributeListValidator {

	@Test
	void reportsOneErrorPerBlankName() {
		List<ApiAttribute> rows = List.of(
				attr("", "x"),
				attr("valid", "y"),
				attr(null, "z"));

		List<String> errors = ApiAttributeListValidator.validate(rows, "Header");

		Assertions.assertEquals(List.of("Header name is required.", "Header name is required."), errors);
	}

	@Test
	void allowsRepeatedNamesNeverEnforcingUniqueness() {
		// Section 24: "Allow repeated query parameter names. Do not enforce uniqueness by parameter name."
		List<ApiAttribute> rows = List.of(attr("tag", "admin"), attr("tag", "verified"));

		Assertions.assertEquals(List.of(), ApiAttributeListValidator.validate(rows, "Parameter"));
	}

	@Test
	void emptyListIsValid() {
		Assertions.assertEquals(List.of(), ApiAttributeListValidator.validate(List.of(), "Variable"));
	}

	private static ApiAttribute attr(String name, String value) {
		return new ApiAttribute(ApiOwnerType.ENDPOINT, "1", ApiAttributeKind.QUERY_PARAMETER, name, value, false);
	}
}
