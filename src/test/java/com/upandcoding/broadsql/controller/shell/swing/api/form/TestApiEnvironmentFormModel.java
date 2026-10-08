package com.upandcoding.broadsql.controller.shell.swing.api.form;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

class TestApiEnvironmentFormModel {

	@Test
	void requiresAName() {
		ApiEnvironmentFormModel model = ApiEnvironmentFormModel.newEnvironment();
		model.setBaseUrl("https://api.example.com");

		Assertions.assertEquals(List.of("Environment name is required."), model.validate());
	}

	@Test
	void reportsOneErrorPerBlankVariableName() {
		ApiEnvironmentFormModel model = ApiEnvironmentFormModel.newEnvironment();
		model.setName("Production");
		model.setVariables(List.of(
				new ApiAttribute(ApiOwnerType.ENVIRONMENT, "1", ApiAttributeKind.VARIABLE, "", "x", false),
				new ApiAttribute(ApiOwnerType.ENVIRONMENT, "1", ApiAttributeKind.VARIABLE, "tenant", "acme", false)));

		Assertions.assertEquals(List.of("Variable name is required."), model.validate());
	}

	@Test
	void excludesTheBaseUrlVariableFromTheOtherVariablesListOnLoad() {
		ApiEnvironmentFormModel model = ApiEnvironmentFormModel.fromEnvironment(
				new ApiEnvironment("GITHUB", "Production", "https://api.example.com", 0),
				List.of(new ApiAttribute(ApiOwnerType.ENVIRONMENT, "1", ApiAttributeKind.VARIABLE, "baseUrl", "https://api.example.com", false),
						new ApiAttribute(ApiOwnerType.ENVIRONMENT, "1", ApiAttributeKind.VARIABLE, "tenant", "acme", false)));

		Assertions.assertEquals(1, model.getVariables().size(), "baseUrl must not be shown a second time in the 'other variables' table");
		Assertions.assertEquals("tenant", model.getVariables().get(0).getName());
		Assertions.assertEquals("https://api.example.com", model.getBaseUrl(), "the prominent Base URL field must still reflect the environment's own baseUrl");
	}

	@Test
	void variablesForOwnerStampsTheGivenEnvironmentId() {
		ApiEnvironmentFormModel model = ApiEnvironmentFormModel.newEnvironment();
		model.setName("Production");
		model.setVariables(List.of(new ApiAttribute(ApiOwnerType.ENVIRONMENT, null, ApiAttributeKind.VARIABLE, "tenant", "acme", false)));

		List<ApiAttribute> stamped = model.variablesForOwner(42);

		Assertions.assertEquals("42", stamped.get(0).getOwnerId());
		Assertions.assertEquals(ApiOwnerType.ENVIRONMENT, stamped.get(0).getOwnerType());
		Assertions.assertEquals("tenant", stamped.get(0).getName());
	}
}
