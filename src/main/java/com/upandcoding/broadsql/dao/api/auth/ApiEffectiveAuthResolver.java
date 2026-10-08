package com.upandcoding.broadsql.dao.api.auth;

import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * Determines the authentication that actually applies to one endpoint - docs/SPRINT XT02 - Universal API
 * Client.md, section 19.2. Walks:
 *
 * <pre>
 * endpoint explicit auth
 *         -&gt; nearest parent group auth
 *         -&gt; next parent group auth (repeated to the root)
 *         -&gt; API/collection auth
 *         -&gt; NONE if genuinely no auth exists anywhere
 * </pre>
 *
 * <p>The critical distinction this resolver relies on - already correctly preserved by
 * {@link ApiDefinitionsVault}'s persistence, re-verified during this sub-sprint rather than assumed - is
 * that {@link ApiDefinitionsVault#getAuth} returns {@code null} for "no row" (absent/{@code inherit} -
 * keep walking) and a non-null {@link ApiAuthConfig} for a row that exists, <b>including one whose type
 * is {@code NONE}</b> (explicit "no authentication" - stop walking immediately, right here, even though a
 * less specific owner might define real authentication). Conflating these two would make an endpoint's
 * explicit {@code NONE} inherit a parent's Bearer token instead of correctly suppressing it.
 */
public class ApiEffectiveAuthResolver {

	private final ApiDefinitionsVault vault;

	public ApiEffectiveAuthResolver(ApiDefinitionsVault vault) {
		this.vault = vault;
	}

	public EffectiveAuth resolve(String apiId, ApiEndpoint endpoint) throws BroadSQLException {
		if (endpoint != null) {
			EffectiveAuth endpointAuth = tryOwner(ApiOwnerType.ENDPOINT, String.valueOf(endpoint.getId()));
			if (endpointAuth != null) {
				return endpointAuth;
			}
			Integer groupId = endpoint.getGroupId();
			// Defensive cap against a corrupted PARENT_GROUP_ID cycle - mirrors ApiVariableResolver's own
			// group-chain walk.
			int maxDepth = 100;
			while (groupId != null && maxDepth-- > 0) {
				EffectiveAuth groupAuth = tryOwner(ApiOwnerType.GROUP, String.valueOf(groupId));
				if (groupAuth != null) {
					return groupAuth;
				}
				ApiEndpointGroup group = vault.findGroupById(groupId);
				groupId = group == null ? null : group.getParentGroupId();
			}
		}
		EffectiveAuth apiAuth = tryOwner(ApiOwnerType.API, apiId);
		if (apiAuth != null) {
			return apiAuth;
		}
		return EffectiveAuth.none();
	}

	private EffectiveAuth tryOwner(ApiOwnerType ownerType, String ownerId) throws BroadSQLException {
		ApiAuthConfig config = vault.getAuth(ownerType, ownerId);
		if (config == null) {
			return null;
		}
		List<ApiAttribute> properties = vault.getAttributes(ApiOwnerType.AUTH, String.valueOf(config.getId()), ApiAttributeKind.PROPERTY);
		return new EffectiveAuth(ownerType, ownerId, config.getAuthType(), properties);
	}
}
