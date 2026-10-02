package com.upandcoding.broadsql.controller.shell.reader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.completion.EntityCompletionService;

/**
 * Endpoint-aware completion candidate generation - SPRINT XT02A (URL-Native API Execution), section
 * 13. Deliberately free of any JLine type (operates on plain {@link String} tokens, exactly as a
 * command line's own whitespace-delimited word boundaries already work - see
 * {@code CommandUtils.getArgumentsFromQuery}), so it is fully unit-testable without a real terminal
 * (section 13's "Design the completion services so they can be unit-tested independently of a real
 * terminal" - the JLine-coupled adapter, {@code BroadSqlJLineCompleter}, is the only class that knows
 * about JLine at all, and is itself a thin translation layer with no completion logic of its own).
 *
 * <p>Implements:
 * <pre>
 * CONNECT API &lt;TAB&gt;                        -&gt; configured API ids
 * RUN &lt;TAB&gt;                                 -&gt; HTTP methods + endpoint alias/name/id (each expands to its canonical URL - SPRINT XT02B: most imported endpoints have no alias at all, only a name and numeric id)
 * RUN CUST&lt;TAB&gt;                             -&gt; expands to the canonical URL, e.g. /api/customer/:id
 * RUN userByUserName&lt;TAB&gt;                   -&gt; expands the endpoint's own name the same way, e.g. /api/users/:userName
 * RUN /api/cu&lt;TAB&gt;                          -&gt; matching endpoint canonical paths
 * RUN /api/customer/:id?&lt;TAB&gt;               -&gt; configured query parameter names
 * RUN /api/customer/:id?expand=&lt;TAB&gt;        -&gt; expand's configured allowed values
 * SYNTAX &lt;TAB&gt;, HELP &lt;TAB&gt;                 -&gt; endpoint aliases (never expanded - an alias stays a
 *                                              discovery handle for these two, section 2.3); HELP also
 *                                              offers command keywords
 * ${ENV:CUS&lt;TAB&gt;                            -&gt; matching environment variable NAMES only, never values
 *                                              (section 13.11/22.1) - recognized inside any word, in
 *                                              any command
 * </pre>
 * An alias candidate for {@code RUN} always expands to the canonical URL (section 2.3/13.3: "Do not
 * leave RUN CUST as the executable command" - alias is a discovery shortcut, never a second execution
 * grammar), while for {@code SYNTAX}/{@code HELP} the alias itself is the correct, final argument, so
 * it is never expanded there.
 */
public final class CompletionService {

	private static final List<String> HTTP_METHODS = List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
	private static final Set<String> CONNECT_KEYWORDS = Set.of("CONNECT", "OPEN", "CONN", "CON");
	private static final Pattern ENV_VAR_TRAILING = Pattern.compile("^(.*)\\$\\{ENV:([A-Za-z0-9_]*)$");
	private static final Pattern TRAILING_QUERY_VALUE = Pattern.compile("^(.*[?&])([A-Za-z0-9_]+)=([^&]*)$");
	private static final Pattern TRAILING_QUERY_KEY = Pattern.compile("^(.*[?&])([A-Za-z0-9_]*)$");

	private final Supplier<Set<String>> environmentVariableNamesSupplier;

	public CompletionService() {
		this(() -> System.getenv().keySet());
	}

	/** Test seam - avoids depending on the real process environment's actual variable names. */
	public CompletionService(Supplier<Set<String>> environmentVariableNamesSupplier) {
		this.environmentVariableNamesSupplier = environmentVariableNamesSupplier;
	}

	/**
	 * @param words           every whitespace-delimited token of the line, including the (possibly
	 *                        empty/partial) word currently being completed at {@code wordIndex} - the
	 *                        same shape JLine's own {@code ParsedLine.words()} already provides
	 * @param wordIndex       index into {@code words} of the word being completed
	 * @param apiId           the active {@code CONNECT API} session's API id, or {@code null} if none
	 * @param catalog         local endpoint/API metadata access
	 * @param commandKeywords every registered command's primary keyword (for {@code HELP <TAB>}), or {@code null}/empty to skip that
	 */
	public List<CompletionCandidate> complete(List<String> words, int wordIndex, String apiId, ApiCatalogService catalog, List<String> commandKeywords) {
		if (words == null || words.isEmpty() || wordIndex < 0 || wordIndex >= words.size()) {
			return List.of();
		}
		String currentWord = words.get(wordIndex);

		Matcher envMatch = ENV_VAR_TRAILING.matcher(currentWord);
		if (envMatch.matches()) {
			return envVarCandidates(envMatch.group(1), envMatch.group(2));
		}

		String command = words.get(0).toUpperCase(Locale.ROOT);
		try {
			if (CONNECT_KEYWORDS.contains(command)) {
				return connectCandidates(words, wordIndex, currentWord, catalog);
			}
			if ("RUN".equals(command)) {
				return runCandidates(words, wordIndex, currentWord, apiId, catalog);
			}
			if ("SYNTAX".equals(command)) {
				return wordIndex == 1 ? aliasCandidates(currentWord, apiId, catalog, false) : List.of();
			}
			if ("HELP".equals(command)) {
				return wordIndex == 1 ? helpCandidates(currentWord, apiId, catalog, commandKeywords) : List.of();
			}
		} catch (BroadSQLException e) {
			return List.of(); // completion never surfaces an error - just no candidates
		}
		return List.of();
	}

