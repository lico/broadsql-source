package com.upandcoding.broadsql.controller.shell.swing.api;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiAuthChoice;
import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiAuthFormModel;

/**
 * The one reusable Authentication editor widget the sub-sprint plan calls for - used identically for
 * API-level (section 13), folder-level (section 22) and endpoint-level (section 27) authentication.
 * {@link ApiAuthChoice#INHERIT} is offered only when {@code allowInherit} is {@code true} (API-level
 * authentication has no parent to inherit from - section 13's own note).
 *
 * <p>Every secret-bearing credential field (Basic's password, Bearer's token, an API key's value, an
 * OAuth2 client secret) is a masked {@link JPasswordField}, matching this codebase's existing convention
 * for a credential field ({@code JSettingsFrame}'s connection password fields use the same plain,
 * always-masked {@code JPasswordField} with no reveal toggle - checked before adding one here, per SPRINT
 * XT02 verification finding 9: none of BroadSQL's existing Swing screens has a reveal-toggle convention to
 * follow, so this panel does not invent one). Every other field (username, an API key's name, OAuth2's
 * token URL/client ID/scope/client authentication method) stays a plain {@link JTextField} - sections
 * 14/15/16/17 are explicit that what is being edited here is a <em>configuration expression</em> (a
 * literal, or a {@code ${variable}} reference such as {@code ${password}}), not the resolved secret
 * itself, and variables are never resolved while editing (section 14: "Do not automatically resolve them
 * while editing") - masking is purely a screenshot/shoulder-surfing protection for whatever expression is
 * currently typed, not a claim that the field holds a resolved secret.
 *
 * <p>Thin Swing shell over {@link ApiAuthFormModel} - {@link #getModel()}/{@link #setModel} are its only
 * two responsibilities; validation is delegated entirely to the model, covered by
 * {@code TestApiAuthFormModel}.
 */
public class JApiAuthenticationPanel extends JPanel {

	private static final String CARD_BLANK = "blank";
	private static final String CARD_BASIC = "basic";
	private static final String CARD_BEARER = "bearer";
	private static final String CARD_API_KEY = "apikey";
	private static final String CARD_OAUTH2 = "oauth2";
	private static final String CARD_UNSUPPORTED = "unsupported";

	private final boolean allowInherit;

	private JComboBox<ApiAuthChoice> choiceCombo;
	private CardLayout cardLayout;
	private JPanel cardsPanel;

	private JTextField usernameField;
	private JPasswordField passwordField;
	private JPasswordField tokenField;
	private JTextField apiKeyNameField;
	private JPasswordField apiKeyValueField;
	private JTextField tokenUrlField;
	private JTextField clientIdField;
	private JPasswordField clientSecretField;
	private JTextField scopeField;
	private JTextField clientAuthMethodField;
	private JTextArea unsupportedInfo;

	public JApiAuthenticationPanel(boolean allowInherit) {
		this.allowInherit = allowInherit;
		buildUi();
	}

	private void buildUi() {
		setLayout(new BorderLayout());

		ApiAuthChoice[] choices = allowInherit
				? ApiAuthChoice.values()
				: java.util.Arrays.stream(ApiAuthChoice.values()).filter(c -> c != ApiAuthChoice.INHERIT).toArray(ApiAuthChoice[]::new);
		choiceCombo = new JComboBox<>(new DefaultComboBoxModel<>(choices));
		choiceCombo.addActionListener(e -> showCardFor((ApiAuthChoice) choiceCombo.getSelectedItem()));

		JPanel top = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
		top.add(new JLabel("Authentication:"));
		top.add(choiceCombo);
		add(top, BorderLayout.NORTH);

		cardLayout = new CardLayout();
		cardsPanel = new JPanel(cardLayout);
		cardsPanel.add(new JPanel(), CARD_BLANK);
		cardsPanel.add(buildBasicCard(), CARD_BASIC);
		cardsPanel.add(buildBearerCard(), CARD_BEARER);
		cardsPanel.add(buildApiKeyCard(), CARD_API_KEY);
		cardsPanel.add(buildOAuth2Card(), CARD_OAUTH2);
		cardsPanel.add(buildUnsupportedCard(), CARD_UNSUPPORTED);
		add(cardsPanel, BorderLayout.CENTER);
	}

	private JPanel buildBasicCard() {
		JPanel panel = field2("Username", usernameField = new JTextField(24), "Password", passwordField = new JPasswordField(24));
		passwordField.setToolTipText("Literal or ${variable} reference, e.g. ${password}");
		return panel;
	}

	private JPanel buildBearerCard() {
		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints c = gbc();
		panel.add(new JLabel("Token"), c);
		c.gridx = 1;
		c.weightx = 1;
		tokenField = new JPasswordField(24);
		tokenField.setToolTipText("Literal or ${variable} reference, e.g. ${token}");
		panel.add(tokenField, c);
		return panel;
	}

	private JPanel buildApiKeyCard() {
		return field2("Name", apiKeyNameField = new JTextField(24), "Value", apiKeyValueField = new JPasswordField(24));
	}

