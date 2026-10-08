package com.upandcoding.broadsql.dao.metadata;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * SPRINT 2409K: a metadata lookup that could not produce a result, with the reason kept distinct so a
 * command never presents it as an apparently valid empty result. "The table exists but has no foreign
 * keys" is not an exception: it is an empty list from {@link MetadataService}.
 */
public class MetadataException extends BroadSQLException {

	private static final long serialVersionUID = 1L;

	public enum Kind {
		/** The typed name is not a usable table name (blank, or an empty schema or table part such as {@code SCHEMA.}). */
		INVALID_NAME,
		/** No table or view matches the name. */
		NOT_FOUND,
		/** The name matches tables in more than one schema/catalog. */
		AMBIGUOUS,
		/** The JDBC driver does not implement the requested metadata call. */
		UNSUPPORTED,
		/** The driver implements the call but it failed. */
		RETRIEVAL_FAILED
	}

	private final Kind kind;

	public MetadataException(Kind kind, String message) {
		super(message);
		this.kind = kind;
	}

	public Kind getKind() {
		return kind;
	}
}