	private List<CompletionCandidate> connectCandidates(List<String> words, int wordIndex, String currentWord, ApiCatalogService catalog) {
		if (wordIndex != 2 || words.size() < 2 || !"API".equalsIgnoreCase(words.get(1))) {
			return List.of();
		}
		List<CompletionCandidate> result = new ArrayList<>();
		for (ApiCatalogService.ApiSummary api : catalog.listApis()) {
			if (startsWithIgnoreCase(api.id, currentWord)) {
				result.add(new CompletionCandidate(api.id, api.id, api.description));
			}
		}
		return result;
	}

	private List<CompletionCandidate> runCandidates(List<String> words, int wordIndex, String currentWord, String apiId, ApiCatalogService catalog)
			throws BroadSQLException {
		if (apiId == null) {
			return List.of();
		}
		// SPRINT 2009A: a word still under the cursor is a prefix, not yet a given method - so RUN get<TAB>
		// offers the GET method and the endpoints whose name/alias starts with "get", instead of nothing.
		boolean methodGiven = wordIndex > 1 && isHttpMethod(words.get(1));
		int urlWordIndex = methodGiven ? 2 : 1;
		String method = methodGiven ? words.get(1).toUpperCase(Locale.ROOT) : "GET";

		if (wordIndex == urlWordIndex && (currentWord.startsWith("/") || currentWord.contains("?") || methodGiven)) {
			return urlOrQueryCandidates(currentWord, apiId, method, catalog);
		}
		if (wordIndex == 1 && !methodGiven) {
			List<CompletionCandidate> result = new ArrayList<>();
			String upper = currentWord.toUpperCase(Locale.ROOT);
			for (String httpMethod : HTTP_METHODS) {
				if (httpMethod.startsWith(upper)) {
					result.add(new CompletionCandidate(httpMethod, httpMethod, "HTTP method"));
				}
			}
			result.addAll(endpointReferenceCandidates(currentWord, apiId, catalog));
			return result;
		}
		return List.of();
	}

	private List<CompletionCandidate> urlOrQueryCandidates(String currentWord, String apiId, String method, ApiCatalogService catalog) throws BroadSQLException {
		int q = currentWord.indexOf('?');
		if (q < 0) {
			return pathCandidates(currentWord, apiId, catalog);
		}
		String pathPart = currentWord.substring(0, q);

		Matcher valueMatch = TRAILING_QUERY_VALUE.matcher(currentWord);
		if (valueMatch.matches()) {
			String prefix = valueMatch.group(1);
			String paramName = valueMatch.group(2);
			String typedValuePrefix = valueMatch.group(3);
			List<CompletionCandidate> result = new ArrayList<>();
			for (String value : catalog.allowedValues(apiId, method, pathPart, paramName)) {
				if (value.startsWith(typedValuePrefix)) {
					result.add(new CompletionCandidate(prefix + paramName + "=" + value, value, null));
				}
			}
			return result;
		}

		Matcher keyMatch = TRAILING_QUERY_KEY.matcher(currentWord);
		if (keyMatch.matches()) {
			String prefix = keyMatch.group(1);
			String typedKeyPrefix = keyMatch.group(2);
			Set<String> alreadyUsed = alreadyUsedQueryParamNames(currentWord, q);
			List<CompletionCandidate> result = new ArrayList<>();
			for (String name : catalog.queryParameterNames(apiId, method, pathPart)) {
				if (name.startsWith(typedKeyPrefix) && !alreadyUsed.contains(name.toLowerCase(Locale.ROOT))) {
					result.add(new CompletionCandidate(prefix + name + "=", name, null));
				}
			}
			return result;
		}

		return List.of();
	}

	private Set<String> alreadyUsedQueryParamNames(String currentWord, int questionMarkIndex) {
		Set<String> used = new HashSet<>();
		String queryPart = currentWord.substring(questionMarkIndex + 1);
		String[] pairs = queryPart.split("&", -1);
		// The LAST pair is the one currently being typed (the partial key/value to complete) - excluded.
		for (int i = 0; i < pairs.length - 1; i++) {
			int eq = pairs[i].indexOf('=');
			String name = eq >= 0 ? pairs[i].substring(0, eq) : pairs[i];
			if (!name.isEmpty()) {
				used.add(name.toLowerCase(Locale.ROOT));
			}
		}
		return used;
	}

