package com.upandcoding.broadsql.controller.shell.swing.api.form;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;

class TestApiFolderFormModel {

	@Test
	void requiresAName() {
		ApiFolderFormModel model = ApiFolderFormModel.newFolder(null);
		Assertions.assertEquals(List.of("Folder name is required."), model.validate());
	}

	@Test
	void newFolderCarriesTheGivenParent() {
		ApiFolderFormModel model = ApiFolderFormModel.newFolder(7);
		Assertions.assertEquals(7, model.getParentGroupId());
	}

	@Test
	void roundTripsFromAnExistingGroup() {
		ApiEndpointGroup group = new ApiEndpointGroup(1, 3, "Search", 0);
		group.setId(9);

		ApiFolderFormModel model = ApiFolderFormModel.fromGroup(group);

		Assertions.assertEquals(9, model.getId());
		Assertions.assertEquals("Search", model.getName());
		Assertions.assertEquals(3, model.getParentGroupId());
		Assertions.assertEquals(List.of(), model.validate());
	}
}
