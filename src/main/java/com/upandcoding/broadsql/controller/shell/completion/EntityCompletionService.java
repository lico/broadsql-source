package com.upandcoding.broadsql.controller.shell.completion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.reader.ApiCatalogService;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidateType;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

/**
 * Context-aware completion of stored BroadSQL entities used as command arguments - SPRINT 2009A. JLine
 * free (plain word tokens, like {@code CompletionService}), so it is unit-testable without a terminal.
 *
 * <p>The command being typed is found through the live {@link CommandList} (so every alias, e.g.
 * {@code CONN}, {@code LI ED}, resolves exactly as at execution), and the argument position is the number
 * of words after that command's own keyword. The entity type of that position comes from the command
 * itself ({@code Command#getCompletionArguments()}); no grammar is kept here. The single prefix
 * matcher ({@link #matches}) and the single case policy (case-insensitive match, canonical stored value
 * inserted) apply to every entity type.
 *
 * <p>{@code @<script>} is the one keyword glued to its argument (see
 * {@code CommandList#getCommandClassFromName}); it is recognized as a first word starting with {@code @}.
 *
 * <p>Never throws: a failing source yields no candidates and a debug log line.
 */
public final class EntityCompletionService {

	private static final Logger log = LoggerFactory.getLogger(EntityCompletionService.class);
	private static final String SCRIPT_KEYWORD = "@";

	private final CommandList commandList;
	private final Map<CompletionEntityType, EntityCandidateProvider> providers = new EnumMap<>(CompletionEntityType.class);
	/** SPRINT 2409K: filesystem path positions; {@code null} means no path completion. */
	private FilesystemPathCompletion paths;
	/** The tables and views of one schema, for a {@code SCHEMA.} prefix at a table position; {@code null} means unqualified names only. */
	private SchemaTables schemaTables;

	/** The table and view names of the schema a user typed before the dot. */
	@FunctionalInterface
	public interface SchemaTables {
		Collection<String> names(String schema) throws Exception;
	}

	public EntityCompletionService(CommandList commandList) {
		this.commandList = commandList;
	}

	/** SPRINT 2409K: enables completion of the filesystem path positions ({@code FILE_PATH}, {@code EXPORT_FOLDER_FILE}, {@code @./...}, {@code <@...>}). */
	public EntityCompletionService withPaths(FilesystemPathCompletion paths) {
		this.paths = paths;
		return this;
	}

	/**
	 * The production wiring: every source is the object the commands themselves use, resolved from the
	 * {@link CommandList} at each TAB (never captured), so a vault or setting replaced later is still seen.
	 * Connections, groups and environments are the vault's in-memory active sets (what {@code CONNECT}
	 * validates against, reloaded by the vault on every save); scripts are listed from the library folder.
	 *
	 * <p>No {@link CompletionEntityType#TABLE} provider is registered by this overload - equivalent to
	 * {@link #standard(CommandList, DatabaseConnection)} with a {@code null} connection. Existing callers
	 * that never had table completion (and every test predating GitHub #153) keep exactly their previous
	 * behavior.
	 */
	public static EntityCompletionService standard(CommandList commandList) {
		return standard(commandList, null);
	}

