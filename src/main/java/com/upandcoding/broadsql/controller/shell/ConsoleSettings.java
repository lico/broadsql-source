package com.upandcoding.broadsql.controller.shell;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.output.DisplayMode;
import com.upandcoding.broadsql.controller.shell.style.ColorMode;
import com.upandcoding.broadsql.controller.shell.style.Theme;

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

	// Output settings - GitHub #154: bound as raw Strings (blank Spring default) rather than directly as
	// char/int, so an invalid or absent value can never abort Spring context startup (confirmed
	// empirically: a directly-@Value-bound primitive field throws UnsatisfiedDependencyException/
	// TypeMismatchException out of ApplicationContext.refresh() itself, which BroadSQL.main() constructs
	// outside its own try/catch - a single bad property such as MaxRowXLSX=format therefore used to
	// prevent the whole application from starting, not merely disable Excel export). Each is resolved
	// lazily into its typed field below, exactly like the existing DefaultFileFormat/CsvSeparator/
	// activatejline settings; an explicit setter (tests, or a caller correcting the value later) always
	// wins over raw-string resolution, same convention as apiProxyUsername/apiProxyPassword.
	@Value("${" + SpringPropertiesConfig.COL_SEP + ":}")
	private String defaultSeparatorRaw;
	@Value("${" + SpringPropertiesConfig.MAX_ROW_SCRN + ":}")
	private String maxRowsOnScreenRaw;
	@Value("${" + SpringPropertiesConfig.SCRN_SEP + ":}")
	private String onScreenSeparatorRaw;
	@Value("${" + SpringPropertiesConfig.MAX_ROW_XL + ":}")
	private String maxRowXlsxRaw;

	// GitHub #154 - the value BroadSQL has always shipped/documented for each, used only when the key is
	// missing from the INI file (not "invented from intuition" - see docs/TECHNICAL_CHANGE.md for the
	// audit backing each one): FieldsSeparator/ScreenSeparator agree between the Windows and Linux
	// templates already; MaxRowXLSX is 500000 on both; MaxRowsOnScreen differs today (Windows 100, Linux
	// 500) - Windows' value is used here, and the Linux template is aligned to match as part of this
	// sprint's template-parity pass (no code reason for the two platforms to differ).
	public static final char DEFAULT_SEPARATOR_FALLBACK = '\t';
	public static final char ON_SCREEN_SEPARATOR_FALLBACK = '|';
	public static final int MAX_ROWS_ON_SCREEN_FALLBACK = 100;
	public static final int MAX_ROW_XLSX_FALLBACK = 500000;

	// Resolved lazily from *Raw above (see resolveDefaultSeparator() etc.) - null/blank until then, same
	// "resolve once, cache" convention as defaultFileFormat. A Character/Integer wrapper (not the
	// primitive) so "not yet resolved" and "resolved to the fallback" stay distinguishable internally.
	private Character defaultSeparator;
	private String defaultSeparatorStartupNotice;
	private Integer maxRowsOnScreen;
	private String maxRowsOnScreenStartupNotice;
	private Character onScreenSeparator;
	private String onScreenSeparatorStartupNotice;
	private Integer maxRowXlsx;
	private String maxRowXlsxStartupNotice;
	// Non-null only when MaxRowXLSX is present in the INI but not a valid integer (as opposed to blank/
	// absent, silently using MAX_ROW_XLSX_FALLBACK) - GitHub #154 section 7's "feature-local failure":
	// CommandDumpTable checks this before comparing against getMaxRowXlsx() and refuses with a clear
	// configuration error instead of silently using the fallback for a threshold decision the user never
	// configured. The other five settings above do not get this treatment: unlike the XLSX row-count
	// threshold, a wrong screen separator, row limit or default default-autocommit/logging flag has no
	// comparable "silently does something the user didn't ask for" failure mode, so - per the sprint's own
	// "do not broaden the startup-critical/feature-blocking category unnecessarily" - they simply fall
	// back to their documented default, with a startup notice, like every other optional setting.
	private String maxRowXlsxInvalidValue;

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
	// GitHub #154: raw String (blank default), same reasoning as defaultSeparatorRaw et al. above - the
	// INI file's own existing comment already documents the intended default ("if autocommit not in INI
	// file, this creates an error. default autocommit=false") - the second half of that comment was
	// always the intent, the first half was the bug this sprint fixes.
	@Value("${" + SpringPropertiesConfig.AUTOCOMMIT + ":}")
	private String autoCommitRaw;
	public static final boolean AUTOCOMMIT_FALLBACK = false;
	private Boolean autoCommit;
	private String autoCommitStartupNotice;

	// SPRINT 1909S: the Scripts Library folder (setting ScriptsLibrary). Optional, so an INI file that does
	// not mention it starts fine and uses the default below. The former SqlLib, Scripts and ListSubfolders
	// keys are obsolete and are never read.
	@Value("${" + SpringPropertiesConfig.SCRIPTS_LIBRARY + ":}")
	private String scriptsLibraryPathRaw;

	// Matches the "scripts" subfolder shipped in every distribution (see deploy/); used when
	// ScriptsLibrary is absent or blank from the INI file. A relative value is located by
	// ScriptsLibrary.locateRoot(): as given, else under BroadSQL's install folder.
	public static final String DEFAULT_SCRIPTS_LIBRARY_PATH = "scripts";

	// Resolved lazily from scriptsLibraryPathRaw on first access, same reasoning as defaultEnvironment above.
	private String scriptsLibraryPath;
	// Non-null only when there is something to report at startup: ScriptsLibrary missing from the INI file.
	private String scriptsLibraryPathStartupNotice;

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

	// SPRINT XT02A (URL-Native API Execution), section 12.1 - "ON"/"OFF" (spec's exact requested
	// spelling, not true/false), so bound as a raw string and parsed case-insensitively by
	// isJLineActivated() below. SPRINT 2209C (GitHub #150): JLine is the standard console, so a missing
	// key now means ON (blank Spring default, resolved like the GitHub #154 settings); only an explicit
	// OFF selects the standard java.io.Console reader.
	@Value("${" + SpringPropertiesConfig.ACTIVATE_JLINE + ":}")
	private String activateJLineRaw;
	public static final boolean ACTIVATE_JLINE_FALLBACK = true;
	private Boolean jLineActivated;
	private String activateJLineStartupNotice;

	// SPRINT XT02B, section 14 - blank/absent default, optional override; see
	// #resolveJLineHistoryFilePath() for the exact resolution rules.
	@Value("${" + SpringPropertiesConfig.JLINE_HISTORY_FILE + ":}")
	private String jLineHistoryFileRaw;

	// SPRINT 2409K: terminal styling (color, theme) and geometry (displaymode). Raw strings resolved on
	// first access with the GitHub #154 missing/invalid convention: fallback value plus one startup notice.
	@Value("${" + SpringPropertiesConfig.COLOR + ":}")
	private String colorRaw;
	@Value("${" + SpringPropertiesConfig.THEME + ":}")
	private String themeRaw;
	@Value("${" + SpringPropertiesConfig.DISPLAY_MODE + ":}")
	private String displayModeRaw;

	// SPRINT XT02A, section 19.1 - NONE/SYSTEM/MANUAL, default NONE for backward compatibility
	// (section 19.1's own stated recommendation). Bound as a raw string; ApiProxyConfig normalizes
	// and falls back to NONE for anything else unrecognized.
	@Value("${" + SpringPropertiesConfig.API_PROXY_MODE + ":NONE}")
	private String apiProxyMode;
	@Value("${" + SpringPropertiesConfig.API_PROXY_TYPE + ":HTTP}")
	private String apiProxyType;
	@Value("${" + SpringPropertiesConfig.API_PROXY_HOST + ":}")
	private String apiProxyHost;
	@Value("${" + SpringPropertiesConfig.API_PROXY_PORT + ":}")
	private String apiProxyPort;
	@Value("${" + SpringPropertiesConfig.API_PROXY_NON_PROXY_HOSTS + ":}")
	private String apiProxyNonProxyHosts;
	// Deliberately NOT bound via @Value / Spring's own "${...}" placeholder mechanism (SPRINT XT02A
	// corrective pass, docs/TECHNICAL_CHANGE.md, 16/09/2026): the raw text may legitimately BE a
	// "${ENV:NAME}" reference (BroadSQL's own environment-variable syntax, resolved later by
	// EnvVarResolver) - if Spring's placeholder resolver ever saw that text, it would try to resolve
	// "ENV:NAME" as a second, nested Spring property of its own and fail application startup outright
	// when it (as expected) does not exist. Read directly from the raw INI file instead - see
	// #getApiProxyUsername()/#getApiProxyPassword() below - so every other @Value-bound key in this
	// class keeps Spring's normal, strict placeholder validation completely unweakened.
	private String apiProxyUsername;
	private boolean apiProxyUsernameResolved = false;
	private String apiProxyPassword;
	private boolean apiProxyPasswordResolved = false;
	private java.util.Properties rawIniProperties;

	// Log Activity - GitHub #154: raw String (blank default), same reasoning as autoCommitRaw above.
	// Windows ships FALSE, Linux ships TRUE (see docs/TECHNICAL_CHANGE.md for why that is kept as a
	// legitimate platform difference, not unified); FALSE (no logging) is the safer universal fallback
	// when the key is missing altogether from either platform's INI.
	@Value("${" + SpringPropertiesConfig.LOG_ENABLED + ":}")
	private String logDefaultActivatedRaw;
	public static final boolean LOG_DEFAULT_ACTIVATED_FALLBACK = false;
	private Boolean logDefaultActivated;
	private String logDefaultActivatedStartupNotice;
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

	/**
	 * GitHub #154: resolved from {@code FieldsSeparator}, falling back to {@link #DEFAULT_SEPARATOR_FALLBACK}
	 * when the key is missing/blank or not exactly one character - see {@link #getDefaultSeparatorStartupNotice()}
	 * for the corresponding startup message in either case.
	 */
	public char getDefaultSeparator() {
		resolveDefaultSeparator();
		return defaultSeparator;
	}

	public void setDefaultSeparator(char defaultSeparator) {
		this.defaultSeparator = defaultSeparator;
		this.defaultSeparatorStartupNotice = null;
	}

	/** Non-null only when there is something to report at startup: {@code FieldsSeparator} missing/blank or invalid (not exactly one character). */
	public String getDefaultSeparatorStartupNotice() {
		resolveDefaultSeparator();
		return defaultSeparatorStartupNotice;
	}

	private void resolveDefaultSeparator() {
		if (defaultSeparator != null) {
			return;
		}
		if (StringUtils.isEmpty(defaultSeparatorRaw)) {
			defaultSeparator = DEFAULT_SEPARATOR_FALLBACK;
			defaultSeparatorStartupNotice = SpringPropertiesConfig.COL_SEP + " not found in the INI file, using default '\\t' (tab).";
		} else if (defaultSeparatorRaw.length() == 1) {
			defaultSeparator = defaultSeparatorRaw.charAt(0);
		} else {
			defaultSeparator = DEFAULT_SEPARATOR_FALLBACK;
			defaultSeparatorStartupNotice = "Invalid BroadSQL setting '" + SpringPropertiesConfig.COL_SEP + "': value '" + defaultSeparatorRaw
					+ "' is not a single character. Expected exactly one character (e.g. \\t). Using default '\\t' (tab).";
		}
	}

	/**
	 * GitHub #154: resolved from {@code MaxRowsOnScreen}, falling back to {@link #MAX_ROWS_ON_SCREEN_FALLBACK}
	 * when the key is missing/blank or not a valid integer - see {@link #getMaxRowsOnScreenStartupNotice()}.
	 */
	public int getMaxRowsOnScreen() {
		resolveMaxRowsOnScreen();
		return maxRowsOnScreen;
	}

	public void setMaxRowsOnScreen(int maxRowsOnScreen) {
		this.maxRowsOnScreen = maxRowsOnScreen;
		this.maxRowsOnScreenStartupNotice = null;
	}

	/** Non-null only when there is something to report at startup: {@code MaxRowsOnScreen} missing/blank or invalid. */
	public String getMaxRowsOnScreenStartupNotice() {
		resolveMaxRowsOnScreen();
		return maxRowsOnScreenStartupNotice;
	}

	private void resolveMaxRowsOnScreen() {
		if (maxRowsOnScreen != null) {
			return;
		}
		if (StringUtils.isBlank(maxRowsOnScreenRaw)) {
			maxRowsOnScreen = MAX_ROWS_ON_SCREEN_FALLBACK;
			maxRowsOnScreenStartupNotice = SpringPropertiesConfig.MAX_ROW_SCRN + " not found in the INI file, using default " + MAX_ROWS_ON_SCREEN_FALLBACK + ".";
			return;
		}
		try {
			maxRowsOnScreen = Integer.parseInt(maxRowsOnScreenRaw.trim());
		} catch (NumberFormatException e) {
			maxRowsOnScreen = MAX_ROWS_ON_SCREEN_FALLBACK;
			maxRowsOnScreenStartupNotice = "Invalid BroadSQL setting '" + SpringPropertiesConfig.MAX_ROW_SCRN + "': value '" + maxRowsOnScreenRaw
					+ "' is not a valid integer. Expected a non-negative integer (0 = display all). Using default " + MAX_ROWS_ON_SCREEN_FALLBACK + ".";
		}
	}

	/**
	 * GitHub #154: resolved from {@code ScreenSeparator}, falling back to {@link #ON_SCREEN_SEPARATOR_FALLBACK}
	 * when the key is missing/blank or not exactly one character - see {@link #getOnScreenSeparatorStartupNotice()}.
	 */
	public char getOnScreenSeparator() {
		resolveOnScreenSeparator();
		return onScreenSeparator;
	}

	public void setOnScreenSeparator(char onScreenSeparator) {
		this.onScreenSeparator = onScreenSeparator;
		this.onScreenSeparatorStartupNotice = null;
	}

	/** Non-null only when there is something to report at startup: {@code ScreenSeparator} missing/blank or invalid (not exactly one character). */
	public String getOnScreenSeparatorStartupNotice() {
		resolveOnScreenSeparator();
		return onScreenSeparatorStartupNotice;
	}

	private void resolveOnScreenSeparator() {
		if (onScreenSeparator != null) {
			return;
		}
		if (StringUtils.isEmpty(onScreenSeparatorRaw)) {
			onScreenSeparator = ON_SCREEN_SEPARATOR_FALLBACK;
			onScreenSeparatorStartupNotice = SpringPropertiesConfig.SCRN_SEP + " not found in the INI file, using default '|'.";
		} else if (onScreenSeparatorRaw.length() == 1) {
			onScreenSeparator = onScreenSeparatorRaw.charAt(0);
		} else {
			onScreenSeparator = ON_SCREEN_SEPARATOR_FALLBACK;
			onScreenSeparatorStartupNotice = "Invalid BroadSQL setting '" + SpringPropertiesConfig.SCRN_SEP + "': value '" + onScreenSeparatorRaw
					+ "' is not a single character. Expected exactly one character (e.g. |). Using default '|'.";
		}
	}

	/**
	 * GitHub #154: resolved from {@code MaxRowXLSX}, falling back to {@link #MAX_ROW_XLSX_FALLBACK} when
	 * the key is missing/blank. Unlike the other settings in this group, an <b>invalid</b> (present but
	 * unparseable) value does not silently fall back here - see {@link #getMaxRowXlsxInvalidValue()} and
	 * {@code CommandDumpTable}, which checks that first and refuses with a clear configuration error
	 * rather than silently applying the XLSX/text-file row-count threshold the user never configured
	 * (section 7's "feature-local failure" example is this exact setting).
	 */
	public int getMaxRowXlsx() {
		resolveMaxRowXlsx();
		return maxRowXlsx;
	}

	public void setMaxRowXlsx(int maxRowXlsx) {
		this.maxRowXlsx = maxRowXlsx;
		this.maxRowXlsxStartupNotice = null;
		this.maxRowXlsxInvalidValue = null;
	}

	/** Non-null only when there is something to report at startup: {@code MaxRowXLSX} missing/blank or invalid. */
	public String getMaxRowXlsxStartupNotice() {
		resolveMaxRowXlsx();
		return maxRowXlsxStartupNotice;
	}

	/** Non-null only when {@code MaxRowXLSX} is present in the INI file but not a valid integer - GitHub #154. */
	public String getMaxRowXlsxInvalidValue() {
		resolveMaxRowXlsx();
		return maxRowXlsxInvalidValue;
	}

	private void resolveMaxRowXlsx() {
		if (maxRowXlsx != null) {
			return;
		}
		if (StringUtils.isBlank(maxRowXlsxRaw)) {
			maxRowXlsx = MAX_ROW_XLSX_FALLBACK;
			maxRowXlsxStartupNotice = SpringPropertiesConfig.MAX_ROW_XL + " not found in the INI file, using default " + MAX_ROW_XLSX_FALLBACK + ".";
			return;
		}
		try {
			maxRowXlsx = Integer.parseInt(maxRowXlsxRaw.trim());
		} catch (NumberFormatException e) {
			maxRowXlsxInvalidValue = maxRowXlsxRaw;
			maxRowXlsx = MAX_ROW_XLSX_FALLBACK;
			maxRowXlsxStartupNotice = "Invalid BroadSQL setting '" + SpringPropertiesConfig.MAX_ROW_XL + "': value '" + maxRowXlsxRaw
					+ "' is not a valid integer. Expected a positive integer. DUMP/EXPORT's Excel-vs-text-file row-count "
					+ "threshold will be unavailable until this is corrected.";
		}
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
	 * GitHub #152: the exact raw {@code DefaultFileFormat} value when it is present in the INI file but
	 * not one of {@link #SUPPORTED_FILE_FORMATS} - {@code null} when the key is blank/absent (a
	 * deliberate, silent default to {@link #DEFAULT_FILE_FORMAT_FALLBACK} in that case, established
	 * 27/08/2026 and unchanged here - see {@link #getDefaultFileFormat()}) or already valid.
	 *
	 * <p>Distinct from {@link #getDefaultFileFormatStartupNotice()}, which is non-null in both the
	 * blank/absent and the present-but-invalid case and is phrased for DUMP/EXPORT's own silent-fallback
	 * behavior ("... ignored, using default ODS.") - misleading if reused as-is for PULL's implicit
	 * {@code AS <format>} default, which does not fall back to ODS in this one case: an explicit, present
	 * but unrecognized value is treated as a real misconfiguration worth failing on, whereas an absent key
	 * is business as usual (see {@code CommandPull#execute}, which uses this method to decide between the
	 * two).
	 */
	public String getDefaultFileFormatInvalidValue() {
		resolveDefaultFileFormat();
		return StringUtils.isNotBlank(defaultFileFormatRaw) && defaultFileFormatStartupNotice != null ? defaultFileFormatRaw : null;
	}

	/**
	 * Test/programmatic seam, same convention as {@link #setActivateJLineRaw}/{@link #setJLineHistoryFileRaw}/
	 * {@link #setScriptHistoryVaultRaw}: {@code @Value} only populates this field through Spring, so a
	 * plain {@code new ConsoleSettings()} used outside a Spring context (every unit test) needs a setter
	 * to exercise a specific {@code DefaultFileFormat} value. Must be called before the first call to
	 * {@link #getDefaultFileFormat()}/{@link #getDefaultFileFormatStartupNotice()}/
	 * {@link #getDefaultFileFormatInvalidValue()} on this instance - like the rest of this class's INI
	 * settings, resolution is memoized on first access ({@link #resolveDefaultFileFormat()}), so a later
	 * call has no effect.
	 */
	public void setDefaultFileFormatRaw(String defaultFileFormatRaw) {
		this.defaultFileFormatRaw = defaultFileFormatRaw;
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

	/** SPRINT 2309T (#165): the two supported {@code CsvSeparator} values. */
	public static final String CSV_SEPARATOR_COMMA = "COMMA";
	public static final String CSV_SEPARATOR_SEMICOLON = "SEMICOLON";

	/**
	 * The field separator character resolved from {@code CsvSeparator} in {@code BroadSQL.ini}: {@code ,}
	 * for {@code COMMA}, {@code ;} for {@code SEMICOLON} (case-insensitive; the literal characters
	 * {@code ,}/{@code ;} written by earlier INI templates are read the same way), {@link #CSV_SEPARATOR_FALLBACK}
	 * (semicolon) when the key is absent or blank. Any other value keeps its former reading, its first
	 * character, so {@code LOAD} (which reads CSV files with this separator) is unaffected; the CSV export
	 * format refuses such a value instead, see {@link #getCsvExportSeparator()}. Independent of
	 * {@link #getDefaultSeparator()}/{@code SET SEP} - see docs/PULL_TO_TEXT.md, "CSV separator".
	 */
	public char getCsvSeparator() {
		if (csvSeparator == null) {
			Character named = parseCsvSeparator(csvSeparatorRaw);
			csvSeparator = StringUtils.isBlank(csvSeparatorRaw) ? CSV_SEPARATOR_FALLBACK : named != null ? named : csvSeparatorRaw.trim().charAt(0);
		}
		return csvSeparator;
	}

	/** {@code ,} or {@code ;} for a supported {@code CsvSeparator} value, {@code null} otherwise (including blank). */
	static Character parseCsvSeparator(String raw) {
		String value = StringUtils.trimToEmpty(raw);
		if (CSV_SEPARATOR_COMMA.equalsIgnoreCase(value) || ",".equals(value)) {
			return ',';
		}
		if (CSV_SEPARATOR_SEMICOLON.equalsIgnoreCase(value) || ";".equals(value)) {
			return ';';
		}
		return null;
	}

	/**
	 * SPRINT 2309T (#165): the separator of the {@code CSV} export format ({@code DUMP}/{@code PULL ... AS
	 * CSV}) - comma or semicolon only, deterministic, never taken from {@code SET SEPARATOR}. A present but
	 * unsupported {@code CsvSeparator} value (e.g. {@code |}) fails the CSV export clearly, naming the
	 * setting, rather than writing a file with a delimiter the format does not support.
	 */
	public char getCsvExportSeparator() throws BroadSQLException {
		String invalid = getCsvSeparatorInvalidValue();
		if (invalid != null) {
			throw new BroadSQLException("AS CSV cannot be used: BroadSQL setting '" + SpringPropertiesConfig.CSV_SEPARATOR + "' is set to '" + invalid
					+ "', which is not supported. Set " + SpringPropertiesConfig.CSV_SEPARATOR + "=" + CSV_SEPARATOR_COMMA + " or "
					+ SpringPropertiesConfig.CSV_SEPARATOR + "=" + CSV_SEPARATOR_SEMICOLON + " in BroadSQL.ini, or use AS TEXT.");
		}
		return getCsvSeparator();
	}

	/** The configured {@code CsvSeparator} value when it is neither comma nor semicolon, {@code null} when it is valid or absent. */
	public String getCsvSeparatorInvalidValue() {
		char separator = getCsvSeparator();
		if (separator == ',' || separator == ';') {
			return null;
		}
		return StringUtils.isNotBlank(csvSeparatorRaw) ? csvSeparatorRaw.trim() : String.valueOf(separator);
	}

	/** Non-null only when there is something to report at startup: {@code CsvSeparator} present but unsupported. */
	public String getCsvSeparatorStartupNotice() {
		String invalid = getCsvSeparatorInvalidValue();
		return invalid == null ? null
				: SpringPropertiesConfig.CSV_SEPARATOR + " value '" + invalid + "' in the INI file is not supported (expected "
						+ CSV_SEPARATOR_COMMA + " or " + CSV_SEPARATOR_SEMICOLON + "): CSV exports are refused until it is corrected.";
	}

	public void setCsvSeparator(char csvSeparator) {
		this.csvSeparator = csvSeparator;
	}

	/** Sets the raw {@code CsvSeparator} INI value as Spring would, re-resolved on next access - used by tests. */
	public void setCsvSeparatorRaw(String csvSeparatorRaw) {
		this.csvSeparatorRaw = csvSeparatorRaw;
		this.csvSeparator = null;
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

	/**
	 * GitHub #154: resolved from {@code Autocommit}, falling back to {@link #AUTOCOMMIT_FALLBACK} (the
	 * INI file's own long-documented intended default) when the key is missing/blank or not exactly
	 * {@code true}/{@code false} - see {@link #getAutoCommitStartupNotice()}.
	 */
	public boolean isAutoCommit() {
		resolveAutoCommit();
		return autoCommit;
	}

	public void setAutoCommit(boolean autoCommit) {
		this.autoCommit = autoCommit;
		this.autoCommitStartupNotice = null;
	}

	/** Non-null only when there is something to report at startup: {@code Autocommit} missing/blank or invalid. */
	public String getAutoCommitStartupNotice() {
		resolveAutoCommit();
		return autoCommitStartupNotice;
	}

	private void resolveAutoCommit() {
		if (autoCommit != null) {
			return;
		}
		if (StringUtils.isBlank(autoCommitRaw)) {
			autoCommit = AUTOCOMMIT_FALLBACK;
			autoCommitStartupNotice = SpringPropertiesConfig.AUTOCOMMIT + " not found in the INI file, using default " + AUTOCOMMIT_FALLBACK + ".";
			return;
		}
		String trimmed = autoCommitRaw.trim();
		if ("true".equalsIgnoreCase(trimmed)) {
			autoCommit = true;
		} else if ("false".equalsIgnoreCase(trimmed)) {
			autoCommit = false;
		} else {
			// Boolean.parseBoolean() never throws - anything not exactly "true" silently becomes false,
			// which would hide a real typo (e.g. "1", "yes") behind a plausible-looking value. Validated
			// explicitly instead so a genuine mistake gets a startup notice rather than silent autocommit=false.
			autoCommit = AUTOCOMMIT_FALLBACK;
			autoCommitStartupNotice = "Invalid BroadSQL setting '" + SpringPropertiesConfig.AUTOCOMMIT + "': value '" + autoCommitRaw
					+ "' is not true or false. Using default " + AUTOCOMMIT_FALLBACK + ".";
		}
	}

	/**
	 * The Scripts Library folder, resolved from {@code ScriptsLibrary} in {@code BroadSQL.ini}. Falls
	 * back to {@link #DEFAULT_SCRIPTS_LIBRARY_PATH} when the key is absent or blank - see
	 * {@link #getScriptsLibraryPathStartupNotice()} for the corresponding INFO message in that case.
	 */
	public String getScriptsLibraryPath() {
		resolveScriptsLibraryPath();
		return scriptsLibraryPath;
	}

	public void setScriptsLibraryPath(String scriptsLibraryPath) {
		this.scriptsLibraryPath = scriptsLibraryPath;
	}

	/**
	 * An INFO message to show once at startup, or {@code null} when {@code ScriptsLibrary} was present
	 * in the INI file (nothing to report).
	 */
	public String getScriptsLibraryPathStartupNotice() {
		resolveScriptsLibraryPath();
		return scriptsLibraryPathStartupNotice;
	}

	/**
	 * Populates {@link #scriptsLibraryPath} and its notice from {@link #scriptsLibraryPathRaw}, the first
	 * time either is requested. Idempotent; test code that calls
	 * {@link #setScriptsLibraryPath(String)} bypasses this resolution entirely.
	 */
	private void resolveScriptsLibraryPath() {
		if (scriptsLibraryPath != null) {
			return;
		}
		if (StringUtils.isBlank(scriptsLibraryPathRaw)) {
			scriptsLibraryPath = DEFAULT_SCRIPTS_LIBRARY_PATH;
			scriptsLibraryPathStartupNotice = SpringPropertiesConfig.SCRIPTS_LIBRARY
					+ " not found in the INI file, using default " + DEFAULT_SCRIPTS_LIBRARY_PATH + ".";
		} else {
			scriptsLibraryPath = scriptsLibraryPathRaw.trim();
		}
	}

	/**
	 * Root folder for the {@code JS} command family, resolved from {@code JsScripts} in
	 * {@code BroadSQL.ini}. Falls back to {@link #DEFAULT_JSSCRIPTS_PATH_FALLBACK} when the key is
	 * absent or blank - see {@link #getJsScriptsPathStartupNotice()} for the corresponding INFO
	 * message in that case. Kept separate from the Scripts Library (SPRINT 1909S: JS is not part of it) - see the Javadoc on
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
	 * SPRINT 2209C (GitHub #150): {@code false} only for an explicit {@code activatejline=OFF}
	 * (case-insensitive); {@code ON}, a missing/blank key, and an unrecognized value are all ON
	 * ({@link #ACTIVATE_JLINE_FALLBACK}), the last two with a startup notice (see
	 * {@link #getActivateJLineStartupNotice()}), the same missing/invalid convention as the GitHub #154
	 * settings. When this is {@code true}, {@code BroadSQL.main} installs {@code JLineConsoleLineReader};
	 * if JLine then fails to initialize, it warns once and keeps the standard console.
	 */
	public boolean isJLineActivated() {
		resolveJLineActivated();
		return jLineActivated;
	}

	public void setActivateJLineRaw(String activateJLineRaw) {
		this.activateJLineRaw = activateJLineRaw;
		this.jLineActivated = null;
		this.activateJLineStartupNotice = null;
	}

	/** SPRINT 2209C: {@code null} unless {@code activatejline} is missing from the INI file or holds a value other than ON/OFF. */
	public String getActivateJLineStartupNotice() {
		resolveJLineActivated();
		return activateJLineStartupNotice;
	}

	private void resolveJLineActivated() {
		if (jLineActivated != null) {
			return;
		}
		String fallback = ACTIVATE_JLINE_FALLBACK ? "ON" : "OFF";
		if (StringUtils.isBlank(activateJLineRaw)) {
			jLineActivated = ACTIVATE_JLINE_FALLBACK;
			activateJLineStartupNotice = SpringPropertiesConfig.ACTIVATE_JLINE + " not found in the INI file, using default " + fallback + ".";
			return;
		}
		String trimmed = activateJLineRaw.trim();
		if ("ON".equalsIgnoreCase(trimmed)) {
			jLineActivated = true;
		} else if ("OFF".equalsIgnoreCase(trimmed)) {
			jLineActivated = false;
		} else {
			jLineActivated = ACTIVATE_JLINE_FALLBACK;
			activateJLineStartupNotice = "Invalid BroadSQL setting '" + SpringPropertiesConfig.ACTIVATE_JLINE + "': value '" + activateJLineRaw
					+ "' is not ON or OFF. Using default " + fallback + ".";
		}
	}

	// ---- SPRINT 2409K: color / theme / displaymode ----

	public static final ColorMode COLOR_FALLBACK = ColorMode.AUTO;
	public static final DisplayMode DISPLAY_MODE_FALLBACK = DisplayMode.AUTO;

	/** {@code color}: AUTO (default), ON or OFF, not case-sensitive; missing or invalid means AUTO. */
	public ColorMode getColorMode() {
		ColorMode parsed = ColorMode.parse(colorRaw);
		return parsed != null ? parsed : COLOR_FALLBACK;
	}

	/** {@code theme}: a built-in theme name, not case-sensitive; missing or unknown means {@link Theme#DEFAULT}. */
	public Theme getTheme() {
		Theme named = Theme.named(themeRaw);
		return named != null ? named : Theme.named(Theme.DEFAULT);
	}

	/** {@code displaymode}: AUTO (default), COMPACT, NORMAL or WIDE, not case-sensitive; missing or invalid means AUTO. */
	public DisplayMode getDisplayMode() {
		DisplayMode parsed = DisplayMode.parse(displayModeRaw);
		return parsed != null ? parsed : DISPLAY_MODE_FALLBACK;
	}

	public void setColorRaw(String colorRaw) {
		this.colorRaw = colorRaw;
	}

	public void setThemeRaw(String themeRaw) {
		this.themeRaw = themeRaw;
	}

	public void setDisplayModeRaw(String displayModeRaw) {
		this.displayModeRaw = displayModeRaw;
	}

	/** {@code null} unless {@code color} is missing from the INI file or holds a value other than AUTO/ON/OFF. */
	public String getColorStartupNotice() {
		return enumNotice(SpringPropertiesConfig.COLOR, colorRaw, ColorMode.parse(colorRaw) != null, "AUTO, ON or OFF", COLOR_FALLBACK.name());
	}

	/** {@code null} unless {@code theme} is missing from the INI file or names no built-in theme. */
	public String getThemeStartupNotice() {
		return enumNotice(SpringPropertiesConfig.THEME, themeRaw, Theme.named(themeRaw) != null, "one of " + String.join(", ", Theme.names()), Theme.DEFAULT);
	}

	/** {@code null} unless {@code displaymode} is missing from the INI file or holds a value other than AUTO/COMPACT/NORMAL/WIDE. */
	public String getDisplayModeStartupNotice() {
		return enumNotice(SpringPropertiesConfig.DISPLAY_MODE, displayModeRaw, DisplayMode.parse(displayModeRaw) != null, "AUTO, COMPACT, NORMAL or WIDE",
				DISPLAY_MODE_FALLBACK.name());
	}

	/** The startup notice wording used by every other enumerated setting (see {@link #getActivateJLineStartupNotice()}). */
	private static String enumNotice(String key, String raw, boolean valid, String allowed, String fallback) {
		if (StringUtils.isBlank(raw)) {
			return key + " not found in the INI file, using default " + fallback + ".";
		}
		if (!valid) {
			return "Invalid BroadSQL setting '" + key + "': value '" + raw + "' is not " + allowed + ". Using default " + fallback + ".";
		}
		return null;
	}

	public String getJLineHistoryFileRaw() {
		return jLineHistoryFileRaw;
	}

	public void setJLineHistoryFileRaw(String jLineHistoryFileRaw) {
		this.jLineHistoryFileRaw = jLineHistoryFileRaw;
	}

	/**
	 * SPRINT XT02B, section 14 - resolves where persistent JLine history is stored:
	 * <ul>
	 * <li>blank/absent {@code jlinehistoryfile} -&gt; {@code ${user.home}/.broadsql/history} (a new,
	 * first per-OS-user state convention for this app - history is personal, session-continuity data,
	 * so a shared install must never mix or leak one OS user's typed commands to another);</li>
	 * <li>an <b>absolute</b> configured path -&gt; used exactly as given;</li>
	 * <li>a <b>relative</b> configured path -&gt; resolved <b>under</b> {@code ${user.home}/.broadsql/}
	 * (e.g. {@code jlinehistoryfile=myhistory} means {@code ${user.home}/.broadsql/myhistory}) - never
	 * against the BroadSQL installation directory, the process working directory, or the directory
	 * containing {@code BroadSQL.ini}, since that would defeat the whole point of this being per-user.</li>
	 * </ul>
	 * Does not create the directory - {@code JLineConsoleLineReader} does that, with its own graceful
	 * fallback to in-memory history if it can't.
	 */
	public Path resolveJLineHistoryFilePath() {
		Path defaultDir = Paths.get(System.getProperty("user.home"), ".broadsql");
		if (StringUtils.isBlank(jLineHistoryFileRaw)) {
			return defaultDir.resolve("history");
		}
		Path configured = Paths.get(jLineHistoryFileRaw.trim());
		return configured.isAbsolute() ? configured : defaultDir.resolve(configured);
	}

	// SPRINT 0917-01 (Script Library, Editor and Version History) - optional override for the
	// filesystem revision vault's root folder. Blank/absent default, same "empty Spring default,
	// resolved and defaulted in code" reasoning as jLineHistoryFileRaw above.
	@Value("${" + SpringPropertiesConfig.SCRIPT_HISTORY_VAULT + ":}")
	private String scriptHistoryVaultRaw;

	public String getScriptHistoryVaultRaw() {
		return scriptHistoryVaultRaw;
	}

	public void setScriptHistoryVaultRaw(String scriptHistoryVaultRaw) {
		this.scriptHistoryVaultRaw = scriptHistoryVaultRaw;
	}

	/**
	 * SPRINT 0917-01 - resolves where the Script Library's filesystem revision vault stores its
	 * per-asset history (index, manifests and full-text revision snapshots). Sibling to
	 * {@link #resolveJLineHistoryFilePath()}, with the exact same three-way resolution rule (see that
	 * method's own Javadoc for the full reasoning: personal per-OS-user state, never the installation
	 * directory or process working directory), except the default subfolder is {@code script-history}
	 * - deliberately not {@code history}, so it never collides with the pre-existing JLine history file
	 * this method's sibling already resolves under the same {@code ${user.home}/.broadsql/} root:
	 * <ul>
	 * <li>blank/absent {@code scripthistoryvault} -&gt; {@code ${user.home}/.broadsql/script-history};</li>
	 * <li>an <b>absolute</b> configured path -&gt; used exactly as given;</li>
	 * <li>a <b>relative</b> configured path -&gt; resolved under {@code ${user.home}/.broadsql/}.</li>
	 * </ul>
	 * Does not create the directory - {@code RevisionVault} does that lazily, the same way
	 * {@code JLineConsoleLineReader} does for the JLine history file.
	 */
	public Path resolveScriptHistoryVaultPath() {
		Path defaultDir = Paths.get(System.getProperty("user.home"), ".broadsql");
		if (StringUtils.isBlank(scriptHistoryVaultRaw)) {
			return defaultDir.resolve("script-history");
		}
		Path configured = Paths.get(scriptHistoryVaultRaw.trim());
		return configured.isAbsolute() ? configured : defaultDir.resolve(configured);
	}

	public String getApiProxyMode() {
		return apiProxyMode;
	}

	public void setApiProxyMode(String apiProxyMode) {
		this.apiProxyMode = apiProxyMode;
	}

	public String getApiProxyType() {
		return apiProxyType;
	}

	public void setApiProxyType(String apiProxyType) {
		this.apiProxyType = apiProxyType;
	}

	public String getApiProxyHost() {
		return apiProxyHost;
	}

	public void setApiProxyHost(String apiProxyHost) {
		this.apiProxyHost = apiProxyHost;
	}

	public String getApiProxyPort() {
		return apiProxyPort;
	}

	public void setApiProxyPort(String apiProxyPort) {
		this.apiProxyPort = apiProxyPort;
	}

	public String getApiProxyNonProxyHosts() {
		return apiProxyNonProxyHosts;
	}

	public void setApiProxyNonProxyHosts(String apiProxyNonProxyHosts) {
		this.apiProxyNonProxyHosts = apiProxyNonProxyHosts;
	}

	/**
	 * Lazily resolved from the raw INI file on first access, bypassing Spring's placeholder mechanism
	 * entirely (see the field's own javadoc, and {@link #rawIniProperties()}) - unless a caller
	 * (production startup never does; a test does, via {@link #setApiProxyUsername}) has already set
	 * an explicit value, which always wins and skips the file read.
	 */
	public String getApiProxyUsername() {
		if (!apiProxyUsernameResolved) {
			apiProxyUsername = rawIniProperties().getProperty(SpringPropertiesConfig.API_PROXY_USERNAME);
			apiProxyUsernameResolved = true;
		}
		return apiProxyUsername;
	}

	public void setApiProxyUsername(String apiProxyUsername) {
		this.apiProxyUsername = apiProxyUsername;
		this.apiProxyUsernameResolved = true;
	}

	/** See {@link #getApiProxyUsername()} - identical raw-file, Spring-bypassing resolution. */
	public String getApiProxyPassword() {
		if (!apiProxyPasswordResolved) {
			apiProxyPassword = rawIniProperties().getProperty(SpringPropertiesConfig.API_PROXY_PASSWORD);
			apiProxyPasswordResolved = true;
		}
		return apiProxyPassword;
	}

	public void setApiProxyPassword(String apiProxyPassword) {
		this.apiProxyPassword = apiProxyPassword;
		this.apiProxyPasswordResolved = true;
	}

	/**
	 * Loads {@link #iniFileName} as a plain {@link java.util.Properties} file, once, lazily - deliberately
	 * NOT through Spring's {@code Environment}/placeholder machinery, so a value's raw {@code ${...}} text
	 * (specifically {@code apiproxyusername}/{@code apiproxypassword}'s possible {@code ${ENV:NAME}}) is
	 * never touched by Spring's recursive placeholder resolution. Plain {@code Properties.load} does no
	 * placeholder substitution of its own either - the text comes back exactly as written. A missing/
	 * unreadable file yields an empty {@link java.util.Properties} (same effect as a missing key - the two
	 * getters above return {@code null}), not an exception - this mirrors every other optional INI key in
	 * this class.
	 */
	private java.util.Properties rawIniProperties() {
		if (rawIniProperties == null) {
			rawIniProperties = new java.util.Properties();
			if (StringUtils.isNotBlank(iniFileName)) {
				try (java.io.InputStream in = new java.io.FileInputStream(iniFileName)) {
					rawIniProperties.load(in);
				} catch (java.io.IOException e) {
					// Left empty - see javadoc above.
				}
			}
		}
		return rawIniProperties;
	}

	public String getLogFolderName() {
		return logFolderName;
	}

	public void setLogFolderName(String logFolderName) {
		this.logFolderName = logFolderName;
	}

	/**
	 * GitHub #154: resolved from {@code IsLogActivated}, falling back to {@link #LOG_DEFAULT_ACTIVATED_FALLBACK}
	 * when the key is missing/blank or not exactly {@code true}/{@code false} - see
	 * {@link #getLogDefaultActivatedStartupNotice()}.
	 */
	public boolean isLogDefaultActivated() {
		resolveLogDefaultActivated();
		return logDefaultActivated;
	}

	public void setLogDefaultActivated(boolean logDefaultActivated) {
		this.logDefaultActivated = logDefaultActivated;
		this.logDefaultActivatedStartupNotice = null;
	}

	/** Non-null only when there is something to report at startup: {@code IsLogActivated} missing/blank or invalid. */
	public String getLogDefaultActivatedStartupNotice() {
		resolveLogDefaultActivated();
		return logDefaultActivatedStartupNotice;
	}

	private void resolveLogDefaultActivated() {
		if (logDefaultActivated != null) {
			return;
		}
		if (StringUtils.isBlank(logDefaultActivatedRaw)) {
			logDefaultActivated = LOG_DEFAULT_ACTIVATED_FALLBACK;
			logDefaultActivatedStartupNotice = SpringPropertiesConfig.LOG_ENABLED + " not found in the INI file, using default " + LOG_DEFAULT_ACTIVATED_FALLBACK + ".";
			return;
		}
		String trimmed = logDefaultActivatedRaw.trim();
		if ("true".equalsIgnoreCase(trimmed)) {
			logDefaultActivated = true;
		} else if ("false".equalsIgnoreCase(trimmed)) {
			logDefaultActivated = false;
		} else {
			logDefaultActivated = LOG_DEFAULT_ACTIVATED_FALLBACK;
			logDefaultActivatedStartupNotice = "Invalid BroadSQL setting '" + SpringPropertiesConfig.LOG_ENABLED + "': value '" + logDefaultActivatedRaw
					+ "' is not true or false. Using default " + LOG_DEFAULT_ACTIVATED_FALLBACK + ".";
		}
	}

	// GitHub #154 test seams - same convention as setActivateJLineRaw/setDefaultFileFormatRaw: @Value
	// only populates these fields through Spring, so a plain `new ConsoleSettings()` used outside a
	// Spring context (every unit test that is not TestConsoleSettingsIniResilience's real, minimal Spring
	// context) needs a setter to exercise a specific raw INI value for each of the six settings hardened
	// by this sprint. Must be called before that setting's first getter/notice call on this instance -
	// resolution is memoized on first access, so a later call has no effect.
	public void setDefaultSeparatorRaw(String defaultSeparatorRaw) {
		this.defaultSeparatorRaw = defaultSeparatorRaw;
	}

	public void setMaxRowsOnScreenRaw(String maxRowsOnScreenRaw) {
		this.maxRowsOnScreenRaw = maxRowsOnScreenRaw;
	}

	public void setOnScreenSeparatorRaw(String onScreenSeparatorRaw) {
		this.onScreenSeparatorRaw = onScreenSeparatorRaw;
	}

	public void setMaxRowXlsxRaw(String maxRowXlsxRaw) {
		this.maxRowXlsxRaw = maxRowXlsxRaw;
	}

	public void setAutoCommitRaw(String autoCommitRaw) {
		this.autoCommitRaw = autoCommitRaw;
	}

	public void setLogDefaultActivatedRaw(String logDefaultActivatedRaw) {
		this.logDefaultActivatedRaw = logDefaultActivatedRaw;
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
