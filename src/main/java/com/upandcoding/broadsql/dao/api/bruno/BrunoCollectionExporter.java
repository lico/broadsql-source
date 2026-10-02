package com.upandcoding.broadsql.dao.api.bruno;

import static com.upandcoding.broadsql.dao.api.bruno.BrunoPlaceholderNormalizer.denormalize;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com.google.gson.Gson;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Exports an {@link ApiDefinitionsVault} API back to bundled OpenCollection YAML - the structural mirror
 * of {@link BrunoCollectionImporter}: walks {@code ApiDefinition -> ApiVersion -> ApiEndpointGroup} tree
 * {@code -> ApiEndpoint} and builds the same {@code LinkedHashMap}/{@code ArrayList} tree shape the
 * importer parses (verified against {@code src/test/resources/bruno/sample-collection-v2.yml}), then
 * serializes with SnakeYAML's {@code Yaml.dump} (already a project dependency - no new one needed).
 *
 * <p>This must produce valid, current bundled OpenCollection YAML that Bruno itself can consume - never a
 * BroadSQL-specific pseudo-format (docs/SPRINT XT02-sub sprint 5 - API Configuration GUI + Bruno YAML
 * Round-trip.md, section 35). The round-trip guarantee this sprint asks for is explicitly
 * <b>semantic</b>, never byte-identical (section 39): in particular, {@link ApiEndpointGroup}/
 * {@link ApiEndpoint} each carry their own independent {@code SORT_ORDER} sequence, so this exporter
 * cannot reconstruct the exact interleaving of folders and requests a hand-authored {@code items} list
 * might have had - it emits every child folder (sorted by its own {@code sortOrder}) followed by every
 * child endpoint (sorted by its own {@code sortOrder}) at each tree level, which round-trips correctly
 * under {@link BrunoCollectionImporter}'s own (parent, method, path)/(parent, name) identity keys
 * regardless of list order.
 *
 * <p><b>Alias is never exported</b> (docs/Amendment - Endpoint Aliases and Future Scriptability.md,
 * section 11) - a deliberate, permanent, documented limitation: OpenCollection has no field for it, and
 * overloading an existing field (e.g. {@code name}) would corrupt the exported collection's meaning,
 * which the amendment explicitly rules out as worse than not round-tripping the alias at all.
 *
 * <p>Only currently-{@code ACTIVE} rows are exported - a deactivated environment/folder/endpoint is
 * treated exactly like a deleted one for export purposes (it will not be recreated by a subsequent
 * re-import of the exported file either, consistent with the importer's own upsert semantics only ever
 * inserting/updating what it currently sees).
 */
public class BrunoCollectionExporter {

	/** Mirrors {@code BrunoCollectionImporter}'s own {@code RAW_BODY_TYPES} list - kept in sync deliberately, not shared, since the importer's is {@code private}. */
	private static final List<String> RAW_BODY_TYPES = List.of("json", "text", "xml", "sparql");

	private final ApiDefinitionsVault vault;

	public BrunoCollectionExporter(ApiDefinitionsVault vault) {
		this.vault = vault;
	}

	/**
	 * Exports {@code apiId} to {@code targetFile} as bundled OpenCollection YAML.
	 *
	 * @return diagnostics about anything that could not be represented exactly (chiefly an
	 *         unreconstructable {@link com.upandcoding.broadsql.dao.api.model.ApiAuthType#UNSUPPORTED} auth)
	 */
	public BrunoExportResult exportToFile(String apiId, File targetFile, BrunoExportOptions options) throws BroadSQLException {
		BrunoExportResult result = new BrunoExportResult();
		Map<String, Object> root = exportRoot(apiId, options, result);
		DumperOptions dumperOptions = new DumperOptions();
		dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		dumperOptions.setPrettyFlow(true);
		try (Writer writer = new OutputStreamWriter(new FileOutputStream(targetFile), StandardCharsets.UTF_8)) {
			new Yaml(dumperOptions).dump(root, writer);
		} catch (IOException e) {
			throw new BroadSQLException(e);
		}
		return result;
	}

