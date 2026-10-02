package com.upandcoding.broadsql.dao.api.model;

/**
 * Discriminates which table an {@code API_ATTRIBUTE} or {@code API_AUTH} row belongs to - see
 * docs/SPRINT XT02 - Universal API Client.md, section 13.1. Persisted as {@link #name()} in the
 * {@code OWNER_TYPE} column; {@code OWNER_ID} is the referenced row's ID as text (a plain {@code VARCHAR}
 * {@code API.ID} for {@link #API}, the numeric auto-generated ID as text for every other owner).
 */
public enum ApiOwnerType {
	API, ENVIRONMENT, GROUP, ENDPOINT, AUTH
}
