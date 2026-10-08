package com.upandcoding.broadsql.controller.shell.commands;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.upandcoding.broadsql.controller.shell.commands.core.script.CommandEcho;
import com.upandcoding.broadsql.controller.shell.commands.core.script.CommandLet;
import com.upandcoding.broadsql.controller.shell.commands.core.script.CommandOnError;
import com.upandcoding.broadsql.controller.shell.commands.core.script.CommandOutput;
import com.upandcoding.broadsql.controller.shell.commands.core.script.CommandShowScriptVariables;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandApiImportBruno;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandRun;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowAllApis;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowApiEnvironment;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowApiEnvironments;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowEndpoint;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowEndpoints;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandSyntax;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandVar;
import com.upandcoding.broadsql.controller.shell.commands.core.compare.CommandCompareTableStructure;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandConfig;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandConfigApi;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandDuplicateConnection;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageConnectionAdd;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageConnectionDelete;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageConnectionEdit;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageConnectionReactivate;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageEnvironmentAdd;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageEnvironmentDelete;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageEnvironmentEdit;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageGroupAdd;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageGroupDelete;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageGroupEdit;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageLoginScriptLineAdd;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageLoginScriptLineDelete;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageLoginScriptLineEdit;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageLoginScriptLineMove;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandCopyResult;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandDumpTable;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandExport;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull;
import com.upandcoding.broadsql.controller.shell.commands.core.help.CommandHelp;
import com.upandcoding.broadsql.controller.shell.commands.core.help.CommandVersion;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandConnect;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandEnv;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandDisconnect;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandLogout;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandTestConnection;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExternalFile;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibDel;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibEdit;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibFind;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibLint;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibList;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibRestore;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibRun;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibShow;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibUndo;
import com.upandcoding.broadsql.controller.shell.commands.core.misc.CommandCls;
import com.upandcoding.broadsql.controller.shell.commands.core.misc.CommandLinkTable;
import com.upandcoding.broadsql.controller.shell.commands.core.misc.CommandPrint;
import com.upandcoding.broadsql.controller.shell.commands.core.scripting.CommandJsEval;
import com.upandcoding.broadsql.controller.shell.commands.core.scripting.CommandJsFind;
import com.upandcoding.broadsql.controller.shell.commands.core.scripting.CommandJsList;
import com.upandcoding.broadsql.controller.shell.commands.core.scripting.CommandJsRun;
import com.upandcoding.broadsql.controller.shell.commands.core.set.CommandSetAutoCommit;
import com.upandcoding.broadsql.controller.shell.commands.core.set.CommandSetConnectionPassword;
import com.upandcoding.broadsql.controller.shell.commands.core.set.CommandSetListMode;
import com.upandcoding.broadsql.controller.shell.commands.core.set.CommandSetMasterPassword;
import com.upandcoding.broadsql.controller.shell.commands.core.set.CommandSetSchema;
import com.upandcoding.broadsql.controller.shell.commands.core.set.CommandSetSeparator;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandDescr;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowAllConnections;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowAllEnvironments;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowAllGroups;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowAutoCommit;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowCatalogs;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowColumn;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowForeignKeys;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowIndexes;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowReferences;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandFindForeignKeys;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandFindIndexes;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowConnection;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowDbInfo;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowDrivers;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowExtensionErrors;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowGroup;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowInactiveConnections;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowLoginScript;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowPrimaryKeys;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowQuery;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowSchemas;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowTables;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowViews;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandAll;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandCnt;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExpand;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandFormat;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandRepeat;
import com.upandcoding.broadsql.controller.shell.commands.extensions.CommandDirectLoadBatch;
import com.upandcoding.broadsql.controller.shell.commands.extensions.CommandDirectLoadDirect;
import com.upandcoding.broadsql.controller.shell.commands.extensions.CommandEdit;

import static com.upandcoding.broadsql.controller.shell.commands.CommandCategory.API_CLIENT;
import static com.upandcoding.broadsql.controller.shell.commands.CommandCategory.CONNECTIONS;
import static com.upandcoding.broadsql.controller.shell.commands.CommandCategory.DATABASE_GROUPS;
import static com.upandcoding.broadsql.controller.shell.commands.CommandCategory.DATABASE_EXPLORATION;
import static com.upandcoding.broadsql.controller.shell.commands.CommandCategory.ENVIRONMENTS;
import static com.upandcoding.broadsql.controller.shell.commands.CommandCategory.EXPORT_AND_LOCAL_DATA;
import static com.upandcoding.broadsql.controller.shell.commands.CommandCategory.DATA_IMPORT;
import static com.upandcoding.broadsql.controller.shell.commands.CommandCategory.GENERAL;
import static com.upandcoding.broadsql.controller.shell.commands.CommandCategory.LIGHT_SCRIPTING_JS;
import static com.upandcoding.broadsql.controller.shell.commands.CommandCategory.LOGIN_SCRIPTS;
import static com.upandcoding.broadsql.controller.shell.commands.CommandCategory.RUNNING_QUERIES;
import static com.upandcoding.broadsql.controller.shell.commands.CommandCategory.SESSION_SETTINGS;
import static com.upandcoding.broadsql.controller.shell.commands.CommandCategory.SCRIPTS_LIBRARY;

