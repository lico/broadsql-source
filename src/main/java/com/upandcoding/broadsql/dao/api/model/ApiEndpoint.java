package com.upandcoding.broadsql.dao.api.model;

import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * One row of {@code API_ENDPOINT} - a single request definition (Bruno's "request"). See
 * docs/SPRINT XT02 - Universal API Client.md, section 6/7/13.1.
 *
 * <p>{@code method} is stored for every HTTP verb (GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS/...) - Release
 * 1 imports and persists all of them; only the separate execution-policy layer (section 13.5, sub-sprint
 * 4) restricts which ones may actually run. {@code endpointPath} is relative to the selected
 * {@link ApiEnvironment}'s {@code baseUrl} and may contain {@code ${variable}} placeholders resolved at
 * request-build time.
 *
 * <p>{@code bodyMode}/{@code bodyContent} hold the request body as imported - Release 1 does not attempt
 * to interpret every possible Bruno body mode; an unsupported mode is still stored verbatim (never
 * dropped, per the sprint doc's "preserve request bodies" requirement) with {@code bodyMode} naming what
 * it was.
 *
 * <p>{@code sourceType}/{@code sourceKey} are the import-provenance/idempotent-reimport identity (section
 * 9/13.1) - both {@code null} for a manually-created endpoint.
 *
 * <p>{@code alias} is separate, BroadSQL-owned operational metadata - docs/Amendment - Endpoint Aliases
 * and Future Scriptability.md. It is never part of the import identity and never derived from
 * {@code name}/{@code method}/{@code endpointPath}. <b>Do not "helpfully" add a
 * {@code setAlias(...)} call to {@link com.upandcoding.broadsql.dao.api.bruno.BrunoCollectionImporter}</b> -
 * the alias must survive re-import untouched (amendment section 3/10), and it already does, for free,
 * precisely because the importer never touches this field: it only mutates the {@code existing} endpoint's
 * imported fields one by one, and {@code alias} is not one of them, so whatever value
 * {@code ApiDefinitionsVault.mapEndpoint(ResultSet)} loaded it with (or {@code null}, for a brand-new
 * endpoint) simply carries through untouched.
 */
public class ApiEndpoint {

	private Integer id;
	private Integer apiVersionId;
	private Integer groupId;
	private String name;
	private String method;
	private String endpointPath;
	private String bodyMode;
	private String bodyContent;
	private int sortOrder;
	private String sourceType;
	private String sourceKey;
	private String alias;
	private String statusId = DatabaseDefinition.STATUS_ACTIVE;

	public ApiEndpoint() {
	}

	public ApiEndpoint(Integer apiVersionId, Integer groupId, String name, String method, String endpointPath, int sortOrder) {
		this.apiVersionId = apiVersionId;
		this.groupId = groupId;
		this.name = name;
		this.method = method;
		this.endpointPath = endpointPath;
		this.sortOrder = sortOrder;
	}

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public Integer getApiVersionId() {
		return apiVersionId;
	}

	public void setApiVersionId(Integer apiVersionId) {
		this.apiVersionId = apiVersionId;
	}

	public Integer getGroupId() {
		return groupId;
	}

	public void setGroupId(Integer groupId) {
		this.groupId = groupId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	public String getEndpointPath() {
		return endpointPath;
	}

	public void setEndpointPath(String endpointPath) {
		this.endpointPath = endpointPath;
	}

	public String getBodyMode() {
		return bodyMode;
	}

	public void setBodyMode(String bodyMode) {
		this.bodyMode = bodyMode;
	}

	public String getBodyContent() {
		return bodyContent;
	}

	public void setBodyContent(String bodyContent) {
		this.bodyContent = bodyContent;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public void setSortOrder(int sortOrder) {
		this.sortOrder = sortOrder;
	}

	public String getSourceType() {
		return sourceType;
	}

	public void setSourceType(String sourceType) {
		this.sourceType = sourceType;
	}

	public String getSourceKey() {
		return sourceKey;
	}

	public void setSourceKey(String sourceKey) {
		this.sourceKey = sourceKey;
	}

	public String getStatusId() {
		return statusId;
	}

	public void setStatusId(String statusId) {
		this.statusId = statusId;
	}

	/** BroadSQL-owned optional stable name for future {@code CALL}/script usage - see the class Javadoc. */
	public String getAlias() {
		return alias;
	}

	public void setAlias(String alias) {
		this.alias = alias;
	}
}
