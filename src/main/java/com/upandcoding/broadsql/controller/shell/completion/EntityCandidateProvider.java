package com.upandcoding.broadsql.controller.shell.completion;

import java.util.Collection;

/**
 * Supplies every current name of one {@link CompletionEntityType}, read from that entity's normal
 * authoritative source at call time (never cached - SPRINT 2009A section 8). Read-only. Prefix filtering
 * and case handling are not the provider's job; {@link EntityCompletionService} applies them once.
 */
@FunctionalInterface
public interface EntityCandidateProvider {

	/** May throw: {@link EntityCompletionService} turns any failure into "no candidates". */
	Collection<String> names() throws Exception;
}
