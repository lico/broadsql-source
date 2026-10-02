package com.upandcoding.broadsql.controller.shell.swing.api.form;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.api.model.ApiDefinition;

class TestApiGeneralFormModel {

	@Test
	void requiresAnIdOnlyForABrandNewApi() {
		ApiGeneralFormModel model = ApiGeneralFormModel.newApi();
		model.setName("My API");

		Assertions.assertEquals(List.of("API ID is required."), model.validate(true));
		Assertions.assertEquals(List.of(), model.validate(false), "an existing API's ID is immutable and never validated again");
	}

	@Test
	void requiresAName() {
		ApiGeneralFormModel model = ApiGeneralFormModel.newApi();
		model.setId("GITHUB");

		Assertions.assertEquals(List.of("API name is required."), model.validate(true));
	}

	@Test
	void appliesFieldsOntoANewApiIncludingId() {
		ApiGeneralFormModel model = ApiGeneralFormModel.newApi();
		model.setId("GITHUB");
		model.setName("GitHub");
		model.setDescription("GitHub's REST API");
		ApiDefinition api = new ApiDefinition();

		model.applyTo(api);

		Assertions.assertEquals("GITHUB", api.getId());
		Assertions.assertEquals("GitHub", api.getName());
		Assertions.assertEquals("GitHub's REST API", api.getDescr());
	}

	@Test
	void appliesFieldsOntoAnExistingApiWithoutChangingItsId() {
		ApiDefinition api = new ApiDefinition("GITHUB");
		ApiGeneralFormModel model = ApiGeneralFormModel.fromApi(api);
		model.setName("GitHub Renamed");

		model.applyTo(api);

		Assertions.assertEquals("GITHUB", api.getId(), "an existing API's ID must never be overwritten by applyTo");
		Assertions.assertEquals("GitHub Renamed", api.getName());
	}

	@Test
	void roundTripsFromAnExistingApi() {
		ApiDefinition api = new ApiDefinition("GITHUB");
		api.setName("GitHub");
		api.setDescr("desc");

		ApiGeneralFormModel model = ApiGeneralFormModel.fromApi(api);

		Assertions.assertEquals("GITHUB", model.getId());
		Assertions.assertEquals("GitHub", model.getName());
		Assertions.assertEquals("desc", model.getDescription());
	}
}
