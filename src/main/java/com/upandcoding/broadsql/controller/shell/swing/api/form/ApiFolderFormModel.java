package com.upandcoding.broadsql.controller.shell.swing.api.form;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;

/**
 * Plain, non-Swing form model for {@code CONFIG API}'s folder editor - docs/SPRINT XT02-sub sprint 5 -
 * API Configuration GUI + Bruno YAML Round-trip.md, section 22 ("Name, Parent folder, Variables,
 * Authentication"). Variables and Authentication are edited through the shared
 * {@link ApiAttributeListValidator}/{@link ApiAuthFormModel} widgets respectively (section 22 explicitly
 * reuses the same Inherit/None/Basic/.../Unsupported choice list endpoints and the API itself use), so
 * this model only carries the two fields unique to a folder: its name and its parent.
 */
public class ApiFolderFormModel {

	private Integer id;
	private String name;
	private Integer parentGroupId;

	public static ApiFolderFormModel newFolder(Integer parentGroupId) {
		ApiFolderFormModel model = new ApiFolderFormModel();
		model.parentGroupId = parentGroupId;
		return model;
	}

	public static ApiFolderFormModel fromGroup(ApiEndpointGroup group) {
		ApiFolderFormModel model = new ApiFolderFormModel();
		model.id = group.getId();
		model.name = group.getName();
		model.parentGroupId = group.getParentGroupId();
		return model;
	}

	public List<String> validate() {
		List<String> errors = new ArrayList<>();
		if (StringUtils.isBlank(name)) {
			errors.add("Folder name is required.");
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

	public Integer getParentGroupId() {
		return parentGroupId;
	}

	public void setParentGroupId(Integer parentGroupId) {
		this.parentGroupId = parentGroupId;
	}
}
