package com.upandcoding.broadsql.controller.shell;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;

/*
 * INI file:
 * http://commons.apache.org/configuration/apidocs/org/apache/commons/configuration/HierarchicalINIConfiguration.html
 */
public class ConsoleSettings {

	private final static Logger log = LoggerFactory.getLogger(ConsoleSettings.class);

	public static final boolean DEBUG = false;

	public static String DEFAULT_PROMPT = SpringPropertiesConfig.APP_TITLE + "> ";
	public static String defaultBroadSQLJarFile = "lib/broadsql.jar";

	// Session management
	public int SESSION_DURATION = 5 * 60 * 60 * 1000; // 5 hours, Time of the login session, in milliseconds

	// General
	@Value("${" + SpringPropertiesConfig.CDF_FILE + "}")
	private String protectedPlatformsFileName;

	private String serversFileType = SpringPropertiesConfig.CDF_TYPE;

	// Output (for data extractions)
	@Value("${" + SpringPropertiesConfig.FOLD_EXTRACT + "}")
	private String extractFolderName;
	@Value("${" + SpringPropertiesConfig.FILE_EXTRACT + "}")
	private String extractDefaultFileName;

	// Output settings
	@Value("${" + SpringPropertiesConfig.COL_SEP + "}")
	private char defaultSeparator;
	@Value("${" + SpringPropertiesConfig.MAX_ROW_SCRN + "}")
	private int maxRowsOnScreen;
	@Value("${" + SpringPropertiesConfig.SCRN_SEP + "}")
	private char onScreenSeparator;
	@Value("${" + SpringPropertiesConfig.MAX_ROW_XL + "}")
	private int maxRowXlsx;

	// Default export format for EXPORT (when the file name has no extension) and DUMP.
	// Supported values: XLSX, ODS, CSV, TXT (not case-sensitive). Empty default (rather than no
	// default at all) so a missing key does not blow up placeholder resolution at startup - blank vs.
	// an invalid value are then told apart and reported differently, see resolveDefaultFileFormat().
	@Value("${" + SpringPropertiesConfig.DEFAULT_FILE_FORMAT + ":}")
	private String defaultFileFormatRaw;

	public static final String FILE_FORMAT_XLSX = "XLSX";
	public static final String FILE_FORMAT_ODS = "ODS";
	public static final String FILE_FORMAT_CSV = "CSV";
	public static final String FILE_FORMAT_TXT = "TXT";
	private static final String[] SUPPORTED_FILE_FORMATS = { FILE_FORMAT_XLSX, FILE_FORMAT_ODS, FILE_FORMAT_CSV, FILE_FORMAT_TXT };
	// Decided 27/08/2026 (see docs/TODO.md, "Amélioration de l'export"): ODS is now the format used
	// when DefaultFileFormat is absent from the INI file or holds an unrecognized value - not XLSX.
	public static final String DEFAULT_FILE_FORMAT_FALLBACK = FILE_FORMAT_ODS;

	// Resolved lazily from defaultFileFormatRaw on first access (see getDefaultFileFormat()) - null
	// until then. Not resolved eagerly in the constructor because @Value fields are only populated by
	// Spring's placeholder configurer after the bean is constructed.
	private String defaultFileFormat;
	// Non-null only when there is something to report at startup: DefaultFileFormat missing from the
	// INI file, or present but not one of SUPPORTED_FILE_FORMATS.
	private String defaultFileFormatStartupNotice;

	// Separator used only by PULL ... AS CSV (see docs/PULL_TO_TEXT.md) - independent of COL_SEP/SET SEP.
	// Same "empty Spring default, resolved and defaulted in code" reasoning as defaultFileFormatRaw above:
	// existing installs' INI files predate this key, so a missing key must not blow up startup.
	@Value("${" + SpringPropertiesConfig.CSV_SEPARATOR + ":}")
	private String csvSeparatorRaw;

	public static final char CSV_SEPARATOR_FALLBACK = ';';

	// Resolved lazily from csvSeparatorRaw on first access, same reasoning as defaultFileFormat above.
	private Character csvSeparator;

	// ENVIRONMENT_ID a freshly created PULL ... AS H2 connection is registered with (see
	// CommandPull.createH2Connection) - see docs/TODO.md, item 5. Same "empty Spring default, resolved
	// and defaulted in code" reasoning as defaultFileFormatRaw above: an unrestricted free-text value
	// (unlike DefaultFileFormat, there is no fixed set of valid environments), so no candidate list to
	// validate against - only "present" vs. "absent" matters here.
	@Value("${" + SpringPropertiesConfig.DEFAULT_ENVIRONMENT + ":}")
	private String defaultEnvironmentRaw;

