package com.upandcoding.broadsql.controller.shell.swing.api.form;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;

class TestApiEndpointFormModel {

	@Test
	void requiresNameMethodAndPath() {
		ApiEndpointFormModel model = ApiEndpointFormModel.newEndpoint(null);
		model.setMethod("");
		model.setPath("");

		List<String> errors = model.validate();

		Assertions.assertTrue(errors.contains("Endpoint name is required."));
		Assertions.assertTrue(errors.contains("HTTP method is required."));
		Assertions.assertTrue(errors.contains("Endpoint URL/path is required."));
	}

	@Test
	void aBlankAliasIsAlwaysValid() {
		ApiEndpointFormModel model = ApiEndpointFormModel.newEndpoint(null);
		model.setName("Get order");
		model.setMethod("GET");
		model.setPath("/orders/${id}");
		model.setAlias("   ");

		Assertions.assertEquals(List.of(), model.validate());
	}

	@Test
	void anInvalidAliasIsRejectedWithTheAmendmentsWording() {
		ApiEndpointFormModel model = ApiEndpointFormModel.newEndpoint(null);
		model.setName("Get order");
		model.setMethod("GET");
		model.setPath("/orders");
		model.setAlias("1-not-valid");

		Assertions.assertEquals(List.of("Alias '1-not-valid' is not a valid BroadSQL identifier (must match [A-Za-z_][A-Za-z0-9_]*)."), model.validate());
	}

	@Test
	void aValidAliasPasses() {
		ApiEndpointFormModel model = ApiEndpointFormModel.newEndpoint(null);
		model.setName("Create order");
		model.setMethod("POST");
		model.setPath("/orders");
		model.setAlias("DO_ORDER");

		Assertions.assertEquals(List.of(), model.validate());
	}

	@Test
	void applyToSetsEveryOwnedFieldButLeavesIdAndSortOrderAlone() {
		ApiEndpointFormModel model = ApiEndpointFormModel.newEndpoint(5);
		model.setName("Create order");
		model.setMethod("POST");
		model.setPath("${baseUrl}/orders");
		model.setAlias(" DO_ORDER ");
		model.setBodyMode("json");
		model.setBodyContent("{}");

		ApiEndpoint endpoint = new ApiEndpoint();
		endpoint.setId(42);
		endpoint.setSortOrder(3);

		model.applyTo(endpoint);

		Assertions.assertEquals(42, endpoint.getId(), "applyTo must never touch the endpoint's own ID");
		Assertions.assertEquals(3, endpoint.getSortOrder(), "applyTo must never touch sort order - that's the tree panel's concern");
		Assertions.assertEquals("Create order", endpoint.getName());
		Assertions.assertEquals("POST", endpoint.getMethod());
		Assertions.assertEquals("${baseUrl}/orders", endpoint.getEndpointPath());
		Assertions.assertEquals(5, endpoint.getGroupId());
		Assertions.assertEquals("DO_ORDER", endpoint.getAlias(), "alias must be trimmed on apply");
		Assertions.assertEquals("json", endpoint.getBodyMode());
	}

	@Test
	void applyToStoresANullAliasWhenBlank() {
		ApiEndpointFormModel model = ApiEndpointFormModel.newEndpoint(null);
		model.setName("Get order");
		model.setMethod("GET");
		model.setPath("/orders");
		model.setAlias("   ");
		ApiEndpoint endpoint = new ApiEndpoint();

		model.applyTo(endpoint);

		Assertions.assertNull(endpoint.getAlias());
	}

	@Test
	void roundTripsFromAnExistingEndpoint() {
		ApiEndpoint endpoint = new ApiEndpoint(1, 5, "Get order", "GET", "/orders/${id}", 0);
		endpoint.setId(7);
		endpoint.setAlias("GET_ORDER");

		ApiEndpointFormModel model = ApiEndpointFormModel.fromEndpoint(endpoint);

		Assertions.assertEquals(7, model.getId());
		Assertions.assertEquals("Get order", model.getName());
		Assertions.assertEquals("GET", model.getMethod());
		Assertions.assertEquals("/orders/${id}", model.getPath());
		Assertions.assertEquals(5, model.getGroupId());
		Assertions.assertEquals("GET_ORDER", model.getAlias());
	}
}
