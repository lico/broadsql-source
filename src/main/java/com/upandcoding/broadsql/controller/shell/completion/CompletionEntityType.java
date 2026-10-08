package com.upandcoding.broadsql.controller.shell.completion;

/**
 * The kind of stored BroadSQL entity a command argument refers to - SPRINT 2009A (context-aware entity
 * completion). A command declares one per argument position ({@code Command#getCompletionArguments()});
 * {@link EntityCompletionService} then asks the matching {@link EntityCandidateProvider} for candidates.
 *
 * <p>The API grammar of {@code CONNECT API}, {@code RUN}, {@code SYNTAX} and {@code HELP} predates this type
 * and is completed by {@code CompletionService} (SPRINT XT02A), which is tried first by
 * {@code BroadSqlJLineCompleter}. SPRINT 2409K added {@link #API} and {@link #API_ENDPOINT} for the other
 * API commands, which take a plain positional argument.
 */
public enum CompletionEntityType {
	/** An active database connection id (the Connections Definition File's {@code CONNECTIONS} table). */
	CONNECTION,
	/** An active Environment id. */
	ENVIRONMENT,
	/**
	 * An Environment that has an active Connection in the current Connection's Database Group - what
	 * {@code ENV <environment>} can actually switch to (SPRINT 2309T, #159). Empty when there is no
	 * current Connection or it belongs to no Database Group; never Environments of other groups.
	 */
	GROUP_ENVIRONMENT,
	/** An active Database Group id. */
	DATABASE_GROUP,
	/** A Scripts Library script, as its path relative to the library root. */
	SCRIPT,
	/**
	 * A table or view name reachable from the active connection's current schema - GitHub #153. Unlike
	 * the other entity types, its source is the live JDBC connection ({@code DatabaseConnection#getTables}/
	 * {@code #getViews}), not a locally-held vault/library, and is registered by
	 * {@link EntityCompletionService#standard(com.upandcoding.broadsql.controller.shell.commands.CommandList,
	 * com.upandcoding.broadsql.dao.DatabaseConnection)} only when a connection is available; with no
	 * active connection, or none passed in, no provider is registered for this type at all, so
	 * {@link EntityCompletionService#complete} cleanly returns no candidates rather than attempting any
	 * database work.
	 */
	TABLE,
	/** SPRINT 2409K: an inactive (soft-deleted) connection id, what {@code REACTIVATE CONNECTION} accepts. */
	INACTIVE_CONNECTION,
	/** SPRINT 2409K: the original library path of an archived Script, what {@code LIB RESTORE} accepts. */
	ARCHIVED_SCRIPT,
	/** SPRINT 2409K: a configured API id (the API vault). */
	API,
	/**
	 * SPRINT 2409K: an endpoint of the active {@code CONNECT API} session's API, as its alias (or its name
	 * when it has no alias), never expanded to a URL. Empty without an active API session.
	 */
	API_ENDPOINT,
	/**
	 * SPRINT 2409K: a filesystem path, absolute or relative to the working directory (e.g.
	 * {@code IMPORT API BRUNO <file>}). Completed from the filesystem by {@link FilesystemPathCompletion},
	 * not by a registered provider.
	 */
	FILE_PATH,
	/**
	 * SPRINT 2409K: a file argument where a bare name means a file of the export folder ({@code DefaultFolder})
	 * and a name with a directory part is a filesystem path ({@code EXPORT <file>}, {@code LOAD <table> <file>}).
	 */
	EXPORT_FOLDER_FILE
}