	/**
	 * Same as {@link #standard(CommandList)}, additionally registering
	 * {@link CompletionEntityType#TABLE} (GitHub #153: {@code DESCR}/{@code DESC}/{@code DESCRIBE} and
	 * {@code DUMP} declare their one argument as {@link CompletionEntityType#TABLE} via
	 * {@code Command#getCompletionArguments()} instead of either command running its own metadata query)
	 * when {@code sqlDatabase} is non-null - the same live {@link DatabaseConnection#getTables}/
	 * {@link DatabaseConnection#getViews} accessors {@code JdbcMetadataCompletionProvider} already uses
	 * for {@code FROM}/{@code JOIN} table-position completion, so a table's visibility rules (active
	 * connection, current schema) are identical either way. Deliberately not schema-qualified and not
	 * cached (unlike {@code JdbcMetadataCompletionProvider}, which goes through
	 * {@code JdbcMetadataCompletionCache}): {@link EntityCandidateProvider}'s contract is "read live, never
	 * cache" (SPRINT 2009A section 8), and a wrong/stale table list offered to {@code DESC}/{@code DUMP}
	 * is a worse failure mode than one extra JDBC metadata call per TAB press.
	 *
	 * <p>No active connection (a null {@code sqlDatabase}, or {@code isConnected()} false at TAB time)
	 * yields no candidates rather than a thrown exception - the same "no active database context, stay
	 * clean and non-blocking" rule every other table-completion path in this codebase already follows.
	 */
	public static EntityCompletionService standard(CommandList commandList, DatabaseConnection sqlDatabase) {
		EntityCompletionService service = new EntityCompletionService(commandList)
				.register(CompletionEntityType.CONNECTION, () -> new ArrayList<>(commandList.getDatabaseConnectionsVault().getIds()))
				.register(CompletionEntityType.ENVIRONMENT, () -> new ArrayList<>(commandList.getDatabaseConnectionsVault().getEnvironments()))
				.register(CompletionEntityType.DATABASE_GROUP, () -> new ArrayList<>(commandList.getDatabaseConnectionsVault().getGroups()))
				.register(CompletionEntityType.SCRIPT, () -> ScriptsLibrary.listScriptKeys(commandList.getShellConsoleSettings().getScriptsLibraryPath()))
				.register(CompletionEntityType.GROUP_ENVIRONMENT, () -> currentGroupEnvironments(commandList, sqlDatabase))
				// SPRINT 2409K: the remaining argument positions that name a stored entity
				.register(CompletionEntityType.INACTIVE_CONNECTION, () -> commandList.getDatabaseConnectionsVault().getInactiveIds())
				.register(CompletionEntityType.ARCHIVED_SCRIPT, () -> archivedScriptPaths(commandList))
				.register(CompletionEntityType.API, () -> apiIds(commandList))
				.register(CompletionEntityType.API_ENDPOINT, () -> sessionEndpoints(commandList))
				.withPaths(FilesystemPathCompletion.standard(
						() -> commandList.getShellConsoleSettings() == null ? null : commandList.getShellConsoleSettings().getExtractFolderName()));
		if (sqlDatabase != null) {
			service.register(CompletionEntityType.TABLE, () -> {
				if (!sqlDatabase.isConnected()) {
					return List.of();
				}
				// Tables, then views: DatabaseConnection#getTables() returns real tables only (never a view,
				// synonym, sequence or index) and getViews() the views. The LinkedHashSet keeps the order and
				// drops a name present in both lists (a table and a view of the same name in two schemas).
				Set<String> names = new LinkedHashSet<>();
				for (TableMetadata table : sqlDatabase.getTables(null, null, null)) {
					names.add(table.getName());
				}
				for (TableMetadata view : sqlDatabase.getViews(null, null, null)) {
					names.add(view.getName());
				}
				return names;
			});
			service.schemaTables = schema -> {
				if (!sqlDatabase.isConnected()) {
					return List.of();
				}
				// The schema as typed, then upper case, then lower case: the order DESCR resolves names in
				for (String candidate : new String[] { schema, schema.toUpperCase(Locale.ROOT), schema.toLowerCase(Locale.ROOT) }) {
					Set<String> names = new LinkedHashSet<>();
					for (TableMetadata table : sqlDatabase.getTables(null, candidate, null)) {
						names.add(table.getName());
					}
					for (TableMetadata view : sqlDatabase.getViews(null, candidate, null)) {
						names.add(view.getName());
					}
					if (!names.isEmpty()) {
						return names;
					}
				}
				return List.of();
			};
		}
		return service;
	}

	/**
	 * SPRINT 2309T (#159): the Environments {@code ENV <TAB>} can switch to - those with an active
	 * Connection in the current Connection's Database Group, read live from the vault like every other
	 * source here. No current Connection, or one without a Database Group, yields nothing: Environment
	 * names are local to a group, so there is nothing meaningful to offer.
	 */
	static Collection<String> currentGroupEnvironments(CommandList commandList, DatabaseConnection sqlDatabase) throws BroadSQLException {
		if (sqlDatabase == null || sqlDatabase.getPlatform() == null) {
			return List.of();
		}
		DatabaseDefinitionsVault vault = commandList.getDatabaseConnectionsVault();
		String group = CommandUtils.currentInstance(sqlDatabase.getPlatform().getId(), vault);
		if (group.isEmpty()) {
			return List.of();
		}
		Set<String> environments = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (DatabaseDefinition connection : vault.getConnectionsForGroup(group)) {
			if (connection.getEnvironment() != null) {
				environments.add(connection.getEnvironment());
			}
		}
		return environments;
	}