	public static final String DEFAULT_ENVIRONMENT_FALLBACK = "DEV";

	// Resolved lazily from defaultEnvironmentRaw on first access, same reasoning as defaultFileFormat above.
	private String defaultEnvironment;
	// Non-null only when there is something to report at startup: DefaultEnvironment missing from the
	// INI file.
	private String defaultEnvironmentStartupNotice;

	// SQL Settings
	public static String defaultCommandName = "_DEFAULT_COMMAND";
	public boolean processUnknownCommands = true; // If true whatever entered command is sent to Server, otherwise an error
													// message is displayed if the command does not exist explicitely
	@Value("${" + SpringPropertiesConfig.AUTOCOMMIT + "}")
	private boolean autoCommit;

	// Queries Library
	@Value("${" + SpringPropertiesConfig.SQL_LIB + "}")
	private String libraryPath;

	// Scripts catalog (SCRIPT * commands) - new key, empty default so existing installs' INI files
	// (which predate it) don't fail to start, same reasoning as csvSeparatorRaw/defaultEnvironmentRaw above.
	@Value("${" + SpringPropertiesConfig.SQL_SCRIPTS + ":}")
	private String scriptsPathRaw;

	// Matches the "scripts" subfolder shipped alongside "sqllib" in every distribution (see deploy/) -
	// used when Scripts is absent or blank from the INI file, same "resolved relative to the working
	// directory" rule FileCatalog already applies to any relative path (see FileCatalog.assignPath()).
	public static final String DEFAULT_SCRIPTS_PATH_FALLBACK = "scripts";

	// Resolved lazily from scriptsPathRaw on first access, same reasoning as defaultEnvironment above.
	private String scriptsPath;
	// Non-null only when there is something to report at startup: Scripts missing from the INI file.
	private String scriptsPathStartupNotice;

	// JS scripts catalog (JS * commands, see docs/LIGHT_SCRIPTING.md) - its own folder, not shared
	// with scriptsPathRaw above, see the Javadoc on SpringPropertiesConfig.SQL_JS_SCRIPTS.
	@Value("${" + SpringPropertiesConfig.SQL_JS_SCRIPTS + ":}")
	private String jsScriptsPathRaw;

	// Matches the "jsscripts" subfolder shipped alongside "scripts"/"sqllib" in every distribution -
	// used when JsScripts is absent or blank from the INI file, same rule as DEFAULT_SCRIPTS_PATH_FALLBACK.
	public static final String DEFAULT_JSSCRIPTS_PATH_FALLBACK = "jsscripts";

	// Resolved lazily from jsScriptsPathRaw on first access, same reasoning as scriptsPath above.
	private String jsScriptsPath;
	// Non-null only when there is something to report at startup: JsScripts missing from the INI file.
	private String jsScriptsPathStartupNotice;

	// Whether SCRIPT LIST/LIB LIST also show subfolder entries by default - false (top-level entries
	// only) unless the INI file says otherwise. New key, so it needs a real default (not just an
	// empty-string placeholder like scriptsPathRaw above) for existing installs whose INI files predate
	// it - Spring can bind a boolean straight off the "false" literal, no lazy-resolution dance needed.
	@Value("${" + SpringPropertiesConfig.LIST_SUBFOLDERS + ":false}")
	private boolean listSubfolders;

	// Log Activity
	@Value("${" + SpringPropertiesConfig.LOG_ENABLED + "}")
	private boolean logDefaultActivated;
	@Value("${" + SpringPropertiesConfig.LOG_FOLDER + "}")
	private String logFolderName;
	@Value("${" + SpringPropertiesConfig.LOG_NAME + "}")
	private String logFileName;

	// Java
	public String packageNameDefault = "com/upandcoding/broadsql/controller/shell/commands/core"; // Core commands
	public String packageNameExtensions = "com/upandcoding/broadsql/controller/shell/commands/extensions"; // Core extensions
	
	@Value("${" + SpringPropertiesConfig.FOLD_CUST_EXT + "}")
	private String customExtensionsFolders;

	// Commands
	public static String CMD_EXIT = "exit";

	// Misc
	@Value("${" + SpringPropertiesConfig.WIN_EDIT_PLUS + "}")
	String winEditPlus;

	private String iniFileName;

	public ConsoleSettings() {
	}

	public String getIniFileName() {
		return iniFileName;
	}

	public void setIniFileName(String iniFileName) {
		this.iniFileName = iniFileName;
	}

