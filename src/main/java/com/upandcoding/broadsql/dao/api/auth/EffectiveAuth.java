package com.upandcoding.broadsql.dao.api.auth;

import java.util.List;

import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAuthType;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * The authentication that actually applies to one endpoint, after walking the ownership chain - see
 * {@link ApiEffectiveAuthResolver}. {@link #getSourceOwnerType()}/{@link #getSourceOwnerId()} are
 * {@code null} for {@link #none()} (genuinely no authentication configured anywhere in the chain);
 * otherwise they name the owner ({@code ENDPOINT}/{@code GROUP}/{@code API}) whose row was found first.
 *
 * <p>{@link #toString()} redacts every secret property - the same reasoning as {@link ApiAttribute}'s own
 * redacted {@code toString()} - since this object is exactly the kind an incidental debug log statement
 * would otherwise print whole.
 */
public class EffectiveAuth {

	private final ApiOwnerType sourceOwnerType;
	private final String sourceOwnerId;
	private final ApiAuthType authType;
	private final List<ApiAttribute> properties;

	public EffectiveAuth(ApiOwnerType sourceOwnerType, String sourceOwnerId, ApiAuthType authType, List<ApiAttribute> properties) {
		this.sourceOwnerType = sourceOwnerType;
		this.sourceOwnerId = sourceOwnerId;
		this.authType = authType;
		this.properties = properties;
	}

	/** No {@code API_AUTH} row exists anywhere along the chain - equivalent in effect to an explicit {@code NONE}, but with no owning row. */
	public static EffectiveAuth none() {
		return new EffectiveAuth(null, null, ApiAuthType.NONE, List.of());
	}

	public ApiOwnerType getSourceOwnerType() {
		return sourceOwnerType;
	}

	public String getSourceOwnerId() {
		return sourceOwnerId;
	}

	public ApiAuthType getAuthType() {
		return authType;
	}

	/** The named property's value (e.g. {@code "token"}, {@code "accessTokenUrl"}), or {@code null} if not configured - never resolved/substituted here. */
	public String getProperty(String name) {
		return properties.stream().filter(p -> name.equals(p.getName())).findFirst().map(ApiAttribute::getValue).orElse(null);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("EffectiveAuth{sourceOwnerType=").append(sourceOwnerType)
				.append(", sourceOwnerId=").append(sourceOwnerId).append(", authType=").append(authType).append(", properties={");
		for (int i = 0; i < properties.size(); i++) {
			ApiAttribute p = properties.get(i);
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(p.getName()).append('=').append(p.isSecret() ? "******" : p.getValue());
		}
		return sb.append("}}").toString();
	}
}