	/** SPRINT 2409K: each archived Script's original library path once (the newest archive of a path is what {@code LIB RESTORE} restores). */
	static Collection<String> archivedScriptPaths(CommandList commandList) throws BroadSQLException {
		Set<String> paths = new LinkedHashSet<>();
		for (ScriptsLibrary.ArchivedEntry entry : new ScriptsLibrary(commandList.getShellConsoleSettings().getScriptsLibraryPath()).getArchivedEntries()) {
			paths.add(entry.getOriginalRelativePath());
		}
		return paths;
	}

	/** SPRINT 2409K: the configured API ids, from the same vault the API commands read. */
	static Collection<String> apiIds(CommandList commandList) {
		ApiDefinitionsVault vault = commandList.getApiDefinitionsVault();
		if (vault == null) {
			return List.of();
		}
		Set<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (ApiCatalogService.ApiSummary api : new ApiCatalogService(vault).listApis()) {
			ids.add(api.id);
		}
		return ids;
	}

	/**
	 * SPRINT 2409K: endpoints of the active {@code CONNECT API} session's API, by alias, else by name (what
	 * {@code SHOW ENDPOINT} accepts besides the numeric id), through the same {@link ApiCatalogService} as
	 * {@code SYNTAX <TAB>}. Nothing without an active API session.
	 */
	static Collection<String> sessionEndpoints(CommandList commandList) throws BroadSQLException {
		ApiSessionContext session = ApiSessionContextHolder.get();
		ApiDefinitionsVault vault = commandList.getApiDefinitionsVault();
		if (session == null || session.getApiId() == null || vault == null) {
			return List.of();
		}
		Set<String> references = new LinkedHashSet<>();
		for (ApiCatalogService.EndpointSummary endpoint : new ApiCatalogService(vault).listEndpoints(session.getApiId())) {
			String reference = endpoint.alias != null && !endpoint.alias.isBlank() ? endpoint.alias : endpoint.name;
			if (reference != null && !reference.isBlank()) {
				references.add(reference);
			}
		}
		return references;
	}

	public EntityCompletionService register(CompletionEntityType type, EntityCandidateProvider provider) {
		providers.put(type, provider);
		return this;
	}

	/** The one prefix policy: case-insensitive prefix match, an empty prefix matches everything. No fuzzy or substring matching. */
	public static boolean matches(String value, String prefix) {
		return value != null && prefix != null && value.regionMatches(true, 0, prefix, 0, prefix.length());
	}

	/** @param words every whitespace-delimited word of the line, including the one being completed at {@code wordIndex} */
	public List<CompletionCandidate> complete(List<String> words, int wordIndex) {
		try {
			if (commandList == null || words == null || wordIndex < 0 || wordIndex >= words.size()) {
				return List.of();
			}
			String current = words.get(wordIndex);
			if (paths != null && current.startsWith(LIST_SOURCE_OPENER)) {
				return listSourcePathCandidates(current);
			}
			if (wordIndex == 0 && current.startsWith(SCRIPT_KEYWORD) && commandList.containsKey(SCRIPT_KEYWORD)) {
				String reference = current.substring(1);
				if (paths != null && FilesystemPathCompletion.isExplicitFilesystemReference(reference)) {
					// SPRINT 2409K: @./file and @C:\dir\file name a file, not a Scripts Library entry (ScriptResolver's rule)
					return paths.complete(reference, SCRIPT_KEYWORD, true, false);
				}
				return candidates(CompletionEntityType.SCRIPT, reference, SCRIPT_KEYWORD);
			}
			Match match = longestKeywordMatch(words, wordIndex);
			if (match == null) {
				return List.of();
			}
			if (EXPORT_KEYWORDS.contains(match.command.getKeywords()[0].toUpperCase(Locale.ROOT))) {
				return exportCandidates(words, wordIndex, match.keywordWords);
			}
			List<CompletionEntityType> arguments = match.command.getCompletionArguments();
			int argIndex = wordIndex - match.keywordWords;
			if (argIndex >= arguments.size()) {
				return List.of();
			}
			CompletionEntityType type = arguments.get(argIndex);
			if (type == CompletionEntityType.FILE_PATH || type == CompletionEntityType.EXPORT_FOLDER_FILE) {
				return paths == null ? List.of() : paths.complete(current, "", true, type == CompletionEntityType.EXPORT_FOLDER_FILE);
			}
			if (type == CompletionEntityType.TABLE) {
				return tableCandidates(current);
			}
			return candidates(type, current, "");
		} catch (RuntimeException e) {
			log.debug("Entity completion failed, offering nothing: {}", e.toString());
			return List.of();
		}
	}

