package com.upandcoding.broadsql.controller.shell.commands;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The canonical, permanent logical grouping of BroadSQL commands - what a user is trying to
 * accomplish, never a Java package or implementation detail. Displayed by {@code HELP} and
 * {@code HELP <category>}, and by the generated {@code releases/documentation/commands/README.md}.
 *
 * <p>Enum declaration order is display order everywhere this is iterated ({@link #values()}) -
 * {@link #EXTENSION_COMMANDS} is deliberately declared last. See {@link CommandCategoryCatalog} for
 * the class-to-category mapping this enum is used with, and
 * {@code docs/TECHNICAL_CHANGE.md} (SPRINT 0911A) for why this exists: a hand-authored categorization
 * of the command reference was silently flattened back to a flat Core/Extension split by
 * {@code CommandDocGenerator} on every release because nothing in code carried the category. This
 * enum plus {@link CommandCategoryCatalog} is that missing authoritative source - do not reintroduce
 * a second, independent category list anywhere else.
 *
 * <p>Each category also carries one canonical, short {@code alias} (e.g. {@code CONN} for
 * {@link #CONNECTIONS}) - authoritative metadata here, not a scattered chain of {@code if} statements
 * in {@code CommandHelp}. The alias is what {@code HELP} actually displays and recommends typing;
 * {@link #findByName} additionally accepts the full display name, the enum constant name, and a
 * handful of extra hand-picked synonyms (see {@link #ALIASES}) so typing the full category name
 * always still works too.
 */
public enum CommandCategory {

	CONNECTIONS("Connections", "CONN", "Connect to databases and manage saved connections."),
	DATABASE_GROUPS("Database Groups", "GROUPS", "Group related database connections."),
	// SPRINT 2309T: alias ENVS, not ENV - ENV is the Environment-switching command, and HELP resolves a
	// category before a command, so HELP ENV must not land here.
	ENVIRONMENTS("Environments", "ENVS", "Define DEV, QA, PROD and other environments."),
	LOGIN_SCRIPTS("Login Scripts", "LOGIN", "Configure SQL automatically executed when a connection opens."),
	RUNNING_QUERIES("Running Queries", "QUERY", "Execute SQL and work with query results."),
	DATABASE_EXPLORATION("Database Exploration", "EXPLORE", "Explore schemas, tables, columns and database metadata."),
	EXPORT_AND_LOCAL_DATA("Export & Local Data", "EXPORT", "Export and preserve data using Excel, ODS, CSV, H2 and more."),
	DATA_IMPORT("Data Import", "IMPORT", "Import data into existing database tables."),
	SCRIPTS_LIBRARY("Scripts Library", "LIB", "Run and manage reusable Scripts: text files of SQL and BroadSQL commands."),
	LIGHT_SCRIPTING_JS("Light Scripting (JS)", "JS", "Run and manage JavaScript against query results."),
	SESSION_SETTINGS("Session & Settings", "SESSION", "Control the BroadSQL session and display behavior."),
	API_CLIENT("API Client", "API", "Configure, import, browse and execute HTTP API endpoints with the Universal API Client."),
	GENERAL("General", "GEN", "Basic session tools: HELP, VERSION, CONFIG, CLS, PRINT."),
	EXTENSION_COMMANDS("Extension Commands", "EXT", "Commands provided through BroadSQL's extension mechanism.",
			"BroadSQL does not currently ship with any extension commands. This category is reserved for commands provided through BroadSQL's extension mechanism and may include optional commands in future releases.");

	private final String displayName;
	private final String alias;
	private final String description;
	private final String emptyState;

	CommandCategory(String displayName, String alias, String description) {
		this(displayName, alias, description, null);
	}

	CommandCategory(String displayName, String alias, String description, String emptyState) {
		this.displayName = displayName;
		this.alias = alias;
		this.description = description;
		this.emptyState = emptyState;
	}

	/** Non-null reserves a visible category even when it has no documented commands. */
	public String getEmptyState() {
		return emptyState;
	}

	public String getDisplayName() {
		return displayName;
	}

	/** The one canonical short form shown by {@code HELP} and always accepted by {@code HELP <alias>}. */
	public String getAlias() {
		return alias;
	}

	public String getDescription() {
		return description;
	}

	/**
	 * Extra accepted synonyms beyond the canonical {@link #getAlias()}, the exact display name, and the
	 * enum constant name - e.g. {@code HELP EXPORT} for {@link #EXPORT_AND_LOCAL_DATA} works both via
	 * its canonical alias and this map. Deliberately small and hand-picked rather than a fuzzy matcher -
	 * see {@code CommandHelp}'s "did you mean" suggestion for the fallback when nothing here matches.
	 */
	private static final Map<String, CommandCategory> ALIASES = buildAliasMap();

	private static Map<String, CommandCategory> buildAliasMap() {
		Map<String, CommandCategory> m = new HashMap<>();
		for (CommandCategory category : values()) {
			m.put(category.alias.toLowerCase(), category);
		}
		m.put("connection", CONNECTIONS);
		m.put("connections", CONNECTIONS);
		m.put("group", DATABASE_GROUPS);
		m.put("groups", DATABASE_GROUPS);
		m.put("databasegroup", DATABASE_GROUPS);
		m.put("environment", ENVIRONMENTS);
		m.put("environments", ENVIRONMENTS);
		m.put("loginscript", LOGIN_SCRIPTS);
		m.put("loginscripts", LOGIN_SCRIPTS);
		m.put("query", RUNNING_QUERIES);
		m.put("queries", RUNNING_QUERIES);
		m.put("running", RUNNING_QUERIES);
		m.put("exploration", DATABASE_EXPLORATION);
		m.put("schema", DATABASE_EXPLORATION);
		m.put("structure", DATABASE_EXPLORATION);
		// SPRINT 2309T: no "dump"/"pull" synonyms any more - HELP resolves a category before a command, so
		// they made HELP DUMP/HELP PULL show this category instead of the commands' own detailed help.
		m.put("library", SCRIPTS_LIBRARY);
		m.put("scriptslibrary", SCRIPTS_LIBRARY);
		m.put("scripts", SCRIPTS_LIBRARY);
		m.put("script", SCRIPTS_LIBRARY);
		m.put("javascript", LIGHT_SCRIPTING_JS);
		m.put("scripting", LIGHT_SCRIPTING_JS);
		m.put("settings", SESSION_SETTINGS);
		m.put("set", SESSION_SETTINGS);
		m.put("general", GENERAL);
		m.put("extension", EXTENSION_COMMANDS);
		m.put("extensions", EXTENSION_COMMANDS);
		return Collections.unmodifiableMap(m);
	}

	/**
	 * Matches an exact display name, enum constant name, canonical {@link #getAlias() alias}, or extra
	 * synonym (see {@link #ALIASES}) - spaces/ampersands/case ignored throughout - or null if
	 * {@code name} matches none of those.
	 */
	public static CommandCategory findByName(String name) {
		if (name == null) {
			return null;
		}
		String needle = normalize(name);
		for (CommandCategory category : values()) {
			if (normalize(category.displayName).equals(needle) || category.name().equalsIgnoreCase(name.trim())) {
				return category;
			}
		}
		return ALIASES.get(needle);
	}

	/** Every accepted alias/synonym keyword (lowercase, no spaces) - used only by {@code CommandHelp}'s "did you mean" suggestion. */
	public static Set<String> aliasKeywords() {
		return Collections.unmodifiableSet(ALIASES.keySet());
	}

	private static String normalize(String s) {
		return s.trim().toLowerCase().replace("&", "and").replaceAll("[^a-z0-9]", "");
	}
}
