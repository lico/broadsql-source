package com.upandcoding.broadsql.controller.shell.completion;

import java.util.List;

import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;

/**
 * One source of completion candidates - SPRINT 0917-02, section 19. A provider decides for itself
 * whether it has anything relevant to offer at the given {@link CompletionContext} (returning an
 * empty list when not applicable, never throwing); it never needs to know about JLine, about other
 * providers, or about how candidates are rendered - see {@link CompletionEngine}.
 */
public interface CompletionProvider {

	/** Never throws - a provider that cannot complete (bad state, metadata failure, ...) simply returns an empty list (section 27). */
	List<CompletionCandidate> complete(CompletionContext context);
}