	private static final String LIST_SOURCE_OPENER = "<@";
	/** The {@code <@...>} list-source prefixes followed by a file path ({@code ListSourceResolver}). */
	private static final List<String> PATH_LIST_SOURCE_PREFIXES = List.of("csv:", "excel:");

	/**
	 * SPRINT 2409K: {@code <@path}, {@code <@csv:path} and {@code <@excel:path} anywhere in a statement name a
	 * file relative to the working directory ({@code FileListSource}, {@code CsvColumnListSource},
	 * {@code ExcelColumnListSource}). The macro has no quoting of its own (everything up to {@code >} is the
	 * token), so candidates are never quoted. {@code <@clipboard} and {@code <@last:...} are not paths.
	 */
	private List<CompletionCandidate> listSourcePathCandidates(String current) {
		String token = current.substring(LIST_SOURCE_OPENER.length());
		String prefix = "";
		for (String sourcePrefix : PATH_LIST_SOURCE_PREFIXES) {
			if (token.regionMatches(true, 0, sourcePrefix, 0, sourcePrefix.length())) {
				prefix = token.substring(0, sourcePrefix.length());
				token = token.substring(sourcePrefix.length());
				break;
			}
		}
		if (prefix.isEmpty() && token.regionMatches(true, 0, "last:", 0, "last:".length())) {
			return List.of();
		}
		return paths.complete(token, LIST_SOURCE_OPENER + prefix, false, false);
	}

	/** SPRINT 2309T: the canonical export command and its compatibility spelling, which share one grammar. */
	private static final Set<String> EXPORT_KEYWORDS = Set.of("DUMP", "PULL");
	/** The formats offered after {@code AS}: canonical names only ({@code TXT} still runs, {@code TEXT} is taught). */
	static final List<String> EXPORT_FORMATS = List.of("CSV", "TEXT", "JSON", "XLSX", "ODS", "H2", "MD", "HTML");

	/**
	 * SPRINT 2309T (#161/#163/#165): {@code DUMP}/{@code PULL} arguments, following the one export grammar
	 * {@code <source> [TO <destination>] [AS <format>]}: the source position offers {@code /}, {@code LIB}
	 * and table names; the word after {@code LIB} offers Scripts Library scripts; after a source, {@code TO}
	 * and {@code AS}; after {@code AS}, the formats. Nothing is offered for a destination name or inside a
	 * parenthesized query (left to SQL completion).
	 */
	private List<CompletionCandidate> exportCandidates(List<String> words, int wordIndex, int keywordWords) {
		String current = words.get(wordIndex);
		List<String> args = words.subList(keywordWords, wordIndex);
		if (args.isEmpty() && current.startsWith(SCRIPT_KEYWORD)) {
			// DUMP @<script> is DUMP LIB <script>: the same Scripts Library names, glued to @
			return candidates(CompletionEntityType.SCRIPT, current.substring(SCRIPT_KEYWORD.length()), SCRIPT_KEYWORD);
		}
		if (args.isEmpty()) {
			List<CompletionCandidate> result = keywordCandidates(List.of("/", "LIB"), current, "source");
			result.addAll(tableCandidates(current));
			return result;
		}
		String joined = String.join(" ", args);
		if (joined.startsWith("(") && joined.chars().filter(c -> c == '(').count() > joined.chars().filter(c -> c == ')').count()) {
			return List.of();
		}
		String previous = args.get(args.size() - 1).toUpperCase(Locale.ROOT);
		if ("LIB".equals(previous) && args.size() == 1) {
			return candidates(CompletionEntityType.SCRIPT, current, "");
		}
		if ("AS".equals(previous)) {
			return keywordCandidates(EXPORT_FORMATS, current, "format");
		}
		if ("TO".equals(previous) || args.stream().anyMatch("AS"::equalsIgnoreCase)) {
			return List.of();
		}
		boolean hasTo = args.stream().anyMatch("TO"::equalsIgnoreCase);
		return keywordCandidates(hasTo ? List.of("AS") : List.of("TO", "AS"), current, null);
	}

