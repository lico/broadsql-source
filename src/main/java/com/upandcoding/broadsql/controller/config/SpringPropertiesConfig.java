package com.upandcoding.broadsql.controller.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Year;

import org.apache.commons.lang3.StringUtils;

import com.sun.jna.Platform;

public class SpringPropertiesConfig {

	// Application Settings
	public static final String APP_TITLE = "BroadSQL";
	// The version number is the JAR's MANIFEST.MF Implementation-Version, the Maven property ${app.version}:
	// the release number for a release build (build.bat X.Y.Z, as publish.bat X.Y.Z requires), "development"
	// otherwise - see the maven-jar-plugin/maven-assembly-plugin <manifestEntries> in pom.xml. Returns
	// "UNKNOWN" when run outside a built JAR (e.g. tests, IDE), since there is no manifest to read in that case.
	public static final String APP_VERSION_NUMBER = loadAppVersionNumber();
	// The end year is always the current year, computed at class-load time rather than hardcoded, so
	// this never needs a manual yearly edit again.
	public static final String APP_VERSION = "release " + APP_VERSION_NUMBER + ", 2008-" + Year.now().getValue() + " by UpAndCoding.com";
	public static final String APP_SITE = "https://www.broadsql.com";
	// The documentation home page (not a specific page); what the BroadSQL Editor's About dialog links to.
	public static final String APP_DOCS_URL = APP_SITE + "/docs/";
	public static final String APP_TITLE_CONFIG = APP_TITLE + " Settings";

	private static String loadAppVersionNumber() {
		String version = SpringPropertiesConfig.class.getPackage().getImplementationVersion();
		return StringUtils.isNotBlank(version) ? version : "UNKNOWN";
	}

	// General Settings
	public static final String PROMPT_TEXT = "BroadSQL> ";

	// File System and export Settings
	private static String currentPath = null;
	private static String fileSep = null;
	public static final String WIN_FSEP = "\\";
	public static final String LNX_FSEP = "/";

	// CDF Database Settings
	public static final String CDF_TYPE = "H2";
	public static final String CDF_ID = "$CDF";
	
	// DB Settings
	public static int MAX_CNCT_RETRY = 3; 		// Nb of retries after 1st failure
	public static int CNCT_RETRY_DELAY = 0; 	// In milliseconds 

	// DB Types
	public static final String DBTYPE_H2 = "H2";
	public static final String DBTYPE_MySQL = "MySQL";
	public static final String DBTYPE_Oracle = "Oracle";
	public static final String DBTYPE_HSQL = "HSQL";
	public static final String DBTYPE_SQLITE = "SQLite";
	public static final String DBTYPE_DERBY_Embedded = "DERBY Embedded";
	public static final String DBTYPE_DERBY_Client = "DERBY Client";
	public static final String DBTYPE_JDBC_ODBC_Bridge = "JDBC-ODBC Bridge";
	public static final String DBTYPE_Informix = "Informix";
	public static final String DBTYPE_Intersys = "Intersys";
	public static final String DBTYPE_Sybase = "Sybase";
	public static final String DBTYPE_MariaDB = "MariaDB";
	public static final String DBTYPE_PostgreSQL = "PostgreSQL";
	public static final String DBTYPE_SQL_Server = "SQL Server";
	public static final String DBTYPE_Teradata = "Teradata";
	public static final String DBTYPE_DB2 = "DB2";
	public static final String DBTYPE_InstantDB = "InstantDB";
	public static final String DBTYPE_IDS_Server = "IDS Server";
	public static final String DBTYPE_Firebird = "Firebird";
	public static final String DBTYPE_Cloudscape = "Cloudscape";
	public static final String DBTYPE_Pointbase = "Pointbase";

	// Properties NOT editable by users
	// ******************************** 

	public static final String CDF_FILE = "ServersFileName";

	public static final String FOLD_EXTRACT = "DefaultFolder";

	public static final String FILE_EXTRACT = "DefaultExtFileName";

	public static final String FOLD_CUST_EXT = "CustomExtensionsFolder";

	public static final String COL_SEP = "FieldsSeparator";

	public static final String SCRN_SEP = "ScreenSeparator";

	public static final String MAX_ROW_SCRN = "MaxRowsOnScreen";

	public static final String MAX_ROW_XL = "MaxRowXLSX";

	public static final String DEFAULT_FILE_FORMAT = "DefaultFileFormat";

	/**
	 * The {@code ENVIRONMENT_ID} a freshly created {@code PULL ... AS H2} connection is registered with
	 * (see {@code ConsoleSettings.getDefaultEnvironment()}, {@code CommandPull.createH2Connection}) -
	 * the {@code INSTANCE_ID} is resolved separately, from the CDF's own {@code INSTANCE} table. Falls
	 * back to {@code DEV} when absent from the INI file - see {@code docs/TODO.md}, item 5.
	 */
	public static final String DEFAULT_ENVIRONMENT = "DefaultEnvironment";