/**
 * The single authoritative mapping from every {@link Command} class to its {@link CommandCategory} -
 * see that enum's Javadoc for why this exists. Used by both {@code CommandHelp} (CLI {@code HELP} /
 * {@code HELP <category>} / {@code HELP FIND}) and {@code CommandDocGenerator} (the generated command
 * reference), so the two documentation surfaces classify every command identically and can never
 * drift apart the way {@code commands/README.md} did before (see {@link CommandCategory}).
 *
 * <p>A command class with no entry here falls back to {@link CommandCategory#GENERAL} rather than
 * throwing, so a forgotten entry never crashes {@code HELP} for a real user - but it is never silent
 * in development: {@code TestCommandCategoryCatalog} enumerates every concrete, non-hidden,
 * keyworded {@code Command} class actually on the classpath and fails if any of them is missing from
 * {@link #BY_CLASS} or if {@link #BY_CLASS} contains a stale entry for a class that no longer exists.
 * Add every new user-facing command here in the same change that introduces it.
 */
public final class CommandCategoryCatalog {

	private static final Map<Class<? extends Command>, CommandCategory> BY_CLASS = buildMap();

	private CommandCategoryCatalog() {
	}

	/** Documentation lifecycle is independent of runtime registration and Java package placement. */
	public enum DocumentationStatus { DOCUMENTED, DEPRECATED_COMPATIBILITY }

	// SPRINT 2309T: EXPORT (#163, replaced by DUMP / and DUMP LIB) and SET SEPARATOR (#165, replaced by the
	// CSV/TEXT/JSON formats) stay executable for existing scripts but are no longer taught: hidden from HELP,
	// TAB completion and the generated command reference.
	private static final Map<Class<? extends Command>, DocumentationStatus> DOCUMENTATION_STATUS = Map.of(
            CommandDirectLoadBatch.class, DocumentationStatus.DEPRECATED_COMPATIBILITY,
            CommandExport.class, DocumentationStatus.DEPRECATED_COMPATIBILITY,
            CommandSetSeparator.class, DocumentationStatus.DEPRECATED_COMPATIBILITY);

    public static DocumentationStatus documentationStatus(Class<? extends Command> commandClass) {
        return DOCUMENTATION_STATUS.getOrDefault(commandClass, DocumentationStatus.DOCUMENTED);
    }

	/** Shared policy for CLI discovery and generated official documentation; never used by execution. */
	public static boolean isDocumented(Command command) {
		return !command.isHidden() && command.getKeywords() != null && command.getKeywords().length > 0
				&& documentationStatus(command.getClass()) == DocumentationStatus.DOCUMENTED;
	}

	public static CommandCategory categoryOf(Command command) {
		return categoryOf(command.getClass());
	}

	public static CommandCategory categoryOf(Class<? extends Command> commandClass) {
		CommandCategory category = BY_CLASS.get(commandClass);
		return category != null ? category : CommandCategory.GENERAL;
	}

	public static boolean isRegistered(Class<? extends Command> commandClass) {
		return BY_CLASS.containsKey(commandClass);
	}

	/** Every class this catalog has an explicit entry for - used only by tests to detect drift. */
	public static Map<Class<? extends Command>, CommandCategory> registeredClasses() {
		return Collections.unmodifiableMap(BY_CLASS);
	}

