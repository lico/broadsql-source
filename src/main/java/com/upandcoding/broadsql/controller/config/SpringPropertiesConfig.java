package com.upandcoding.broadsql.controller.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Year;

import org.apache.commons.lang3.StringUtils;

import com.sun.jna.Platform;

public class SpringPropertiesConfig {

	// Application Settings
	public static final String APP_TITLE = "BroadSQL";
	// The version number comes from version.txt (single source of truth), baked into the JAR's
	// MANIFEST.MF (Implementation-Version) at build time - see the maven-jar-plugin/maven-assembly-plugin
	// <manifestEntries> and the "read-version" antrun execution in pom.xml. version.txt itself is
	// never shipped in the JAR, only its value. Returns "UNKNOWN" when run outside a built JAR
	// (e.g. tests, IDE), since there is no manifest to read in that case.
	public static final String APP_VERSION_NUMBER = loadAppVersionNumber();
	// The end year is always the current year, computed at class-load time rather than hardcoded, so
	// this never needs a manual yearly edit again.
	public static final String APP_VERSION = "release " + APP_VERSION_NUMBER + ", 2008-" + Year.now().getValue() + " by UpAndCoding.com";
	public static final String APP_SITE = "https://www.broadsql.com";
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

	public static final String SQL_LIB = "SqlLib";

	/**
	 * Root folder for the {@code SCRIPT *} command family (see {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}),
	 * parallel to {@link #SQL_LIB}. New key - existing installs' INI files predate it, so it is bound
	 * with an empty Spring default (see {@code ConsoleSettings.scriptsPath}) rather than requiring it.
	 */
	public static final String SQL_SCRIPTS = "Scripts";

	/**
	 * Root folder for the {@code JS} command family's script catalog (see
	 * {@code docs/LIGHT_SCRIPTING.md}), parallel to {@link #SQL_SCRIPTS} but kept in its own folder
	 * rather than sharing {@code scripts/} - {@code FileCatalog} auto-appends {@code .sql} (not
	 * {@code .js}) on an extension-less name and scans every file in its folder regardless of
	 * extension, so a shared folder would mix the two catalogs. New key - bound with an empty Spring
	 * default (see {@code ConsoleSettings.jsScriptsPath}) rather than requiring it.
	 */
	public static final String SQL_JS_SCRIPTS = "JsScripts";

	/**
	 * Whether {@code SCRIPT LIST}/{@code LIB LIST} also show entries stored in subfolders of
	 * {@link #SQL_SCRIPTS}/{@link #SQL_LIB} (see {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}). Subfolders
	 * are always resolved correctly by every other command ({@code RUN}/{@code SHOW}/{@code EDIT}/
	 * {@code DEL}/{@code FIND}/...) regardless of this setting - it only controls what the two
	 * {@code LIST} grids display by default. New key - existing installs' INI files predate it, so it
	 * is bound with a {@code false} Spring default (see {@code ConsoleSettings.isListSubfolders()})
	 * rather than requiring it.
	 */
	public static final String LIST_SUBFOLDERS = "ListSubfolders";

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