	private JPanel buildOAuth2Card() {
		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints c = gbc();
		String[] labels = { "Token URL", "Client ID", "Client Secret", "Scope", "Client authentication method" };
		tokenUrlField = new JTextField(24);
		clientIdField = new JTextField(24);
		clientSecretField = new JPasswordField(24);
		scopeField = new JTextField(24);
		clientAuthMethodField = new JTextField(24);
		JTextField[] fields = { tokenUrlField, clientIdField, clientSecretField, scopeField, clientAuthMethodField };
		for (int i = 0; i < labels.length; i++) {
			c.gridx = 0;
			c.gridy = i;
			c.weightx = 0;
			panel.add(new JLabel(labels[i]), c);
			c.gridx = 1;
			c.weightx = 1;
			panel.add(fields[i], c);
		}
		return panel;
	}

	private JPanel buildUnsupportedCard() {
		JPanel panel = new JPanel(new BorderLayout());
		unsupportedInfo = new JTextArea();
		unsupportedInfo.setEditable(false);
		unsupportedInfo.setLineWrap(true);
		unsupportedInfo.setWrapStyleWord(true);
		unsupportedInfo.setOpaque(false);
		unsupportedInfo.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		panel.add(unsupportedInfo, BorderLayout.CENTER);
		return panel;
	}

	private JPanel field2(String label1, JTextField field1, String label2, JTextField field2) {
		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints c = gbc();
		panel.add(new JLabel(label1), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(field1, c);
		c.gridx = 0;
		c.gridy = 1;
		c.weightx = 0;
		panel.add(new JLabel(label2), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(field2, c);
		return panel;
	}

	private GridBagConstraints gbc() {
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(5, 6, 5, 6);
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridx = 0;
		c.gridy = 0;
		return c;
	}

	private void showCardFor(ApiAuthChoice choice) {
		if (choice == null) {
			return;
		}
		switch (choice) {
			case BASIC:
				cardLayout.show(cardsPanel, CARD_BASIC);
				break;
			case BEARER:
				cardLayout.show(cardsPanel, CARD_BEARER);
				break;
			case API_KEY_HEADER:
			case API_KEY_QUERY:
				cardLayout.show(cardsPanel, CARD_API_KEY);
				break;
			case OAUTH2_CLIENT_CREDENTIALS:
				cardLayout.show(cardsPanel, CARD_OAUTH2);
				break;
			case UNSUPPORTED:
				cardLayout.show(cardsPanel, CARD_UNSUPPORTED);
				break;
			case INHERIT:
			case NONE:
			default:
				cardLayout.show(cardsPanel, CARD_BLANK);
				break;
		}
	}

	public void setModel(ApiAuthFormModel model) {
		choiceCombo.setSelectedItem(model.getChoice());
		usernameField.setText(model.getUsername());
		passwordField.setText(model.getPassword());
		tokenField.setText(model.getToken());
		apiKeyNameField.setText(model.getApiKeyName());
		apiKeyValueField.setText(model.getApiKeyValue());
		tokenUrlField.setText(model.getTokenUrl());
		clientIdField.setText(model.getClientId());
		clientSecretField.setText(model.getClientSecret());
		scopeField.setText(model.getScope());
		clientAuthMethodField.setText(model.getClientAuthMethod());
		if (model.getChoice() == ApiAuthChoice.UNSUPPORTED) {
			unsupportedInfo.setText("Authentication: Unsupported\nImported type: " + model.getUnsupportedSourceType()
					+ "\n\nThis authentication method is preserved but is not supported for execution by this BroadSQL release. "
					+ "You may replace it with a supported authentication type above.");
		}
		showCardFor(model.getChoice());
	}

	public ApiAuthFormModel getModel() {
		ApiAuthFormModel model = ApiAuthFormModel.inheritDefault();
		ApiAuthChoice choice = (ApiAuthChoice) choiceCombo.getSelectedItem();
		model.setChoice(choice == null ? ApiAuthChoice.INHERIT : choice);
		model.setUsername(usernameField.getText());
		// String.valueOf(char[]) - never charArray.toString(), which would yield a useless object
		// identity string like "[C@1a2b3c" instead of the actual field content (SPRINT XT02 verification
		// finding 9's own warning about incorrect JPasswordField char[] handling).
		model.setPassword(String.valueOf(passwordField.getPassword()));
		model.setToken(String.valueOf(tokenField.getPassword()));
		model.setApiKeyName(apiKeyNameField.getText());
		model.setApiKeyValue(String.valueOf(apiKeyValueField.getPassword()));
		model.setTokenUrl(tokenUrlField.getText());
		model.setClientId(clientIdField.getText());
		model.setClientSecret(String.valueOf(clientSecretField.getPassword()));
		model.setScope(scopeField.getText());
		model.setClientAuthMethod(clientAuthMethodField.getText());
		return model;
	}

	/** Named {@code validateModel}, not {@code validate} - {@link java.awt.Container} already declares a {@code void validate()} for layout purposes. */
	public List<String> validateModel() {
		return getModel().validate();
	}
}