	private static Map<Class<? extends Command>, CommandCategory> buildMap() {
		Map<Class<? extends Command>, CommandCategory> m = new HashMap<>();

		// Connections
		m.put(CommandConnect.class, CONNECTIONS);
		m.put(CommandDisconnect.class, CONNECTIONS);
		m.put(CommandLogout.class, CONNECTIONS);
		m.put(CommandTestConnection.class, CONNECTIONS);
		m.put(CommandDuplicateConnection.class, CONNECTIONS);
		m.put(CommandManageConnectionAdd.class, CONNECTIONS);
		m.put(CommandManageConnectionEdit.class, CONNECTIONS);
		m.put(CommandManageConnectionDelete.class, CONNECTIONS);
		m.put(CommandManageConnectionReactivate.class, CONNECTIONS);
		m.put(CommandShowAllConnections.class, CONNECTIONS);
		m.put(CommandShowConnection.class, CONNECTIONS);
		m.put(CommandShowInactiveConnections.class, CONNECTIONS);

		// Database Groups
		m.put(CommandManageGroupAdd.class, DATABASE_GROUPS);
		m.put(CommandManageGroupEdit.class, DATABASE_GROUPS);
		m.put(CommandManageGroupDelete.class, DATABASE_GROUPS);
		m.put(CommandShowGroup.class, DATABASE_GROUPS);
		m.put(CommandShowAllGroups.class, DATABASE_GROUPS);

		// Environments
		m.put(CommandManageEnvironmentAdd.class, ENVIRONMENTS);
		m.put(CommandManageEnvironmentEdit.class, ENVIRONMENTS);
		m.put(CommandManageEnvironmentDelete.class, ENVIRONMENTS);
		m.put(CommandShowAllEnvironments.class, ENVIRONMENTS);
		// SPRINT 2309T (#159): ENV switches the active Connection by Environment; listed with the other
		// Environment commands (HELP ENVS). The category alias is ENVS so that HELP ENV shows this command.
		m.put(CommandEnv.class, ENVIRONMENTS);

		// Login Scripts
		m.put(CommandManageLoginScriptLineAdd.class, LOGIN_SCRIPTS);
		m.put(CommandManageLoginScriptLineEdit.class, LOGIN_SCRIPTS);
		m.put(CommandManageLoginScriptLineDelete.class, LOGIN_SCRIPTS);
		m.put(CommandManageLoginScriptLineMove.class, LOGIN_SCRIPTS);
		m.put(CommandShowLoginScript.class, LOGIN_SCRIPTS);

		// Running Queries
		m.put(CommandAll.class, RUNNING_QUERIES);
		m.put(CommandCnt.class, RUNNING_QUERIES);
		m.put(CommandShowQuery.class, RUNNING_QUERIES);
		m.put(CommandExpand.class, RUNNING_QUERIES);
		m.put(CommandFormat.class, RUNNING_QUERIES);
		// GitHub #196: foreground repetition of a query, a block or a script, for monitoring
		m.put(CommandRepeat.class, RUNNING_QUERIES);
		m.put(CommandEdit.class, RUNNING_QUERIES);

		// Database Exploration
		m.put(CommandDescr.class, DATABASE_EXPLORATION);
		m.put(CommandShowTables.class, DATABASE_EXPLORATION);
		m.put(CommandShowViews.class, DATABASE_EXPLORATION);
		m.put(CommandShowSchemas.class, DATABASE_EXPLORATION);
		m.put(CommandShowCatalogs.class, DATABASE_EXPLORATION);
		m.put(CommandShowColumn.class, DATABASE_EXPLORATION);
		m.put(CommandShowPrimaryKeys.class, DATABASE_EXPLORATION);
		// SPRINT 2409K: portable table metadata (SHOW) and metadata search (FIND)
		m.put(CommandShowForeignKeys.class, DATABASE_EXPLORATION);
		m.put(CommandShowReferences.class, DATABASE_EXPLORATION);
		m.put(CommandShowIndexes.class, DATABASE_EXPLORATION);
		m.put(CommandFindForeignKeys.class, DATABASE_EXPLORATION);
		m.put(CommandFindIndexes.class, DATABASE_EXPLORATION);
		m.put(CommandShowDbInfo.class, DATABASE_EXPLORATION);
		m.put(CommandShowDrivers.class, DATABASE_EXPLORATION);
		m.put(CommandLinkTable.class, DATABASE_EXPLORATION);
		m.put(CommandCompareTableStructure.class, DATABASE_EXPLORATION);

		// Export & Local Data
		m.put(CommandExport.class, EXPORT_AND_LOCAL_DATA);
		m.put(CommandDumpTable.class, EXPORT_AND_LOCAL_DATA);
		m.put(CommandPull.class, EXPORT_AND_LOCAL_DATA);
		m.put(CommandCopyResult.class, EXPORT_AND_LOCAL_DATA);

		// Scripts Library
		m.put(CommandLibDel.class, SCRIPTS_LIBRARY);
		m.put(CommandLibEdit.class, SCRIPTS_LIBRARY);
		m.put(CommandLibFind.class, SCRIPTS_LIBRARY);
		m.put(CommandLibLint.class, SCRIPTS_LIBRARY);
		m.put(CommandLibList.class, SCRIPTS_LIBRARY);
		m.put(CommandLibRestore.class, SCRIPTS_LIBRARY);
		m.put(CommandLibRun.class, SCRIPTS_LIBRARY);
		m.put(CommandLibShow.class, SCRIPTS_LIBRARY);
		m.put(CommandLibUndo.class, SCRIPTS_LIBRARY);

		// Scripts Library: @ (run a Script) plus the LIB family above (SPRINT 1909S)
		m.put(CommandExternalFile.class, SCRIPTS_LIBRARY);

		// Scripts Library: SQL scripting variables and execution controls (SPRINT 0110A)
		m.put(CommandLet.class, SCRIPTS_LIBRARY);
		m.put(CommandEcho.class, SCRIPTS_LIBRARY);
		m.put(CommandOnError.class, SCRIPTS_LIBRARY);
		m.put(CommandOutput.class, SCRIPTS_LIBRARY);
		m.put(CommandShowScriptVariables.class, SCRIPTS_LIBRARY);

		// Light Scripting (JS)
		m.put(CommandJsEval.class, LIGHT_SCRIPTING_JS);
		m.put(CommandJsFind.class, LIGHT_SCRIPTING_JS);
		m.put(CommandJsList.class, LIGHT_SCRIPTING_JS);
		m.put(CommandJsRun.class, LIGHT_SCRIPTING_JS);

		// Session & Settings
		m.put(CommandSetAutoCommit.class, SESSION_SETTINGS);
		m.put(CommandSetConnectionPassword.class, SESSION_SETTINGS);
		m.put(CommandSetListMode.class, SESSION_SETTINGS);
		m.put(CommandSetMasterPassword.class, SESSION_SETTINGS);
		m.put(CommandSetSchema.class, SESSION_SETTINGS);
		m.put(CommandSetSeparator.class, SESSION_SETTINGS);
		m.put(CommandShowAutoCommit.class, SESSION_SETTINGS);
		m.put(CommandShowExtensionErrors.class, GENERAL);

		// General
		m.put(CommandHelp.class, GENERAL);
		m.put(CommandVersion.class, GENERAL);
		m.put(CommandCls.class, GENERAL);
		m.put(CommandPrint.class, GENERAL);
		m.put(CommandConfig.class, GENERAL);

		// Data Import, including the executable but undocumented compatibility adapter.
		m.put(CommandDirectLoadBatch.class, DATA_IMPORT);
		m.put(CommandDirectLoadDirect.class, DATA_IMPORT);

		// API Client (SPRINT XT02 - Universal API Client; command vocabulary consolidated by the API
		// Quality and UX Consolidation sprint - see docs/TECHNICAL_CHANGE.md, 2026-09-14).
		// CommandApiExecuteEndpoint (the shared execution engine RUN delegates to) is deliberately NOT
		// registered here any more - it is now hidden (no public keyword), so CommandCategoryCatalog
		// no longer needs (and TestCommandCategoryCatalog would flag as stale) an entry for it.
		// CommandShowApiEndpoints was deleted outright - its behavior is fully absorbed into
		// CommandShowEndpoints' optional "API <apiId>" clause, not merely hidden.
		m.put(CommandApiImportBruno.class, API_CLIENT);
		m.put(CommandShowAllApis.class, API_CLIENT);
		m.put(CommandShowApiEnvironments.class, API_CLIENT);
		// SPRINT XT02B, section 11: singular detail view (one environment), companion to the plural listing above.
		m.put(CommandShowApiEnvironment.class, API_CLIENT);
		m.put(CommandShowEndpoint.class, API_CLIENT);
		// CONFIG API configures the same API/HTTP client catalog these commands operate on - catalogued
		// alongside them (API_CLIENT), not GENERAL, unlike its database counterpart CommandConfig; see
		// this class's own javadoc and CommandCategory.API_CLIENT's description (SPRINT XT02 sub-sprint 5).
		m.put(CommandConfigApi.class, API_CLIENT);
		// Interactive API session context ergonomics (SPRINT XT02-7B) - CONNECT API/DISCONNECT API are
		// branches inside CommandConnect/CommandDisconnect (CONNECTIONS category, unchanged), not separate
		// command classes, so nothing new is registered here for them.
		m.put(CommandRun.class, API_CLIENT);
		m.put(CommandShowEndpoints.class, API_CLIENT);
		// SPRINT XT02A (URL-Native API Execution) - RUN's own new grammar (VAR session overrides,
		// SYNTAX generated call-syntax help) - see docs/TECHNICAL_CHANGE.md.
		m.put(CommandVar.class, API_CLIENT);
		m.put(CommandSyntax.class, API_CLIENT);

		return Collections.unmodifiableMap(m);
	}
}