	public String getProtectedPlatformsFileName() {
		return protectedPlatformsFileName;
	}

	public void setProtectedPlatformsFileName(String protectedPlatformsFileName) {
		this.protectedPlatformsFileName = protectedPlatformsFileName;
	}

	public String getServersFileType() {
		return serversFileType;
	}

	public void setServersFileType(String serversFileType) {
		this.serversFileType = serversFileType;
	}

	public String getExtractFolderName() {
		return extractFolderName;
	}

	public void setExtractFolderName(String extractFolderName) {
		this.extractFolderName = extractFolderName;
	}

	public String getExtractDefaultFileName() {
		return extractDefaultFileName;
	}

	public void setExtractDefaultFileName(String extractDefaultFileName) {
		this.extractDefaultFileName = extractDefaultFileName;
	}

	public char getDefaultSeparator() {
		return defaultSeparator;
	}

	public void setDefaultSeparator(char defaultSeparator) {
		this.defaultSeparator = defaultSeparator;
	}

	public int getMaxRowsOnScreen() {
		return maxRowsOnScreen;
	}

	public void setMaxRowsOnScreen(int maxRowsOnScreen) {
		this.maxRowsOnScreen = maxRowsOnScreen;
	}

	public char getOnScreenSeparator() {
		return onScreenSeparator;
	}

	public void setOnScreenSeparator(char onScreenSeparator) {
		this.onScreenSeparator = onScreenSeparator;
	}

	public int getMaxRowXlsx() {
		return maxRowXlsx;
	}

	public void setMaxRowXlsx(int maxRowXlsx) {
		this.maxRowXlsx = maxRowXlsx;
	}

	/**
	 * The validated, upper-cased default export format ({@link #FILE_FORMAT_XLSX},
	 * {@link #FILE_FORMAT_ODS}, {@link #FILE_FORMAT_CSV} or {@link #FILE_FORMAT_TXT}), resolved from
	 * {@code DefaultFileFormat} in {@code BroadSQL.ini}. Falls back to {@link #DEFAULT_FILE_FORMAT_FALLBACK}
	 * when the key is missing or its value is not one of the four supported formats - see
	 * {@link #getDefaultFileFormatStartupNotice()} for the corresponding INFO message in that case.
	 */
	public String getDefaultFileFormat() {
		resolveDefaultFileFormat();
		return defaultFileFormat;
	}

	/**
	 * Lower-cased file extension (without the dot) matching {@link #getDefaultFileFormat()} - what
	 * EXPORT appends to an extension-less file name, and what DUMP uses for tables at or below
	 * {@code MaxRowXLSX}.
	 */
	public String getDefaultFileExtension() {
		return getDefaultFileFormat().toLowerCase();
	}

	/**
	 * An INFO message to show once at startup, or {@code null} when {@code DefaultFileFormat} was
	 * present in the INI file and valid (nothing to report).
	 */
	public String getDefaultFileFormatStartupNotice() {
		resolveDefaultFileFormat();
		return defaultFileFormatStartupNotice;
	}

	/**
	 * Populates {@link #defaultFileFormat} and {@link #defaultFileFormatStartupNotice} from
	 * {@link #defaultFileFormatRaw}, the first time either is requested. Idempotent: does nothing on
	 * later calls, since {@link #defaultFileFormat} is only ever {@code null} before the first
	 * resolution.
	 */
	private void resolveDefaultFileFormat() {
		if (defaultFileFormat != null) {
			return;
		}
		if (StringUtils.isBlank(defaultFileFormatRaw)) {
			defaultFileFormat = DEFAULT_FILE_FORMAT_FALLBACK;
			defaultFileFormatStartupNotice = SpringPropertiesConfig.DEFAULT_FILE_FORMAT
					+ " not found in the INI file, using default " + DEFAULT_FILE_FORMAT_FALLBACK + ".";
		} else {
			String candidate = defaultFileFormatRaw.trim().toUpperCase();
			boolean supported = false;
			for (String format : SUPPORTED_FILE_FORMATS) {
				if (format.equals(candidate)) {
					supported = true;
					break;
				}
			}
			if (supported) {
				defaultFileFormat = candidate;
			} else {
				defaultFileFormat = DEFAULT_FILE_FORMAT_FALLBACK;
				defaultFileFormatStartupNotice = SpringPropertiesConfig.DEFAULT_FILE_FORMAT + " value '" + defaultFileFormatRaw
						+ "' in the INI file is not valid (expected XLSX, ODS, CSV or TXT) - ignored, using default "
						+ DEFAULT_FILE_FORMAT_FALLBACK + ".";
			}
		}
	}

