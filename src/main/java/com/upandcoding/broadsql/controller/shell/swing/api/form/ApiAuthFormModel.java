package com.upandcoding.broadsql.controller.shell.swing.api.form;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiAuthType;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * Plain, non-Swing form model for {@code CONFIG API}'s Authentication editor - docs/SPRINT XT02-sub
 * sprint 5 - API Configuration GUI + Bruno YAML Round-trip.md, sections 13-18/22/27: "one reusable
 * widget for API/group/endpoint scope" (the sub-sprint plan's own wording), since all three scopes offer
 * the same choice list and field layout, differing only in whether {@link ApiAuthChoice#INHERIT} is
 * offered at all (see {@link ApiAuthChoice}'s javadoc). The testable half of
 * {@code JApiAuthenticationPanel}.
 *
 * <p>Selecting {@link ApiAuthChoice#INHERIT} is the one choice {@link #toAuthType()} refuses to convert
 * (throws {@link IllegalStateException} - a programming error to call it in that state) - the caller
 * (the Swing panel) must instead delete the owner's {@code API_AUTH} row entirely when
 * {@link #getChoice()} is {@code INHERIT}, per {@code ApiEffectiveAuthResolver}'s null-means-inherit
 * contract (section 27: "Inherit means no local auth row").
 */
public class ApiAuthFormModel {

	private ApiAuthChoice choice = ApiAuthChoice.INHERIT;

	// Basic
	private String username;
	private String password;
	// Bearer
	private String token;
	// API key
	private String apiKeyName;
	private String apiKeyValue;
	// OAuth2 Client Credentials
	private String tokenUrl;
	private String clientId;
	private String clientSecret;
	private String scope;
	private String clientAuthMethod;
	// Unsupported (imported, read-only informational)
	private String unsupportedSourceType;

	public static ApiAuthFormModel inheritDefault() {
		return new ApiAuthFormModel();
	}

	/** @param auth {@code null} means "no row exists" -&gt; {@link ApiAuthChoice#INHERIT}. */
	public static ApiAuthFormModel fromAuth(ApiAuthConfig auth, List<ApiAttribute> properties) {
		ApiAuthFormModel model = new ApiAuthFormModel();
		if (auth == null) {
			model.choice = ApiAuthChoice.INHERIT;
			return model;
		}
		switch (auth.getAuthType()) {
			case NONE:
				model.choice = ApiAuthChoice.NONE;
				break;
			case BASIC:
				model.choice = ApiAuthChoice.BASIC;
				model.username = valueOf(properties, "username");
				model.password = valueOf(properties, "password");
				break;
			case BEARER:
				model.choice = ApiAuthChoice.BEARER;
				model.token = valueOf(properties, "token");
				break;
			case API_KEY_HEADER:
			case API_KEY_QUERY:
				model.choice = auth.getAuthType() == ApiAuthType.API_KEY_QUERY ? ApiAuthChoice.API_KEY_QUERY : ApiAuthChoice.API_KEY_HEADER;
				model.apiKeyName = valueOf(properties, "name");
				model.apiKeyValue = valueOf(properties, "value");
				break;
			case OAUTH2_CLIENT_CREDENTIALS:
				model.choice = ApiAuthChoice.OAUTH2_CLIENT_CREDENTIALS;
				model.tokenUrl = valueOf(properties, "accessTokenUrl");
				model.clientId = valueOf(properties, "clientId");
				model.clientSecret = valueOf(properties, "clientSecret");
				model.scope = valueOf(properties, "scope");
				model.clientAuthMethod = valueOf(properties, "tokenPlacement");
				break;
			case UNSUPPORTED:
			default:
				model.choice = ApiAuthChoice.UNSUPPORTED;
				model.unsupportedSourceType = valueOf(properties, "unsupportedSourceAuthType");
				break;
		}
		return model;
	}

	/**
	 * Structural validation only (section 30) - never requires a {@code ${variable}} reference to
	 * resolve at configuration time (section 30: "Do not require variables such as ${token} to resolve
	 * at configuration time because another environment may supply them later").
	 */
	public List<String> validate() {
		List<String> errors = new ArrayList<>();
		if (choice == ApiAuthChoice.OAUTH2_CLIENT_CREDENTIALS && StringUtils.isBlank(tokenUrl)) {
			errors.add("OAuth token URL is required.");
		}
		if ((choice == ApiAuthChoice.API_KEY_HEADER || choice == ApiAuthChoice.API_KEY_QUERY) && StringUtils.isBlank(apiKeyName)) {
			errors.add("API key name is required.");
		}
		return errors;
	}

	/** @throws IllegalStateException if {@link #getChoice()} is {@link ApiAuthChoice#INHERIT} or {@link ApiAuthChoice#UNSUPPORTED} - neither is ever written back as a fresh {@code API_AUTH} row (see the class javadoc). */
	public ApiAuthType toAuthType() {
		switch (choice) {
			case NONE:
				return ApiAuthType.NONE;
			case BASIC:
				return ApiAuthType.BASIC;
			case BEARER:
				return ApiAuthType.BEARER;
			case API_KEY_HEADER:
				return ApiAuthType.API_KEY_HEADER;
			case API_KEY_QUERY:
				return ApiAuthType.API_KEY_QUERY;
			case OAUTH2_CLIENT_CREDENTIALS:
				return ApiAuthType.OAUTH2_CLIENT_CREDENTIALS;
			default:
				throw new IllegalStateException("Cannot convert " + choice + " to an ApiAuthType - INHERIT means 'delete the auth row', "
						+ "UNSUPPORTED is read-only imported data the GUI never re-saves as-is.");
		}
	}

	/** The {@code PROPERTY} attributes to save under the new/updated {@code API_AUTH} row's generated ID - {@code authId} is that ID, applied to every returned attribute's owner. */
	public List<ApiAttribute> toProperties(int authId) {
		List<ApiAttribute> properties = new ArrayList<>();
		String ownerId = String.valueOf(authId);
		switch (choice) {
			case BASIC:
				properties.add(property(ownerId, "username", username, false));
				properties.add(property(ownerId, "password", password, true));
				break;
			case BEARER:
				properties.add(property(ownerId, "token", token, true));
				break;
			case API_KEY_HEADER:
			case API_KEY_QUERY:
				properties.add(property(ownerId, "name", apiKeyName, false));
				properties.add(property(ownerId, "value", apiKeyValue, true));
				break;
			case OAUTH2_CLIENT_CREDENTIALS:
				properties.add(property(ownerId, "accessTokenUrl", tokenUrl, false));
				properties.add(property(ownerId, "clientId", clientId, false));
				properties.add(property(ownerId, "clientSecret", clientSecret, true));
				if (StringUtils.isNotBlank(scope)) {
					properties.add(property(ownerId, "scope", scope, false));
				}
				if (StringUtils.isNotBlank(clientAuthMethod)) {
					properties.add(property(ownerId, "tokenPlacement", clientAuthMethod, false));
				}
				break;
			default:
				break;
		}
		return properties;
	}

	private static ApiAttribute property(String ownerId, String name, String value, boolean secret) {
		return new ApiAttribute(ApiOwnerType.AUTH, ownerId, ApiAttributeKind.PROPERTY, name, value, secret);
	}

	private static String valueOf(List<ApiAttribute> properties, String name) {
		for (ApiAttribute attr : properties) {
			if (name.equals(attr.getName())) {
				return attr.getValue();
			}
		}
		return null;
	}

	public ApiAuthChoice getChoice() {
		return choice;
	}

	public void setChoice(ApiAuthChoice choice) {
		this.choice = choice;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	public String getApiKeyName() {
		return apiKeyName;
	}

	public void setApiKeyName(String apiKeyName) {
		this.apiKeyName = apiKeyName;
	}

	public String getApiKeyValue() {
		return apiKeyValue;
	}

	public void setApiKeyValue(String apiKeyValue) {
		this.apiKeyValue = apiKeyValue;
	}

	public String getTokenUrl() {
		return tokenUrl;
	}

	public void setTokenUrl(String tokenUrl) {
		this.tokenUrl = tokenUrl;
	}

	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	public void setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
	}

	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

	public String getClientAuthMethod() {
		return clientAuthMethod;
	}

	public void setClientAuthMethod(String clientAuthMethod) {
		this.clientAuthMethod = clientAuthMethod;
	}

	public String getUnsupportedSourceType() {
		return unsupportedSourceType;
	}

	/**
	 * A deterministic string representation of every field - the value-snapshot dirty-tracking building
	 * block (API Quality and UX Consolidation sprint, Phase 2) for whichever panel embeds
	 * {@code JApiAuthenticationPanel}. Held only in memory for an equality comparison, exactly like the
	 * fields themselves already are - never logged or displayed.
	 */
	public String snapshotKey() {
		StringBuilder key = new StringBuilder();
		key.append(choice).append('\u0001');
		key.append(nullToEmpty(username)).append('\u0001');
		key.append(nullToEmpty(password)).append('\u0001');
		key.append(nullToEmpty(token)).append('\u0001');
		key.append(nullToEmpty(apiKeyName)).append('\u0001');
		key.append(nullToEmpty(apiKeyValue)).append('\u0001');
		key.append(nullToEmpty(tokenUrl)).append('\u0001');
		key.append(nullToEmpty(clientId)).append('\u0001');
		key.append(nullToEmpty(clientSecret)).append('\u0001');
		key.append(nullToEmpty(scope)).append('\u0001');
		key.append(nullToEmpty(clientAuthMethod)).append('\u0001');
		key.append(nullToEmpty(unsupportedSourceType));
		return key.toString();
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}
}
