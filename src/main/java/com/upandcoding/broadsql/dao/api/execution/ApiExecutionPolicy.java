package com.upandcoding.broadsql.dao.api.execution;

import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * The API execution policy - docs/SPRINT XT02 - Universal API Client.md, section 13.5/21.1, completed by
 * SPRINT XT02-8: every HTTP verb is imported and persisted (sub-sprint 2), but only a fixed allowlist may
 * actually be *executed*. Release 1 (sub-sprint 4) allowed only {@code GET}/{@code HEAD}; XT02-8 adds
 * {@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE} ("write-verb execution", the scope XT02 always
 * named for this final sprint). {@code OPTIONS} remains deliberately unsupported - nothing in the XT02
 * specification history ever names it for execution, so leaving it refused preserves the intended scope
 * rather than expanding it.
 *
 * <p>This is a policy check sitting in front of user-endpoint execution only - it is deliberately not
 * enforced in {@link com.upandcoding.broadsql.dao.api.http.ApiHttpTransport}, which has always stayed
 * method-agnostic (OAuth2's token exchange already needs {@code POST} internally). Widening the allowlist
 * here, exactly as this class's own history predicted, required no transport change at all.
 */
public final class ApiExecutionPolicy {

	private static final List<String> EXECUTABLE_METHODS = List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");

	private ApiExecutionPolicy() {
	}

	/** @return {@code true} if {@code method} is in the executable allowlist (case-insensitive) */
	public static boolean isExecutable(String method) {
		return method != null && EXECUTABLE_METHODS.stream().anyMatch(method::equalsIgnoreCase);
	}

	/** @throws BroadSQLException if {@code method} is not in the executable allowlist (case-insensitive) */
	public static void checkExecutable(String method) throws BroadSQLException {
		if (!isExecutable(method)) {
			throw new BroadSQLException("Execution refused.\n\n"
					+ "Method " + method + " is not enabled for execution in this release.");
		}
	}
}