	/**
	 * The single character used to separate fields in a {@code PULL ... AS CSV} output file, resolved
	 * from {@code CsvSeparator} in {@code BroadSQL.ini}. Falls back to {@link #CSV_SEPARATOR_FALLBACK}
	 * (semicolon) when the key is absent or blank - only the first character of a longer value is used,
	 * unlike {@code SET SEP}, which also accepts symbolic names like {@code TAB}/{@code PIPE}; this
	 * setting is a single literal character only (e.g. {@code ;} or {@code ,}). Independent of
	 * {@link #getDefaultSeparator()}/{@code SET SEP} - see docs/PULL_TO_TEXT.md, "CSV separator".
	 */
	public char getCsvSeparator() {
		if (csvSeparator == null) {
			csvSeparator = StringUtils.isBlank(csvSeparatorRaw) ? CSV_SEPARATOR_FALLBACK : csvSeparatorRaw.charAt(0);
		}
		return csvSeparator;
	}

	public void setCsvSeparator(char csvSeparator) {
		this.csvSeparator = csvSeparator;
	}

	/**
	 * The {@code ENVIRONMENT_ID} a freshly created {@code PULL ... AS H2} connection is registered with
	 * (see {@code CommandPull.createH2Connection}), resolved from {@code DefaultEnvironment} in
	 * {@code BroadSQL.ini}. Falls back to {@link #DEFAULT_ENVIRONMENT_FALLBACK} when the key is absent
	 * or blank - see {@link #getDefaultEnvironmentStartupNotice()} for the corresponding INFO message in
	 * that case. Unlike {@link #getDefaultFileFormat()}, any non-blank value is accepted as-is (trimmed,
	 * not upper-cased) - there is no fixed set of valid environments to validate against.
	 */
	public String getDefaultEnvironment() {
		resolveDefaultEnvironment();
		return defaultEnvironment;
	}

	/**
	 * An INFO message to show once at startup, or {@code null} when {@code DefaultEnvironment} was
	 * present in the INI file (nothing to report).
	 */
	public String getDefaultEnvironmentStartupNotice() {
		resolveDefaultEnvironment();
		return defaultEnvironmentStartupNotice;
	}

	/**
	 * Populates {@link #defaultEnvironment} and {@link #defaultEnvironmentStartupNotice} from
	 * {@link #defaultEnvironmentRaw}, the first time either is requested. Idempotent: does nothing on
	 * later calls, since {@link #defaultEnvironment} is only ever {@code null} before the first
	 * resolution.
	 */
	private void resolveDefaultEnvironment() {
		if (defaultEnvironment != null) {
			return;
		}
		if (StringUtils.isBlank(defaultEnvironmentRaw)) {
			defaultEnvironment = DEFAULT_ENVIRONMENT_FALLBACK;
			defaultEnvironmentStartupNotice = SpringPropertiesConfig.DEFAULT_ENVIRONMENT
					+ " not found in the INI file, using default " + DEFAULT_ENVIRONMENT_FALLBACK + ".";
		} else {
			defaultEnvironment = defaultEnvironmentRaw.trim();
		}
	}

	public boolean isProcessUnknownCommands() {
		return processUnknownCommands;
	}

	public void setProcessUnknownCommands(boolean processUnknownCommands) {
		this.processUnknownCommands = processUnknownCommands;
	}

	public boolean isAutoCommit() {
		return autoCommit;
	}

	public void setAutoCommit(boolean autoCommit) {
		this.autoCommit = autoCommit;
	}

	public String getLibraryPath() {
		return libraryPath;
	}

	public void setLibraryPath(String libraryPath) {
		this.libraryPath = libraryPath;
	}

	/**
	 * Root folder for the {@code SCRIPT *} command family, resolved from {@code Scripts} in
	 * {@code BroadSQL.ini}. Falls back to {@link #DEFAULT_SCRIPTS_PATH_FALLBACK} (a plain relative
	 * {@code scripts}, resolved against the working directory the same way any other relative catalog
	 * path is - see {@code FileCatalog.assignPath()}) when the key is absent or blank, since every
	 * distribution already ships a matching {@code scripts} folder next to {@code sqllib} - see
	 * {@link #getScriptsPathStartupNotice()} for the corresponding INFO message in that case.
	 */
	public String getScriptsPath() {
		resolveScriptsPath();
		return scriptsPath;
	}

	public void setScriptsPath(String scriptsPath) {
		this.scriptsPath = scriptsPath;
	}