	/** Package-visible for {@code TestBrunoCollectionExporter}/{@code TestBrunoRoundTripSemantic} - builds the same generic tree {@link #exportToFile} serializes, without touching the filesystem. */
	Map<String, Object> exportRoot(String apiId, BrunoExportOptions options, BrunoExportResult result) throws BroadSQLException {
		ApiDefinition api = vault.getApi(apiId);
		if (api == null) {
			throw new BroadSQLException("API '" + apiId + "' does not exist or is not active.");
		}
		ApiVersion version = vault.getDefaultVersion(apiId);
		if (version == null) {
			throw new BroadSQLException("API '" + apiId + "' has no default version.");
		}
		BrunoExportOptions effectiveOptions = options != null ? options : new BrunoExportOptions();
		boolean includeSecrets = effectiveOptions.isIncludeSecrets();

		Map<String, Object> root = new LinkedHashMap<>();
		root.put("opencollection", "1.0.0");
		root.put("bundled", true);

		Map<String, Object> info = new LinkedHashMap<>();
		info.put("name", api.getName());
		root.put("info", info);

		Map<String, Object> config = new LinkedHashMap<>();
		List<Object> environments = new ArrayList<>();
		for (ApiEnvironment env : vault.getEnvironmentsForApi(apiId)) {
			if (!isActive(env.getStatusId()) || !effectiveOptions.includesEnvironment(env.getId())) {
				continue;
			}
			environments.add(exportEnvironment(env, includeSecrets));
		}
		config.put("environments", environments);
		root.put("config", config);

		Map<String, Object> requestDefaults = buildRequestDefaults(ApiOwnerType.API, apiId, includeSecrets, result, api.getName());
		if (!requestDefaults.isEmpty()) {
			root.put("request", requestDefaults);
		}

		root.put("items", buildItems(version.getId(), null, includeSecrets, result, api.getName()));

		return root;
	}

	// ------------------------------------------------------------------------------------------
	// Environments
	// ------------------------------------------------------------------------------------------