	private static List<CompletionCandidate> keywordCandidates(List<String> keywords, String prefix, String description) {
		List<CompletionCandidate> result = new ArrayList<>();
		for (String keyword : keywords) {
			if (matches(keyword, prefix)) {
				result.add(new CompletionCandidate(keyword, keyword, description, CompletionCandidateType.BROADSQL_KEYWORD));
			}
		}
		return result;
	}

	private static final class Match {
		final Command command;
		final int keywordWords;

		Match(Command command, int keywordWords) {
			this.command = command;
			this.keywordWords = keywordWords;
		}
	}

	/** The registered keyword (alias or canonical) that the words before {@code wordIndex} spell, longest first; {@code null} if none. */
	private Match longestKeywordMatch(List<String> words, int wordIndex) {
		Match best = null;
		for (Map.Entry<String, Command> entry : commandList.entrySet()) {
			String keyword = entry.getKey();
			if (keyword == null || keyword.isBlank() || entry.getValue() == null || SCRIPT_KEYWORD.equals(keyword.trim())) {
				continue;
			}
			String[] parts = keyword.trim().split("\\s+");
			if (parts.length > wordIndex || (best != null && parts.length <= best.keywordWords)) {
				continue;
			}
			boolean spelled = true;
			for (int i = 0; i < parts.length && spelled; i++) {
				spelled = parts[i].equalsIgnoreCase(words.get(i));
			}
			if (spelled) {
				best = new Match(entry.getValue(), parts.length);
			}
		}
		return best;
	}

	private List<CompletionCandidate> candidates(CompletionEntityType type, String prefix, String valuePrefix) {
		EntityCandidateProvider provider = providers.get(type);
		if (provider == null) {
			return List.of();
		}
		try {
			List<CompletionCandidate> result = new ArrayList<>();
			for (String name : provider.names()) {
				if (matches(name, prefix)) {
					result.add(new CompletionCandidate(valuePrefix + quoteIfNeeded(name), name, type.name(), CompletionCandidateType.ENTITY));
				}
			}
			return result;
		} catch (Exception e) {
			log.debug("Completion source for {} unavailable, offering nothing: {}", type, e.toString());
			return List.of();
		}
	}

	/**
	 * Every table position: a plain prefix matches the table and view names; {@code SCHEMA.prefix} matches the
	 * tables and views of that schema and keeps the schema as typed ({@code sales.ord} becomes {@code sales.ORDERS}).
	 */
	private List<CompletionCandidate> tableCandidates(String current) {
		int dot = current.lastIndexOf('.');
		if (dot <= 0 || schemaTables == null) {
			return candidates(CompletionEntityType.TABLE, current, "");
		}
		String schema = current.substring(0, dot);
		String prefix = current.substring(dot + 1);
		try {
			List<CompletionCandidate> result = new ArrayList<>();
			for (String name : schemaTables.names(schema)) {
				if (matches(name, prefix)) {
					result.add(new CompletionCandidate(quoteIfNeeded(schema + "." + name), name, CompletionEntityType.TABLE.name(), CompletionCandidateType.ENTITY));
				}
			}
			return result;
		} catch (Exception e) {
			log.debug("Tables of schema {} unavailable, offering nothing: {}", schema, e.toString());
			return List.of();
		}
	}

	/** BroadSQL's own argument syntax quotes a value containing spaces with {@code "} (see {@code CommandUtils.getArgumentsFromQuery}). */
	private static String quoteIfNeeded(String name) {
		return com.upandcoding.broadsql.controller.shell.commands.CommandUtils.quoteArgumentIfNeeded(name);
	}
}