	/**
	 * An INFO message to show once at startup, or {@code null} when {@code Scripts} was present in
	 * the INI file (nothing to report).
	 */
	public String getScriptsPathStartupNotice() {
		resolveScriptsPath();
		return scriptsPathStartupNotice;
	}

	/**
	 * Populates {@link #scriptsPath} and {@link #scriptsPathStartupNotice} from
	 * {@link #scriptsPathRaw}, the first time either is requested. Idempotent: does nothing on later
	 * calls, since {@link #scriptsPath} is only ever {@code null} before the first resolution (or
	 * after test code calls {@link #setScriptsPath(String)} directly, which is the point - it bypasses
	 * this resolution entirely).
	 */
	private void resolveScriptsPath() {
		if (scriptsPath != null) {
			return;
		}
		if (StringUtils.isBlank(scriptsPathRaw)) {
			scriptsPath = DEFAULT_SCRIPTS_PATH_FALLBACK;
			scriptsPathStartupNotice = SpringPropertiesConfig.SQL_SCRIPTS
					+ " not found in the INI file, using default " + DEFAULT_SCRIPTS_PATH_FALLBACK + ".";
		} else {
			scriptsPath = scriptsPathRaw.trim();
		}
	}

	/**
	 * Root folder for the {@code JS} command family, resolved from {@code JsScripts} in
	 * {@code BroadSQL.ini}. Falls back to {@link #DEFAULT_JSSCRIPTS_PATH_FALLBACK} when the key is
	 * absent or blank - see {@link #getJsScriptsPathStartupNotice()} for the corresponding INFO
	 * message in that case. Kept separate from {@link #getScriptsPath()} - see the Javadoc on
	 * {@link SpringPropertiesConfig#SQL_JS_SCRIPTS}.
	 */
	public String getJsScriptsPath() {
		resolveJsScriptsPath();
		return jsScriptsPath;
	}

	public void setJsScriptsPath(String jsScriptsPath) {
		this.jsScriptsPath = jsScriptsPath;
	}

	/**
	 * An INFO message to show once at startup, or {@code null} when {@code JsScripts} was present in
	 * the INI file (nothing to report).
	 */
	public String getJsScriptsPathStartupNotice() {
		resolveJsScriptsPath();
		return jsScriptsPathStartupNotice;
	}

	private void resolveJsScriptsPath() {
		if (jsScriptsPath != null) {
			return;
		}
		if (StringUtils.isBlank(jsScriptsPathRaw)) {
			jsScriptsPath = DEFAULT_JSSCRIPTS_PATH_FALLBACK;
			jsScriptsPathStartupNotice = SpringPropertiesConfig.SQL_JS_SCRIPTS
					+ " not found in the INI file, using default " + DEFAULT_JSSCRIPTS_PATH_FALLBACK + ".";
		} else {
			jsScriptsPath = jsScriptsPathRaw.trim();
		}
	}

	/**
	 * Whether {@code SCRIPT LIST}/{@code LIB LIST} also show subfolder entries by default, resolved
	 * from {@code ListSubfolders} in {@code BroadSQL.ini}. Defaults to {@code false} (top-level entries
	 * only) when the key is absent - unlike {@link #getScriptsPath()} and friends, there is no startup
	 * notice for this one: a missing key is a perfectly normal, silent "keep the default" case, not
	 * something worth flagging.
	 */
	public boolean isListSubfolders() {
		return listSubfolders;
	}

	public void setListSubfolders(boolean listSubfolders) {
		this.listSubfolders = listSubfolders;
	}

	public String getLogFolderName() {
		return logFolderName;
	}

	public void setLogFolderName(String logFolderName) {
		this.logFolderName = logFolderName;
	}

	public boolean isLogDefaultActivated() {
		return logDefaultActivated;
	}

	public void setLogDefaultActivated(boolean logDefaultActivated) {
		this.logDefaultActivated = logDefaultActivated;
	}

	public String getLogFileName() {
		return logFileName;
	}

	public void setLogFileName(String logFileName) {
		this.logFileName = logFileName;
	}

	/*
	public String getPackageCustomExtensions() {
		return packageCustomExtensions;
	}

	public void setPackageCustomExtensions(String packageCustomExtensions) {
		this.packageCustomExtensions = packageCustomExtensions;
	}
	*/

	public String getCustomExtensionsFolder() {
		return customExtensionsFolders;
	}

	public void setCustomExtensionsFolder(String customExtensionsFolder) {
		this.customExtensionsFolders = customExtensionsFolder;
	}

	public String getWinEditPlus() {
		return winEditPlus;
	}

	public void setWinEditPlus(String winEditPlus) {
		this.winEditPlus = winEditPlus;
	}

}
