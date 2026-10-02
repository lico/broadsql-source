package com.upandcoding.broadsql.controller.shell.swing.api;

import java.lang.reflect.Field;

import javax.swing.JPasswordField;
import javax.swing.JTextField;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiAuthChoice;
import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiAuthFormModel;

/**
 * SPRINT XT02 verification finding 9 (MEDIUM): every secret-bearing credential field in
 * {@link JApiAuthenticationPanel} (Basic's password, Bearer's token, an API key's value, an OAuth2 client
 * secret) used to be a plain {@link JTextField} - visibly plaintext in a screenshot. No component/pixel
 * rendering is exercised here (this development environment has no interactive terminal/display - see
 * this project's own testing conventions); this is a headless, structural check: only component
 * construction happens (no {@code setVisible}/painting), which does not require a real display.
 *
 * <p>Verifies two things: (1) the four secret fields are actually {@link JPasswordField} instances, never
 * plain {@link JTextField}, while every non-secret field (username, API key name, OAuth2 token URL/client
 * ID/scope/client auth method) remains a plain {@link JTextField} - masking must not over-reach; (2) the
 * {@code getModel()}/{@code setModel()} round trip through {@link JPasswordField#getPassword()} does not
 * corrupt the value (the finding's own warning about incorrect {@code char[]} handling - e.g. calling
 * {@code charArray.toString()} instead of {@code String.valueOf(charArray)}).
 */
class TestJApiAuthenticationPanelMasking {

	private static final String SYNTHETIC_SECRET = "synthetic-test-secret-456";

	@Test
	void secretBearingFieldsAreMaskedPasswordFields() throws Exception {
		JApiAuthenticationPanel panel = new JApiAuthenticationPanel(true);

		Assertions.assertInstanceOf(JPasswordField.class, field(panel, "passwordField"), "Basic auth's password field must be masked");
		Assertions.assertInstanceOf(JPasswordField.class, field(panel, "tokenField"), "Bearer auth's token field must be masked");
		Assertions.assertInstanceOf(JPasswordField.class, field(panel, "apiKeyValueField"), "an API key's value field must be masked");
		Assertions.assertInstanceOf(JPasswordField.class, field(panel, "clientSecretField"), "OAuth2's client secret field must be masked");
	}

	@Test
	void nonSecretFieldsRemainPlainTextFieldsNotOverMasked() throws Exception {
		JApiAuthenticationPanel panel = new JApiAuthenticationPanel(true);

		assertPlainTextFieldNotPassword(panel, "usernameField");
		assertPlainTextFieldNotPassword(panel, "apiKeyNameField");
		assertPlainTextFieldNotPassword(panel, "tokenUrlField");
		assertPlainTextFieldNotPassword(panel, "clientIdField");
		assertPlainTextFieldNotPassword(panel, "scopeField");
		assertPlainTextFieldNotPassword(panel, "clientAuthMethodField");
	}

	@Test
	void basicPasswordRoundTripsThroughSetModelAndGetModelWithoutCorruption() {
		JApiAuthenticationPanel panel = new JApiAuthenticationPanel(true);
		ApiAuthFormModel model = ApiAuthFormModel.inheritDefault();
		model.setChoice(ApiAuthChoice.BASIC);
		model.setUsername("someuser");
		model.setPassword(SYNTHETIC_SECRET);

		panel.setModel(model);
		ApiAuthFormModel roundTripped = panel.getModel();

		Assertions.assertEquals(SYNTHETIC_SECRET, roundTripped.getPassword(),
				"the password must survive the JPasswordField round trip unchanged - a char[].toString() bug "
						+ "would produce something like '[C@1a2b3c' instead");
		Assertions.assertEquals("someuser", roundTripped.getUsername());
	}

	@Test
	void bearerTokenRoundTripsThroughSetModelAndGetModelWithoutCorruption() {
		JApiAuthenticationPanel panel = new JApiAuthenticationPanel(true);
		ApiAuthFormModel model = ApiAuthFormModel.inheritDefault();
		model.setChoice(ApiAuthChoice.BEARER);
		model.setToken(SYNTHETIC_SECRET);

		panel.setModel(model);

		Assertions.assertEquals(SYNTHETIC_SECRET, panel.getModel().getToken());
	}

	@Test
	void apiKeyValueRoundTripsThroughSetModelAndGetModelWithoutCorruption() {
		JApiAuthenticationPanel panel = new JApiAuthenticationPanel(true);
		ApiAuthFormModel model = ApiAuthFormModel.inheritDefault();
		model.setChoice(ApiAuthChoice.API_KEY_HEADER);
		model.setApiKeyName("X-Api-Key");
		model.setApiKeyValue(SYNTHETIC_SECRET);

		panel.setModel(model);
		ApiAuthFormModel roundTripped = panel.getModel();

		Assertions.assertEquals(SYNTHETIC_SECRET, roundTripped.getApiKeyValue());
		Assertions.assertEquals("X-Api-Key", roundTripped.getApiKeyName());
	}

	@Test
	void oAuth2ClientSecretRoundTripsThroughSetModelAndGetModelWithoutCorruption() {
		JApiAuthenticationPanel panel = new JApiAuthenticationPanel(true);
		ApiAuthFormModel model = ApiAuthFormModel.inheritDefault();
		model.setChoice(ApiAuthChoice.OAUTH2_CLIENT_CREDENTIALS);
		model.setTokenUrl("https://example.com/oauth/token");
		model.setClientId("client-1");
		model.setClientSecret(SYNTHETIC_SECRET);

		panel.setModel(model);
		ApiAuthFormModel roundTripped = panel.getModel();

		Assertions.assertEquals(SYNTHETIC_SECRET, roundTripped.getClientSecret());
		Assertions.assertEquals("client-1", roundTripped.getClientId());
	}

	private void assertPlainTextFieldNotPassword(JApiAuthenticationPanel panel, String fieldName) throws Exception {
		Object value = field(panel, fieldName);
		Assertions.assertInstanceOf(JTextField.class, value, fieldName + " must still be a text field");
		Assertions.assertFalse(value instanceof JPasswordField, fieldName + " must not be masked - it never holds a secret value");
	}

	private Object field(JApiAuthenticationPanel panel, String name) throws Exception {
		Field field = JApiAuthenticationPanel.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(panel);
	}
}