	/**
	 * Field separator used only by {@code PULL ... TO <name> AS CSV} (see docs/PULL_TO_TEXT.md) -
	 * deliberately independent of {@link #COL_SEP}/{@code SET SEP}, which {@code EXPORT}/{@code DUMP}'s
	 * {@code .csv} output ignores anyway (always comma there). A single literal character (e.g. {@code ;}
	 * or {@code ,}), not a symbolic name like {@code SET SEP}'s {@code TAB}/{@code PIPE}. Falls back to
	 * {@code ;} when absent - see {@code ConsoleSettings.getCsvSeparator()}.
	 */
	public static final String CSV_SEPARATOR = "CsvSeparator";

	public static final String AUTOCOMMIT = "Autocommit";

	/**
	 * SPRINT 1909S: the Scripts Library folder, the one managed location of reusable Scripts (default
	 * {@code scripts}). Replaces the obsolete {@code SqlLib}, {@code Scripts} and {@code ListSubfolders}
	 * keys, which are no longer read (hard break, no fallback). Optional in the INI file: bound with an
	 * empty Spring default (see {@code ConsoleSettings.getScriptsLibraryPath()}).
	 */
	public static final String SCRIPTS_LIBRARY = "ScriptsLibrary";

	/**
	 * Root folder for the {@code JS} command family's script catalog (see
	 * {@code docs/LIGHT_SCRIPTING.md}), kept in its own folder, separate from the Scripts Library (JS is not part of it) - {@code JsScriptCatalog} auto-appends {@code .sql} (not
	 * {@code .js}) on an extension-less name and scans every file in its folder regardless of
	 * extension, so a shared folder would mix the two catalogs. New key - bound with an empty Spring
	 * default (see {@code ConsoleSettings.jsScriptsPath}) rather than requiring it.
	 */
	public static final String SQL_JS_SCRIPTS = "JsScripts";

	// SPRINT XT02A (URL-Native API Execution), section 12.1: JLine is an optional interactive
	// enhancement only - "ON"/"OFF" (not true/false, matching the spec's exact requested spelling),
	// case-insensitive, defaulting to OFF for backward compatibility with every existing install.
	public static final String ACTIVATE_JLINE = "activatejline";

	// SPRINT XT02B, section 14: overrides where persistent JLine history is stored. Blank/absent ->
	// ${user.home}/.broadsql/history (a new, first per-OS-user state convention for this app, since
	// history is personal, session-continuity data - a shared install must never mix or leak one OS
	// user's typed commands to another). A relative value resolves under ${user.home}/.broadsql/, never
	// against the install directory - see ConsoleSettings#resolveJLineHistoryFilePath.
	public static final String JLINE_HISTORY_FILE = "jlinehistoryfile";

	// SPRINT 2409K: terminal styling and adaptive display. color = AUTO/ON/OFF, theme = one of the
	// built-in theme names (ConsoleSettings / shell.style.Theme), displaymode = AUTO/COMPACT/NORMAL/WIDE.
	public static final String COLOR = "color";
	public static final String THEME = "theme";
	public static final String DISPLAY_MODE = "displaymode";

	// SPRINT 0917-01 (Script Library, Editor and Version History) - optional override for the Script
	// Library's filesystem revision vault root. Same resolution convention as JLINE_HISTORY_FILE above
	// (blank -> ${user.home}/.broadsql/script-history, relative -> resolved under
	// ${user.home}/.broadsql/, absolute -> used as-is) - see ConsoleSettings#resolveScriptHistoryVaultPath.
	public static final String SCRIPT_HISTORY_VAULT = "scripthistoryvault";

	// SPRINT XT02A, section 19.1-19.6: enterprise HTTP/HTTPS proxy support for API execution only -
	// scoped to com.upandcoding.broadsql.dao.api.http.ApiHttpTransport, never unrelated BroadSQL networking.
	public static final String API_PROXY_MODE = "apiproxymode";
	public static final String API_PROXY_TYPE = "apiproxytype";
	public static final String API_PROXY_HOST = "apiproxyhost";
	public static final String API_PROXY_PORT = "apiproxyport";
	public static final String API_PROXY_NON_PROXY_HOSTS = "apiproxynonproxyhosts";
	public static final String API_PROXY_USERNAME = "apiproxyusername";
	public static final String API_PROXY_PASSWORD = "apiproxypassword";

	public static final String LOG_ENABLED = "IsLogActivated";

	public static final String LOG_FOLDER = "LogFolderName";

	public static final String LOG_NAME = "LogFileNamePattern";

	public static final String WIN_EDIT_PLUS = "WinEditPlus";

	public static String getFileSep() {
		if (StringUtils.isBlank(fileSep)) {
			if (Platform.isWindows()) {
				fileSep = WIN_FSEP;
			} else {
				fileSep = LNX_FSEP;
			}
		}
		return fileSep;
	}

	public static String getCurrentPath() {
		if (StringUtils.isBlank(currentPath)) {
			Path currentRelativePath = Paths.get("");
			currentPath = currentRelativePath.toAbsolutePath().toString();
		}
		return currentPath;
	}
}