	private List<CompletionCandidate> pathCandidates(String currentWord, String apiId, ApiCatalogService catalog) throws BroadSQLException {
		List<CompletionCandidate> result = new ArrayList<>();
		for (ApiCatalogService.EndpointSummary ep : catalog.listEndpoints(apiId)) {
			if (ep.canonicalPath.startsWith(currentWord)) {
				String description = ep.method + (isNotBlank(ep.alias) ? " (" + ep.alias + ")" : "");
				result.add(new CompletionCandidate(ep.canonicalPath, ep.canonicalPath, description));
			}
		}
		return result;
	}

	private List<CompletionCandidate> aliasCandidates(String currentWord, String apiId, ApiCatalogService catalog, boolean expandToUrl) throws BroadSQLException {
		if (apiId == null) {
			return List.of();
		}
		List<CompletionCandidate> result = new ArrayList<>();
		for (ApiCatalogService.EndpointSummary ep : catalog.listEndpoints(apiId)) {
			if (isNotBlank(ep.alias) && startsWithIgnoreCase(ep.alias, currentWord)) {
				String value = expandToUrl ? ep.canonicalPath : ep.alias;
				result.add(new CompletionCandidate(value, ep.alias, ep.method + " " + ep.canonicalPath));
			}
		}
		return result;
	}

	/**
	 * {@code RUN <TAB>}'s own candidate set (SPRINT XT02B acceptance correction): a bare alias is a
	 * discovery shortcut, but most imported endpoints (e.g. from Bruno) never have one set at all - only
	 * an auto-derived {@code name} and a numeric {@code id}. {@code RUN <alias|name|id><TAB>} must expand
	 * to the canonical URL whichever of the three the user typed, matching the same id/alias/name scope
	 * {@link com.upandcoding.broadsql.dao.api.ApiEndpointReferenceResolver} already resolves for {@code SHOW
	 * ENDPOINT}/{@code SYNTAX}/{@code HELP} - RUN itself still never executes a bare reference directly
	 * (unchanged: the URL-only execution model is not reversed), only its tab completion is broadened.
	 * One candidate per endpoint even when more than one field happens to match the typed prefix.
	 */
	private List<CompletionCandidate> endpointReferenceCandidates(String currentWord, String apiId, ApiCatalogService catalog) throws BroadSQLException {
		if (apiId == null) {
			return List.of();
		}
		List<CompletionCandidate> result = new ArrayList<>();
		for (ApiCatalogService.EndpointSummary ep : catalog.listEndpoints(apiId)) {
			boolean matchesAlias = isNotBlank(ep.alias) && startsWithIgnoreCase(ep.alias, currentWord);
			boolean matchesName = isNotBlank(ep.name) && startsWithIgnoreCase(ep.name, currentWord);
			boolean matchesId = isNotBlank(ep.id) && ep.id.startsWith(currentWord);
			if (!matchesAlias && !matchesName && !matchesId) {
				continue;
			}
			String display = isNotBlank(ep.alias) ? ep.alias : (isNotBlank(ep.name) ? ep.name : ep.id);
			result.add(new CompletionCandidate(ep.canonicalPath, display, ep.method + " " + ep.canonicalPath));
		}
		return result;
	}

	private List<CompletionCandidate> helpCandidates(String currentWord, String apiId, ApiCatalogService catalog, List<String> commandKeywords)
			throws BroadSQLException {
		List<CompletionCandidate> result = new ArrayList<>();
		if (commandKeywords != null) {
			for (String keyword : commandKeywords) {
				if (startsWithIgnoreCase(keyword, currentWord)) {
					result.add(new CompletionCandidate(keyword, keyword, "command"));
				}
			}
		}
		if (apiId != null) {
			result.addAll(aliasCandidates(currentWord, apiId, catalog, false));
		}
		return result;
	}

	/** Section 13.11/22.1: candidate NAMES only, never values - {@code prefixBeforeEnv} already includes everything up to and including {@code ${ENV:}. */
	private List<CompletionCandidate> envVarCandidates(String prefixBeforeEnv, String typedNamePrefix) {
		List<CompletionCandidate> result = new ArrayList<>();
		for (String name : new TreeSet<>(environmentVariableNamesSupplier.get())) {
			if (name.startsWith(typedNamePrefix)) {
				result.add(new CompletionCandidate(prefixBeforeEnv + "${ENV:" + name + "}", name, null));
			}
		}
		return result;
	}

	private boolean isHttpMethod(String token) {
		for (String method : HTTP_METHODS) {
			if (method.equalsIgnoreCase(token)) {
				return true;
			}
		}
		return false;
	}

	private boolean startsWithIgnoreCase(String value, String prefix) {
		return EntityCompletionService.matches(value, prefix); // SPRINT 2009A: one shared prefix policy
	}

	private boolean isNotBlank(String value) {
		return value != null && !value.isBlank();
	}
}
