package com.upandcoding.broadsql.controller.shell.swing.api.form;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiAuthType;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

class TestApiAuthFormModel {

	@Test
	void noAuthRowMapsToInherit() {
		ApiAuthFormModel model = ApiAuthFormModel.fromAuth(null, List.of());
		Assertions.assertEquals(ApiAuthChoice.INHERIT, model.getChoice());
	}

	@Test
	void loadsBasicAuthFields() {
		ApiAuthConfig auth = new ApiAuthConfig(ApiOwnerType.API, "GITHUB", ApiAuthType.BASIC);
		List<ApiAttribute> props = List.of(prop("username", "${user}", false), prop("password", "${pass}", true));

		ApiAuthFormModel model = ApiAuthFormModel.fromAuth(auth, props);

		Assertions.assertEquals(ApiAuthChoice.BASIC, model.getChoice());
		Assertions.assertEquals("${user}", model.getUsername());
		Assertions.assertEquals("${pass}", model.getPassword());
	}

	@Test
	void loadsOAuth2Fields() {
		ApiAuthConfig auth = new ApiAuthConfig(ApiOwnerType.API, "GITHUB", ApiAuthType.OAUTH2_CLIENT_CREDENTIALS);
		List<ApiAttribute> props = List.of(
				prop("accessTokenUrl", "${baseUrl}/oauth/token", false),
				prop("clientId", "${clientId}", false),
				prop("clientSecret", "${clientSecret}", true),
				prop("tokenPlacement", "basic_auth_header", false),
				prop("scope", "admin.read", false));

		ApiAuthFormModel model = ApiAuthFormModel.fromAuth(auth, props);

		Assertions.assertEquals(ApiAuthChoice.OAUTH2_CLIENT_CREDENTIALS, model.getChoice());
		Assertions.assertEquals("${baseUrl}/oauth/token", model.getTokenUrl());
		Assertions.assertEquals("admin.read", model.getScope());
		Assertions.assertEquals("basic_auth_header", model.getClientAuthMethod());
	}

	@Test
	void loadsUnsupportedAuthAsReadOnlyInformational() {
		ApiAuthConfig auth = new ApiAuthConfig(ApiOwnerType.ENDPOINT, "5", ApiAuthType.UNSUPPORTED);
		List<ApiAttribute> props = List.of(prop("unsupportedSourceAuthType", "digest", false));

		ApiAuthFormModel model = ApiAuthFormModel.fromAuth(auth, props);

		Assertions.assertEquals(ApiAuthChoice.UNSUPPORTED, model.getChoice());
		Assertions.assertEquals("digest", model.getUnsupportedSourceType());
	}

	@Test
	void oauth2RequiresATokenUrl() {
		ApiAuthFormModel model = ApiAuthFormModel.inheritDefault();
		model.setChoice(ApiAuthChoice.OAUTH2_CLIENT_CREDENTIALS);

		Assertions.assertEquals(List.of("OAuth token URL is required."), model.validate());
	}

	@Test
	void apiKeyRequiresAName() {
		ApiAuthFormModel model = ApiAuthFormModel.inheritDefault();
		model.setChoice(ApiAuthChoice.API_KEY_HEADER);

		Assertions.assertEquals(List.of("API key name is required."), model.validate());
	}

	@Test
	void inheritAndNoneNeverRequireAnything() {
		ApiAuthFormModel inherit = ApiAuthFormModel.inheritDefault();
		ApiAuthFormModel none = ApiAuthFormModel.inheritDefault();
		none.setChoice(ApiAuthChoice.NONE);

		Assertions.assertEquals(List.of(), inherit.validate());
		Assertions.assertEquals(List.of(), none.validate());
	}

	@Test
	void doesNotRequireACredentialValueToResolveAtConfigurationTime() {
		// Section 30: "Do not require variables such as ${token} to resolve at configuration time" -
		// a Bearer token left as a literal, unresolved ${token} reference must still validate cleanly.
		ApiAuthFormModel model = ApiAuthFormModel.inheritDefault();
		model.setChoice(ApiAuthChoice.BEARER);
		model.setToken("${token}");

		Assertions.assertEquals(List.of(), model.validate());
	}

	@Test
	void toAuthTypeRefusesInheritAndUnsupported() {
		ApiAuthFormModel inherit = ApiAuthFormModel.inheritDefault();
		Assertions.assertThrows(IllegalStateException.class, inherit::toAuthType);

		ApiAuthFormModel unsupported = ApiAuthFormModel.inheritDefault();
		unsupported.setChoice(ApiAuthChoice.UNSUPPORTED);
		Assertions.assertThrows(IllegalStateException.class, unsupported::toAuthType);
	}

	@Test
	void toPropertiesBuildsTheExpectedBasicAuthPropertySet() {
		ApiAuthFormModel model = ApiAuthFormModel.inheritDefault();
		model.setChoice(ApiAuthChoice.BASIC);
		model.setUsername("${user}");
		model.setPassword("${pass}");

		List<ApiAttribute> props = model.toProperties(99);

		Assertions.assertEquals(2, props.size());
		Assertions.assertTrue(props.stream().anyMatch(p -> "username".equals(p.getName()) && "${user}".equals(p.getValue()) && !p.isSecret()));
		Assertions.assertTrue(props.stream().anyMatch(p -> "password".equals(p.getName()) && p.isSecret()));
		Assertions.assertTrue(props.stream().allMatch(p -> "99".equals(p.getOwnerId())));
	}

	@Test
	void toAuthTypeMapsEveryConcreteChoice() {
		Assertions.assertEquals(ApiAuthType.NONE, choiceModel(ApiAuthChoice.NONE).toAuthType());
		Assertions.assertEquals(ApiAuthType.BASIC, choiceModel(ApiAuthChoice.BASIC).toAuthType());
		Assertions.assertEquals(ApiAuthType.BEARER, choiceModel(ApiAuthChoice.BEARER).toAuthType());
		Assertions.assertEquals(ApiAuthType.API_KEY_HEADER, choiceModel(ApiAuthChoice.API_KEY_HEADER).toAuthType());
		Assertions.assertEquals(ApiAuthType.API_KEY_QUERY, choiceModel(ApiAuthChoice.API_KEY_QUERY).toAuthType());
		Assertions.assertEquals(ApiAuthType.OAUTH2_CLIENT_CREDENTIALS, choiceModel(ApiAuthChoice.OAUTH2_CLIENT_CREDENTIALS).toAuthType());
	}

	private ApiAuthFormModel choiceModel(ApiAuthChoice choice) {
		ApiAuthFormModel model = ApiAuthFormModel.inheritDefault();
		model.setChoice(choice);
		return model;
	}

	private static ApiAttribute prop(String name, String value, boolean secret) {
		return new ApiAttribute(ApiOwnerType.AUTH, "1", ApiAttributeKind.PROPERTY, name, value, secret);
	}
}