	private Map<String, Object> exportEnvironment(ApiEnvironment env, boolean includeSecrets) throws BroadSQLException {
		Map<String, Object> node = new LinkedHashMap<>();
		node.put("name", env.getName());
		List<ApiAttribute> variables = vault.getAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE);
		List<Object> variableNodes = new ArrayList<>();
		boolean hasBaseUrlVariable = false;
		for (ApiAttribute attr : variables) {
			variableNodes.add(attributeNode(attr, includeSecrets));
			if ("baseUrl".equalsIgnoreCase(attr.getName())) {
				hasBaseUrlVariable = true;
			}
		}
		// Defensive: an environment saved directly via saveEnvironment (bypassing setEnvironmentBaseUrl -
		// legacy data, or a test fixture) can have a BASE_URL column value with no matching VARIABLE
		// attribute row. Synthesize one rather than silently losing the base URL on export.
		if (!hasBaseUrlVariable && env.getBaseUrl() != null) {
			Map<String, Object> synthesized = new LinkedHashMap<>();
			synthesized.put("name", "baseUrl");
			synthesized.put("value", denormalize(env.getBaseUrl()));
			variableNodes.add(0, synthesized);
		}
		node.put("variables", variableNodes);
		return node;
	}

	// ------------------------------------------------------------------------------------------
	// Items: folders and HTTP requests
	// ------------------------------------------------------------------------------------------

	private List<Object> buildItems(int apiVersionId, Integer parentGroupId, boolean includeSecrets, BrunoExportResult result, String apiName) throws BroadSQLException {
		List<Object> items = new ArrayList<>();

		List<ApiEndpointGroup> childGroups = new ArrayList<>();
		for (ApiEndpointGroup group : vault.getGroupsForVersion(apiVersionId)) {
			if (isActive(group.getStatusId()) && Objects.equals(group.getParentGroupId(), parentGroupId)) {
				childGroups.add(group);
			}
		}
		childGroups.sort(Comparator.comparingInt(ApiEndpointGroup::getSortOrder));
		for (ApiEndpointGroup group : childGroups) {
			items.add(buildFolder(group, includeSecrets, result, apiName));
		}

		List<ApiEndpoint> childEndpoints = new ArrayList<>();
		if (parentGroupId == null) {
			for (ApiEndpoint endpoint : vault.getEndpointsForVersion(apiVersionId)) {
				if (isActive(endpoint.getStatusId()) && endpoint.getGroupId() == null) {
					childEndpoints.add(endpoint);
				}
			}
		} else {
			for (ApiEndpoint endpoint : vault.getEndpointsForGroup(parentGroupId)) {
				if (isActive(endpoint.getStatusId())) {
					childEndpoints.add(endpoint);
				}
			}
		}
		childEndpoints.sort(Comparator.comparingInt(ApiEndpoint::getSortOrder));
		for (ApiEndpoint endpoint : childEndpoints) {
			items.add(buildEndpointItem(endpoint, includeSecrets, result, apiName));
		}

		return items;
	}

	private Map<String, Object> buildFolder(ApiEndpointGroup group, boolean includeSecrets, BrunoExportResult result, String apiName) throws BroadSQLException {
		Map<String, Object> folder = new LinkedHashMap<>();
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("name", group.getName());
		info.put("type", "folder");
		info.put("seq", group.getSortOrder());
		folder.put("info", info);

		String contextLabel = apiName + " / " + group.getName();
		Map<String, Object> requestDefaults = buildRequestDefaults(ApiOwnerType.GROUP, String.valueOf(group.getId()), includeSecrets, result, contextLabel);
		if (!requestDefaults.isEmpty()) {
			folder.put("request", requestDefaults);
		}

		folder.put("items", buildItems(group.getApiVersionId(), group.getId(), includeSecrets, result, apiName));
		return folder;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> buildEndpointItem(ApiEndpoint endpoint, boolean includeSecrets, BrunoExportResult result, String apiName) throws BroadSQLException {
		Map<String, Object> item = new LinkedHashMap<>();
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("name", endpoint.getName());
		info.put("type", "http");
		info.put("seq", endpoint.getSortOrder());
		item.put("info", info);

		Map<String, Object> http = new LinkedHashMap<>();
		http.put("method", endpoint.getMethod());
		http.put("url", denormalize(endpoint.getEndpointPath()));

		String endpointOwnerId = String.valueOf(endpoint.getId());

		List<Object> headers = new ArrayList<>();
		for (ApiAttribute header : vault.getAttributes(ApiOwnerType.ENDPOINT, endpointOwnerId, ApiAttributeKind.HEADER)) {
			headers.add(attributeNode(header, includeSecrets));
		}
		if (!headers.isEmpty()) {
			http.put("headers", headers);
		}

		List<Object> params = new ArrayList<>();
		for (ApiAttribute queryParam : vault.getAttributes(ApiOwnerType.ENDPOINT, endpointOwnerId, ApiAttributeKind.QUERY_PARAMETER)) {
			params.add(paramNode(queryParam, "query", includeSecrets));
		}
		for (ApiAttribute pathParam : vault.getAttributes(ApiOwnerType.ENDPOINT, endpointOwnerId, ApiAttributeKind.PATH_PARAMETER)) {
			params.add(paramNode(pathParam, "path", includeSecrets));
		}
		if (!params.isEmpty()) {
			http.put("params", params);
		}

		Map<String, Object> body = buildBody(endpoint);
		if (body != null) {
			http.put("body", body);
		}

		String contextLabel = apiName + " / " + endpoint.getName();
		Map<String, Object> auth = buildAuthNode(ApiOwnerType.ENDPOINT, endpointOwnerId, includeSecrets, result, contextLabel);
		if (auth != null) {
			http.put("auth", auth);
		}

		item.put("http", http);

		List<ApiAttribute> variables = vault.getAttributes(ApiOwnerType.ENDPOINT, endpointOwnerId, ApiAttributeKind.VARIABLE);
		if (!variables.isEmpty()) {
			Map<String, Object> runtime = new LinkedHashMap<>();
			List<Object> variableNodes = new ArrayList<>();
			for (ApiAttribute variable : variables) {
				variableNodes.add(attributeNode(variable, includeSecrets));
			}
			runtime.put("variables", variableNodes);
			item.put("runtime", runtime);
		}

		// Alias is deliberately never written here - see this class's Javadoc and docs/Amendment -
		// Endpoint Aliases and Future Scriptability.md, section 11.

		return item;
	}

	/**
	 * Reverse of {@code BrunoCollectionImporter#applyBody}. A raw body type ({@code json}/{@code text}/
	 * {@code xml}/{@code sparql}) round-trips as {@code {type, data: bodyContent}}, unchanged. A
	 * structured body's {@code bodyContent} already holds the <em>entire</em> body subtree - including
	 * its own {@code type} key - as canonical JSON (see {@code applyBody}'s javadoc), so this simply
	 * parses it back verbatim rather than re-wrapping it a second time.
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> buildBody(ApiEndpoint endpoint) {
		String mode = endpoint.getBodyMode();
		if (mode == null) {
			return null;
		}
		if (RAW_BODY_TYPES.contains(mode)) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("type", mode);
			body.put("data", denormalize(endpoint.getBodyContent()));
			return body;
		}
		if (endpoint.getBodyContent() == null) {
			return null;
		}
		Object parsed = new Gson().fromJson(endpoint.getBodyContent(), Object.class);
		Object denormalized = denormalizeDeep(parsed);
		return denormalized instanceof Map ? (Map<String, Object>) denormalized : null;
	}

	/** Recursively applies {@link BrunoPlaceholderNormalizer#denormalize} to every string leaf of a generic {@code Map}/{@code List} tree - the export mirror of {@code BrunoCollectionImporter#normalizeDeep}. */
	@SuppressWarnings("unchecked")
	private Object denormalizeDeep(Object node) {
		if (node instanceof String) {
			return denormalize((String) node);
		}
		if (node instanceof Map<?, ?> map) {
			Map<String, Object> result = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				result.put(String.valueOf(entry.getKey()), denormalizeDeep(entry.getValue()));
			}
			return result;
		}
		if (node instanceof List<?> list) {
			List<Object> result = new ArrayList<>();
			for (Object item : list) {
				result.add(denormalizeDeep(item));
			}
			return result;
		}
		return node;
	}

	// ------------------------------------------------------------------------------------------
	// Shared: request defaults, auth, attribute/param nodes
	// ------------------------------------------------------------------------------------------

	private Map<String, Object> buildRequestDefaults(ApiOwnerType ownerType, String ownerId, boolean includeSecrets, BrunoExportResult result, String contextLabel) throws BroadSQLException {
		Map<String, Object> node = new LinkedHashMap<>();
		List<Object> headers = new ArrayList<>();
		for (ApiAttribute header : vault.getAttributes(ownerType, ownerId, ApiAttributeKind.HEADER)) {
			headers.add(attributeNode(header, includeSecrets));
		}
		if (!headers.isEmpty()) {
			node.put("headers", headers);
		}
		List<Object> variables = new ArrayList<>();
		for (ApiAttribute variable : vault.getAttributes(ownerType, ownerId, ApiAttributeKind.VARIABLE)) {
			variables.add(attributeNode(variable, includeSecrets));
		}
		if (!variables.isEmpty()) {
			node.put("variables", variables);
		}
		Map<String, Object> auth = buildAuthNode(ownerType, ownerId, includeSecrets, result, contextLabel);
		if (auth != null) {
			node.put("auth", auth);
		}
		return node;
	}

	/** {@code null} when no {@code API_AUTH} row exists at all for this owner - "inherit", omitted entirely, matching how the importer treats an absent/"{@code inherit}" auth node identically (see {@link BrunoAuthMapper#map}). */
	private Map<String, Object> buildAuthNode(ApiOwnerType ownerType, String ownerId, boolean includeSecrets, BrunoExportResult result, String contextLabel) throws BroadSQLException {
		ApiAuthConfig auth = vault.getAuth(ownerType, ownerId);
		if (auth == null) {
			return null;
		}
		List<ApiAttribute> properties = vault.getAttributes(ApiOwnerType.AUTH, String.valueOf(auth.getId()), ApiAttributeKind.PROPERTY);
		return BrunoAuthMapper.unmap(auth.getAuthType(), properties, includeSecrets, contextLabel, result);
	}

	/** Shared {@code {name, value, secret, disabled}} node shape for both variables and headers - see {@link BrunoCollectionImporter#mapVariable}/{@code mapHeaderOrParam} for the field names this mirrors. */
	private Map<String, Object> attributeNode(ApiAttribute attr, boolean includeSecrets) {
		Map<String, Object> node = new LinkedHashMap<>();
		node.put("name", attr.getName());
		if (!(attr.isSecret() && !includeSecrets)) {
			node.put("value", denormalize(attr.getValue()));
		}
		if (attr.isSecret()) {
			node.put("secret", true);
		}
		if (!attr.isEnabled()) {
			node.put("disabled", true);
		}
		return node;
	}

	/**
	 * Query/path parameter node - {@code {name, value, type, secret, disabled}}. Previously always wrote
	 * {@code value} unconditionally and never wrote {@code secret} at all, unlike {@link #attributeNode}'s
	 * identical field shape for headers/variables: a secret-flagged query or path parameter's value was
	 * exported in the clear even when {@code includeSecrets} was {@code false}, and its secret status was
	 * lost outright (SPRINT XT02 verification finding 6 - the audit's own repro used a header, but query/
	 * path parameters had the same gap, one step worse since the value itself leaked unconditionally, not
	 * only on re-import).
	 */
	private Map<String, Object> paramNode(ApiAttribute attr, String type, boolean includeSecrets) {
		Map<String, Object> node = new LinkedHashMap<>();
		node.put("name", attr.getName());
		if (!(attr.isSecret() && !includeSecrets)) {
			node.put("value", denormalize(attr.getValue()));
		}
		node.put("type", type);
		if (attr.isSecret()) {
			node.put("secret", true);
		}
		if (!attr.isEnabled()) {
			node.put("disabled", true);
		}
		return node;
	}

	private boolean isActive(String statusId) {
		return DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(statusId);
	}
}
