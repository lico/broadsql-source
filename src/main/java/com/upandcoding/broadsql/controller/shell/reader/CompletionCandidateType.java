package com.upandcoding.broadsql.controller.shell.reader;

/**
 * Optional semantic classification for a {@link CompletionCandidate} - SPRINT 0917-02 (Intelligent TAB
 * Auto-Completion), section 21. Kept alongside {@link CompletionCandidate} itself, not in the newer
 * {@code shell.completion} package, so the one existing {@link CompletionCandidate} type can be shared
 * unchanged by both the SPRINT XT02A API-completion stack ({@code CompletionService}, which never sets
 * a type - see {@link CompletionCandidate}'s 3-arg constructor) and the new BroadSQL/SQL/JDBC-metadata
 * completion engine, rather than introducing a second, parallel candidate type that would need
 * translating back and forth at the one place both stacks meet ({@code BroadSqlJLineCompleter}).
 */
public enum CompletionCandidateType {
	BROADSQL_COMMAND,
	BROADSQL_KEYWORD,
	SQL_KEYWORD,
	SCHEMA,
	TABLE,
	VIEW,
	COLUMN,
	/** SPRINT XT02A candidates (API ids, endpoints, HTTP methods, query params, env vars, ...) - never reclassified, just named so {@link CompletionCandidate#getType()} is never null by accident going forward. */
	API,
	/** SPRINT 2009A: a stored BroadSQL entity (connection, environment, database group, script) used as a command argument. */
	ENTITY,
	/** SPRINT 2409K: a file in a filesystem-path argument position; completion ends the word. */
	FILE,
	/** SPRINT 2409K: a directory in a filesystem-path argument position, ending with a separator; the word stays open for the next TAB. */
	DIRECTORY
}
