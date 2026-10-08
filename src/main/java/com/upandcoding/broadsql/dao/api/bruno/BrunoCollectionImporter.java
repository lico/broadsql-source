package com.upandcoding.broadsql.dao.api.bruno;

import static com.upandcoding.broadsql.dao.api.bruno.BrunoPlaceholderNormalizer.normalize;
import static com.upandcoding.broadsql.dao.api.bruno.BrunoYamlUtil.asBoolean;
import static com.upandcoding.broadsql.dao.api.bruno.BrunoYamlUtil.asInteger;
import static com.upandcoding.broadsql.dao.api.bruno.BrunoYamlUtil.asList;
import static com.upandcoding.broadsql.dao.api.bruno.BrunoYamlUtil.asMap;
import static com.upandcoding.broadsql.dao.api.bruno.BrunoYamlUtil.asString;
import static com.upandcoding.broadsql.dao.api.bruno.BrunoYamlUtil.pathGet;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

import com.google.gson.Gson;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiUrlQueryStringSync;
import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.api.model.ApiImportSource;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * Imports a bundled OpenCollection YAML document (Bruno's {@code bundled: true} single-file
 * representation - see docs/SPRINT XT02 - Universal API Client.md, section 16) into an
 * {@link ApiDefinitionsVault}. Directory-based (multi-file, {@code bundled: false}) collections are not
 * yet supported - a deliberate first-round scope decision (see the sprint doc's sub-sprint 2 conclusions)
 * since both representations describe the exact same {@code Item} tree; a directory reader would only
 * need to assemble that same tree from multiple files before feeding it to this same importer.
 *
 * <p>Parses into generic {@code Map}/{@code List} structures (SnakeYAML with no POJO binding) rather than
 * typed classes for the OpenCollection schema - deliberately, since the schema's {@code oneOf}
 * discriminated unions (Auth, HttpRequestBody, Item) are exactly the case manual Map-walking handles more
 * directly than a data-binding library would.
 *
 * <p>Every import is an UPSERT, never a destructive sync (sprint doc, requirement 7): an object already
 * known by its {@code (SOURCE_TYPE, SOURCE_KEY)} identity is updated; a new one is inserted; an object
 * that existed from a previous import but is no longer present in the source is left untouched.
 *
 * <p><b>Identity, corrected (sprint doc section 18.1) - {@code seq} is never identity.</b> {@code seq} is
 * sort order, nothing else; it is persisted as {@link ApiEndpoint#getSortOrder()}/
 * {@link ApiEndpointGroup#getSortOrder()} but never appears in a source key, so reordering items in the
 * source can never duplicate, misattribute, or reassign an object's identity. A folder's source key is
 * {@code <parent path>/folder:<name>} - a folder carries no request-shaped data to key on, so its name is
 * the only structural handle available (an explicit, accepted limitation: renaming a folder is
 * indistinguishable from replacing it, same as any other case with no stable signal at all - see 17.7/18.4).
 * An endpoint's source key is {@code <parent path>/endpoint:<METHOD> <normalized path>} - method and path
 * survive a display-name rename (the key does not depend on {@code name} at all), which is exactly the
 * "rename" and "rename + reorder" scenarios this sub-sprint's correction was asked to prove. When neither
 * the key nor the name can relate a source item to anything already persisted (both method/path *and*
 * name change at once - genuinely ambiguous, no signal survives), the conservative choice is always
 * "insert a new object, never touch an unrelated existing one" - see {@link #importHttpRequest} and the
 * sprint doc section 18.4 for why guessing was rejected in favor of a diagnosable duplicate.
 */
public class BrunoCollectionImporter {

	private final ApiDefinitionsVault vault;

	public BrunoCollectionImporter(ApiDefinitionsVault vault) {
		this.vault = vault;
	}

	/**
	 * Imports {@code yamlFile} into the API identified by {@code apiId} - creates it (with its mandatory
	 * default version) if it does not exist yet, otherwise re-imports into the existing API.
	 */
	public BrunoImportResult importFile(String apiId, File yamlFile) throws BroadSQLException {
		Map<String, Object> root;
		try (Reader reader = new InputStreamReader(new FileInputStream(yamlFile), StandardCharsets.UTF_8)) {
			root = asMap(new Yaml().load(reader));
		} catch (IOException e) {
			throw new BroadSQLException(e);
		}
		if (root == null) {
			throw new BroadSQLException("'" + yamlFile.getAbsolutePath() + "' does not contain a valid OpenCollection document.");
		}
		return importRoot(apiId, root, yamlFile.getAbsolutePath());
	}

	BrunoImportResult importRoot(String apiId, Map<String, Object> root, String sourceLocation) throws BroadSQLException {
		BrunoImportResult result = new BrunoImportResult();

		Map<String, Object> info = asMap(root.get("info"));
		String apiName = info != null ? asString(info.get("name")) : apiId;

		ApiVersion version;
		if (!vault.contains(apiId)) {
			ApiDefinition api = new ApiDefinition(apiId);
			api.setName(apiName);
			version = vault.createApiWithDefaultVersion(api);
		} else {
			ApiDefinition api = vault.getApi(apiId);
			api.setName(apiName);
			vault.saveApi(api);
			version = vault.getDefaultVersion(apiId);
		}

		importRequestDefaults(asMap(root.get("request")), ApiOwnerType.API, apiId, result);

		for (Object envObj : asList(pathGet(root, "config", "environments"))) {
			importEnvironment(apiId, asMap(envObj), result);
		}

		importItems(asList(root.get("items")), version.getId(), null, "", result);

		vault.saveImportSource(new ApiImportSource(apiId, ApiImportSource.SOURCE_TYPE_BRUNO_YAML, sourceLocation, null));

		return result;
	}

	// ------------------------------------------------------------------------------------------
	// Environments
	// ------------------------------------------------------------------------------------------

	private void importEnvironment(String apiId, Map<String, Object> envNode, BrunoImportResult result) throws BroadSQLException {
		if (envNode == null) {
			return;
		}
		String name = asString(envNode.get("name"));
		// Bruno environments are not nested inside the item tree, and the name is the identity Bruno
		// itself treats as unique within a collection - the stable source key for this owner type.
		String sourceKey = name;

		ApiEnvironment existing = vault.findEnvironmentBySource(apiId, ApiImportSource.SOURCE_TYPE_BRUNO_YAML, sourceKey);
		boolean created = existing == null;
		ApiEnvironment env = created ? new ApiEnvironment(apiId, name, null, 0) : existing;
		env.setName(name);
		env.setSourceType(ApiImportSource.SOURCE_TYPE_BRUNO_YAML);
		env.setSourceKey(sourceKey);
		vault.saveEnvironment(env);
		result.recordEnvironment(created);

		List<ApiAttribute> variableAttrs = new ArrayList<>();
		String baseUrl = null;
		for (Object varObj : asList(envNode.get("variables"))) {
			ApiAttribute attr = mapVariable(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), asMap(varObj));
			variableAttrs.add(attr);
			if (baseUrl == null && attr.getValue() != null && ("baseUrl".equalsIgnoreCase(attr.getName()) || "url".equalsIgnoreCase(attr.getName()))) {
				baseUrl = attr.getValue();
			}
		}
		vault.replaceAttributes(ApiOwnerType.ENVIRONMENT, String.valueOf(env.getId()), ApiAttributeKind.VARIABLE, variableAttrs);

		// See docs/SPRINT XT02 - Universal API Client.md, section 16.2: baseUrl is a Bruno convention
		// (an ordinary variable), not a schema field - promoted here to the first-class column for
		// direct display/programmatic access, while remaining resolvable as a normal variable too.
		if (baseUrl != null && !baseUrl.equals(env.getBaseUrl())) {
			env.setBaseUrl(baseUrl);
			vault.saveEnvironment(env);
		}
	}

	// ------------------------------------------------------------------------------------------
	// Items: folders and HTTP requests (see section 16.1 for what every other Item variant maps to)
	// ------------------------------------------------------------------------------------------

	private void importItems(List<Object> items, int apiVersionId, Integer parentGroupId, String parentPath, BrunoImportResult result) throws BroadSQLException {
		int ordinal = 0;
		for (Object itemObj : items) {
			Map<String, Object> item = asMap(itemObj);
			if (item == null) {
				continue;
			}
			String itemType = detectItemType(item);
			if ("folder".equals(itemType)) {
				importFolder(item, apiVersionId, parentGroupId, parentPath, ordinal, result);
			} else if ("http".equals(itemType)) {
				importHttpRequest(item, apiVersionId, parentGroupId, parentPath, ordinal, result);
			} else {
				// GraphQLRequest, GrpcRequest, WebSocketRequest, ScriptFile, App - explicitly not imported
				// in this round (section 16.1); recorded, never fatal to the rest of the import.
				result.recordSkippedItem(itemType == null ? "unknown" : itemType);
			}
			ordinal++;
		}
	}

	private String detectItemType(Map<String, Object> item) {
		Object topLevelType = item.get("type");
		if (topLevelType instanceof String) {
			// ScriptFile has no "info" wrapper - {type: script, script: "..."} directly.
			return (String) topLevelType;
		}
		Map<String, Object> info = asMap(item.get("info"));
		return info != null ? asString(info.get("type")) : null;
	}

	private void importFolder(Map<String, Object> folder, int apiVersionId, Integer parentGroupId, String parentPath, int ordinal, BrunoImportResult result) throws BroadSQLException {
		Map<String, Object> info = asMap(folder.get("info"));
		String name = info != null ? asString(info.get("name")) : null;
		Integer seq = info != null ? asInteger(info.get("seq")) : null;
		int sortOrder = seq != null ? seq : ordinal;
		String sourceKey = folderSourceKey(parentPath, name);

		ApiEndpointGroup existing = vault.findGroupBySource(apiVersionId, ApiImportSource.SOURCE_TYPE_BRUNO_YAML, sourceKey);
		boolean created = existing == null;
		ApiEndpointGroup group = created ? new ApiEndpointGroup(apiVersionId, parentGroupId, name, sortOrder) : existing;
		group.setName(name);
		group.setParentGroupId(parentGroupId);
		group.setSortOrder(sortOrder);
		group.setSourceType(ApiImportSource.SOURCE_TYPE_BRUNO_YAML);
		group.setSourceKey(sourceKey);
		vault.saveEndpointGroup(group);
		result.recordGroup(created);

		importRequestDefaults(asMap(folder.get("request")), ApiOwnerType.GROUP, String.valueOf(group.getId()), result);

		importItems(asList(folder.get("items")), apiVersionId, group.getId(), sourceKey, result);
	}

	private void importHttpRequest(Map<String, Object> requestNode, int apiVersionId, Integer groupId, String parentPath, int ordinal, BrunoImportResult result) throws BroadSQLException {
		Map<String, Object> info = asMap(requestNode.get("info"));
		String name = info != null ? asString(info.get("name")) : null;
		Integer seq = info != null ? asInteger(info.get("seq")) : null;
		int sortOrder = seq != null ? seq : ordinal;

		Map<String, Object> http = asMap(requestNode.get("http"));
		String method = http != null ? asString(http.get("method")) : null;
		String url = http != null ? normalize(asString(http.get("url"))) : null;
		// sourceKey identifies "the same" endpoint across re-imports - always derived from the original,
		// unstripped url so re-running an import stays stable regardless of what gets stripped below.
		String sourceKey = endpointSourceKey(parentPath, method, url);

		// Persist a query-string-free base path when http.params already models a query parameter
		// structurally - never a raw url that duplicates what QUERY_PARAMETER attributes already own
		// (the sprint's persistence invariant). A query-string fragment with no structural counterpart
		// (an oddly-authored Bruno file) is left in place - ApiEndpointRequestBuilder reconciles it at
		// execution time regardless (see its "Correctness fix" javadoc).
		String storedUrl = stripStructurallyRepresentedQueryString(url, structuralQueryParamNames(http));

		ApiEndpoint existing = vault.findEndpointBySource(apiVersionId, ApiImportSource.SOURCE_TYPE_BRUNO_YAML, sourceKey);
		boolean created = existing == null;
		ApiEndpoint endpoint = created ? new ApiEndpoint(apiVersionId, groupId, name, method, storedUrl, sortOrder) : existing;
		endpoint.setName(name);
		endpoint.setGroupId(groupId);
		endpoint.setMethod(method);
		endpoint.setEndpointPath(storedUrl);
		endpoint.setSortOrder(sortOrder);
		endpoint.setSourceType(ApiImportSource.SOURCE_TYPE_BRUNO_YAML);
		endpoint.setSourceKey(sourceKey);

		Map<String, Object> body = http != null ? selectBody(http.get("body")) : null;
		if (body != null) {
			applyBody(endpoint, body);
		} else {
			endpoint.setBodyMode(null);
			endpoint.setBodyContent(null);
		}

		vault.saveEndpoint(endpoint);
		result.recordEndpoint(created);

		String endpointOwnerId = String.valueOf(endpoint.getId());

		List<ApiAttribute> headerAttrs = new ArrayList<>();
		List<ApiAttribute> queryParamAttrs = new ArrayList<>();
		List<ApiAttribute> pathParamAttrs = new ArrayList<>();
		if (http != null) {
			for (Object headerObj : asList(http.get("headers"))) {
				headerAttrs.add(mapHeaderOrParam(ApiOwnerType.ENDPOINT, endpointOwnerId, ApiAttributeKind.HEADER, asMap(headerObj)));
			}
			for (Object paramObj : asList(http.get("params"))) {
				Map<String, Object> param = asMap(paramObj);
				if (param == null) {
					continue;
				}
				boolean isPath = "path".equalsIgnoreCase(asString(param.get("type")));
				ApiAttribute attr = mapHeaderOrParam(ApiOwnerType.ENDPOINT, endpointOwnerId, isPath ? ApiAttributeKind.PATH_PARAMETER : ApiAttributeKind.QUERY_PARAMETER, param);
				(isPath ? pathParamAttrs : queryParamAttrs).add(attr);
			}
		}
		vault.replaceAttributes(ApiOwnerType.ENDPOINT, endpointOwnerId, ApiAttributeKind.HEADER, headerAttrs);
		vault.replaceAttributes(ApiOwnerType.ENDPOINT, endpointOwnerId, ApiAttributeKind.QUERY_PARAMETER, queryParamAttrs);
		vault.replaceAttributes(ApiOwnerType.ENDPOINT, endpointOwnerId, ApiAttributeKind.PATH_PARAMETER, pathParamAttrs);

		Map<String, Object> runtime = asMap(requestNode.get("runtime"));
		List<ApiAttribute> variableAttrs = new ArrayList<>();
		if (runtime != null) {
			for (Object varObj : asList(runtime.get("variables"))) {
				variableAttrs.add(mapVariable(ApiOwnerType.ENDPOINT, endpointOwnerId, asMap(varObj)));
			}
			result.addIgnoredRuntimeFeatures(asList(runtime.get("scripts")).size()
					+ asList(runtime.get("assertions")).size()
					+ asList(runtime.get("actions")).size());
		}
		vault.replaceAttributes(ApiOwnerType.ENDPOINT, endpointOwnerId, ApiAttributeKind.VARIABLE, variableAttrs);

		importAuth(http != null ? http.get("auth") : null, ApiOwnerType.ENDPOINT, endpointOwnerId, result);
	}

	/** The names of every {@code http.params} entry that is <b>not</b> a path parameter - i.e. every name that will be persisted as a structured {@code QUERY_PARAMETER} attribute for this request. */
	private List<String> structuralQueryParamNames(Map<String, Object> http) {
		List<String> names = new ArrayList<>();
		if (http == null) {
			return names;
		}
		for (Object paramObj : asList(http.get("params"))) {
			Map<String, Object> param = asMap(paramObj);
			if (param == null) {
				continue;
			}
			if (!"path".equalsIgnoreCase(asString(param.get("type")))) {
				names.add(asString(param.get("name")));
			}
		}
		return names;
	}

	/**
	 * Strips from {@code url}'s query string every occurrence already covered by
	 * {@code queryParamNames} (matched by name and occurrence index within that name's group, since
	 * BroadSQL allows duplicate query parameter names), leaving only base path + fragment when
	 * everything is covered. An occurrence with no structural counterpart at all is left in place, so
	 * an oddly-authored Bruno file (a query string with no matching {@code http.params} entry) is not
	 * silently discarded - {@link com.upandcoding.broadsql.dao.api.execution.ApiEndpointRequestBuilder}
	 * reconciles any such remainder at execution time regardless.
	 */
	private String stripStructurallyRepresentedQueryString(String url, List<String> queryParamNames) {
		if (url == null || queryParamNames.isEmpty()) {
			return url;
		}
		ApiUrlQueryStringSync.UrlParts parts = ApiUrlQueryStringSync.split(url);
		if (parts.queryParams.isEmpty()) {
			return url;
		}
		Map<String, Integer> structuredCountByName = new HashMap<>();
		for (String name : queryParamNames) {
			structuredCountByName.merge(name, 1, Integer::sum);
		}
		Map<String, Integer> seenCountByName = new HashMap<>();
		List<ApiUrlQueryStringSync.RawQueryParam> uncovered = new ArrayList<>();
		for (ApiUrlQueryStringSync.RawQueryParam param : parts.queryParams) {
			int occurrenceIndex = seenCountByName.merge(param.name, 1, Integer::sum) - 1;
			if (occurrenceIndex >= structuredCountByName.getOrDefault(param.name, 0)) {
				uncovered.add(param);
			}
		}
		String fragmentSuffix = parts.fragment == null || parts.fragment.isEmpty() ? "" : "#" + parts.fragment;
		if (uncovered.isEmpty()) {
			return parts.basePath + fragmentSuffix;
		}
		StringBuilder result = new StringBuilder(parts.basePath).append('?');
		for (int i = 0; i < uncovered.size(); i++) {
			if (i > 0) {
				result.append('&');
			}
			ApiUrlQueryStringSync.RawQueryParam param = uncovered.get(i);
			result.append(param.name);
			if (param.value != null) {
				result.append('=').append(param.value);
			}
		}
		return result.append(fragmentSuffix).toString();
	}

	// ------------------------------------------------------------------------------------------
	// Shared: request defaults (collection/folder-level headers/variables/auth), auth, variables
	// ------------------------------------------------------------------------------------------

	private void importRequestDefaults(Map<String, Object> requestDefaults, ApiOwnerType ownerType, String ownerId, BrunoImportResult result) throws BroadSQLException {
		if (requestDefaults == null) {
			return;
		}
		List<ApiAttribute> headerAttrs = new ArrayList<>();
		for (Object headerObj : asList(requestDefaults.get("headers"))) {
			headerAttrs.add(mapHeaderOrParam(ownerType, ownerId, ApiAttributeKind.HEADER, asMap(headerObj)));
		}
		vault.replaceAttributes(ownerType, ownerId, ApiAttributeKind.HEADER, headerAttrs);

		List<ApiAttribute> variableAttrs = new ArrayList<>();
		for (Object varObj : asList(requestDefaults.get("variables"))) {
			variableAttrs.add(mapVariable(ownerType, ownerId, asMap(varObj)));
		}
		vault.replaceAttributes(ownerType, ownerId, ApiAttributeKind.VARIABLE, variableAttrs);

		importAuth(requestDefaults.get("auth"), ownerType, ownerId, result);
	}

	private void importAuth(Object authNode, ApiOwnerType ownerType, String ownerId, BrunoImportResult result) throws BroadSQLException {
		BrunoAuthMapper.MappedAuth mapped = BrunoAuthMapper.map(authNode);
		if (mapped == null) {
			// Absent, or explicit "inherit" - no row written at this level; resolution falls through to a
			// less specific owner (sub-sprint 3/4 concern).
			return;
		}
		vault.saveAuth(new ApiAuthConfig(ownerType, ownerId, mapped.authType));
		ApiAuthConfig saved = vault.getAuth(ownerType, ownerId);
		String authOwnerId = String.valueOf(saved.getId());
		List<ApiAttribute> properties = new ArrayList<>();
		for (ApiAttribute property : mapped.properties) {
			properties.add(new ApiAttribute(ApiOwnerType.AUTH, authOwnerId, ApiAttributeKind.PROPERTY, property.getName(), property.getValue(), property.isSecret()));
		}
		vault.replaceAttributes(ApiOwnerType.AUTH, authOwnerId, ApiAttributeKind.PROPERTY, properties);
		if (mapped.unsupportedSourceType != null) {
			result.recordUnsupportedAuth(mapped.unsupportedSourceType);
		}
	}

	/**
	 * Maps a header or query/path parameter node - {@code {name, value, secret, disabled}}, the same shape
	 * {@link #mapVariable} reads and {@link BrunoCollectionExporter}'s {@code attributeNode}/
	 * {@code paramNode} write. Reads {@code secret} exactly like {@link #mapVariable} already did - this
	 * used to be hardcoded to {@code false} regardless of the node's actual {@code secret} field, so a
	 * secret-flagged header or query/path parameter silently lost its secret status on import: it would
	 * still round-trip as a plain, non-secret attribute, meaning a later "export without secrets" would
	 * include its value in the clear (SPRINT XT02 verification finding 6 - a real credential-leak bug, not
	 * limited to headers alone).
	 */
	private ApiAttribute mapHeaderOrParam(ApiOwnerType ownerType, String ownerId, ApiAttributeKind kind, Map<String, Object> node) {
		if (node == null) {
			return new ApiAttribute(ownerType, ownerId, kind, null, null, false);
		}
		boolean secret = asBoolean(node.get("secret"), false);
		ApiAttribute attr = new ApiAttribute(ownerType, ownerId, kind, asString(node.get("name")), normalize(asString(node.get("value"))), secret);
		attr.setEnabled(!asBoolean(node.get("disabled"), false));
		return attr;
	}

	private ApiAttribute mapVariable(ApiOwnerType ownerType, String ownerId, Map<String, Object> node) {
		if (node == null) {
			return new ApiAttribute(ownerType, ownerId, ApiAttributeKind.VARIABLE, null, null, false);
		}
		boolean secret = asBoolean(node.get("secret"), false);
		String value = extractVariableValue(node.get("value"));
		ApiAttribute attr = new ApiAttribute(ownerType, ownerId, ApiAttributeKind.VARIABLE, asString(node.get("name")), normalize(value), secret);
		attr.setEnabled(!asBoolean(node.get("disabled"), false));
		return attr;
	}

	/**
	 * A {@code Variable}'s {@code value} is usually a plain scalar, but the schema also allows an array
	 * of {@code VariableValueVariant} ({@code {title, selected, value}}, Bruno's "multiple saved drafts,
	 * one active" UI concept - the same shape {@code HttpRequestBodyVariant} uses for bodies, see
	 * {@link #selectBody}). Picks the selected variant's value, or the first one if none is marked
	 * selected; a plain scalar is returned as-is.
	 */
	private String extractVariableValue(Object rawValue) {
		if (rawValue instanceof List<?> variants) {
			String firstValue = null;
			for (Object variantObj : variants) {
				Map<String, Object> variant = asMap(variantObj);
				if (variant == null) {
					continue;
				}
				String value = asString(variant.get("value"));
				if (firstValue == null) {
					firstValue = value;
				}
				if (asBoolean(variant.get("selected"), false)) {
					return value;
				}
			}
			return firstValue;
		}
		return asString(rawValue);
	}

	/**
	 * {@code HttpRequestDetails.body} is either a single {@code HttpRequestBody} or an array of
	 * {@code HttpRequestBodyVariant} ({@code {title, selected, body}}) - the selected variant's body is
	 * returned, or the first one if none is marked selected.
	 */
	private Map<String, Object> selectBody(Object bodyNode) {
		if (bodyNode == null) {
			return null;
		}
		if (bodyNode instanceof List<?> variants) {
			Map<String, Object> firstVariantBody = null;
			for (Object variantObj : variants) {
				Map<String, Object> variant = asMap(variantObj);
				if (variant == null) {
					continue;
				}
				Map<String, Object> variantBody = asMap(variant.get("body"));
				if (firstVariantBody == null) {
					firstVariantBody = variantBody;
				}
				if (asBoolean(variant.get("selected"), false)) {
					return variantBody;
				}
			}
			return firstVariantBody;
		}
		return asMap(bodyNode);
	}

	private static final List<String> RAW_BODY_TYPES = List.of("json", "text", "xml", "sparql");

	/**
	 * Persists {@code body} losslessly (sprint doc section 18.3 - a corrective requirement: the original
	 * sub-sprint 2 draft flattened form/multipart bodies to a {@code name=value} text join, which drops
	 * {@code description}/{@code disabled}/{@code contentType}/multi-value-array information a future
	 * write-execution release would need). {@code RawBody} ({@code json}/{@code text}/{@code xml}/
	 * {@code sparql}) is a plain string already - imported verbatim into {@code bodyContent}, unchanged
	 * from before. Every structured body type ({@code form-urlencoded}, {@code multipart-form}, {@code
	 * file}, and anything not recognized) instead gets its *entire* body subtree - field order, names,
	 * values (including a multi-value array for a repeated multipart field name), {@code description},
	 * {@code disabled}, {@code contentType} - canonically serialized to JSON via Gson (already a project
	 * dependency, sub-sprint 1) after a recursive placeholder-normalization pass. This guarantees no
	 * information loss for any current or future structured body shape without inventing a second,
	 * fragile name-based attribute-encoding convention.
	 */
	private void applyBody(ApiEndpoint endpoint, Map<String, Object> body) {
		String type = asString(body.get("type"));
		endpoint.setBodyMode(type);
		if (RAW_BODY_TYPES.contains(type)) {
			endpoint.setBodyContent(normalize(asString(body.get("data"))));
		} else {
			endpoint.setBodyContent(new Gson().toJson(normalizeDeep(body)));
		}
	}

	/** Recursively applies {@link BrunoPlaceholderNormalizer#normalize} to every string leaf in a generic YAML {@code Map}/{@code List} tree. */
	private Object normalizeDeep(Object node) {
		if (node instanceof String) {
			return normalize((String) node);
		}
		if (node instanceof Map<?, ?> map) {
			Map<String, Object> result = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				result.put(String.valueOf(entry.getKey()), normalizeDeep(entry.getValue()));
			}
			return result;
		}
		if (node instanceof List<?> list) {
			List<Object> result = new ArrayList<>();
			for (Object item : list) {
				result.add(normalizeDeep(item));
			}
			return result;
		}
		return node;
	}

	/**
	 * A folder's source key - see the class Javadoc's "Identity, corrected" note. {@code name} is the
	 * only structural handle a folder has (it carries no request-shaped data like a method/path to key
	 * on instead), so a folder rename is an accepted, documented limitation (sprint doc 17.7/18.4): it is
	 * indistinguishable from replacing the folder, exactly like any other case with no surviving
	 * identity signal at all.
	 */
	private String folderSourceKey(String parentPath, String name) {
		String leaf = "folder:" + name;
		return parentPath.isEmpty() ? leaf : parentPath + "/" + leaf;
	}

	/**
	 * An endpoint's source key - {@code seq} and {@code name} are deliberately excluded (see the class
	 * Javadoc's "Identity, corrected" note): {@code method}+{@code path} is the structural information
	 * that actually identifies "the same request" across a re-import, since a rename or a reorder changes
	 * neither. When a source item's method/path no longer matches anything previously imported under the
	 * same parent - the only case this key cannot resolve - {@link #importHttpRequest} correctly inserts
	 * a new endpoint rather than guessing which existing one it might be.
	 */
	private String endpointSourceKey(String parentPath, String method, String path) {
		String leaf = "endpoint:" + (method == null ? "" : method.toUpperCase()) + " " + (path == null ? "" : path.trim());
		return parentPath.isEmpty() ? leaf : parentPath + "/" + leaf;
	}
}
