package com.upandcoding.broadsql.dao;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.errors.SqlExecutionException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.controller.shell.scripts.PreparedSql;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptValue;
import com.upandcoding.broadsql.dao.pull.TemporalValues;
import com.upandcoding.broadsql.dao.export.TabularResultCapture;
import com.upandcoding.broadsql.dao.export.TabularResultSpool;
import com.upandcoding.broadsql.dao.extractors.IQueryExtractor;
import com.upandcoding.broadsql.dao.extractors.QueryExtractorToAccess;
import com.upandcoding.broadsql.dao.extractors.QueryExtractorToExcel2007;
import com.upandcoding.broadsql.dao.extractors.QueryExtractorToFile;
import com.upandcoding.broadsql.dao.extractors.QueryExtractorToODS;
import com.upandcoding.broadsql.dao.extractors.QueryExtractorToScreen;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.metadata.MetadataException;
import com.upandcoding.broadsql.dao.metadata.MetadataService;
import com.upandcoding.broadsql.dao.model.metadata.ColumnMetadata;
import com.upandcoding.broadsql.dao.model.metadata.PrimaryKeyMetadata;
import com.upandcoding.broadsql.dao.model.metadata.SchemaMetadata;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

public class DatabaseConnection {

	private static final Logger log = LoggerFactory.getLogger(DatabaseConnection.class);

	@Autowired
	ConsoleSettings consoleSettings;

	@Autowired
	DatabaseDefinitionsVault databaseConnectionsCollection;

	// SETTINGS
	private char sep = '\t'; // fields separator: default is tab

	// FLAGS
	// GitHub #154: no longer independently @Value-bound - this was a redundant second binding of the
	// same Autocommit INI property ConsoleSettings already resolves (resilient, see ConsoleSettings#
	// resolveAutoCommit()), and this field is always overwritten by consoleSettings.isAutoCommit() in
	// connect() below before it is ever meaningfully read - the plain false default here is dead weight,
	// not user-visible behavior. Removing the duplicate @Value also removes it as its own, independent
	// Spring-context-startup crash point for an invalid Autocommit value.
	private boolean autoCommit;

	private boolean toScreen = false;
	private IQueryExtractor extractor = null;
	private boolean hasUncommitted = false;
	private boolean debug = false;

	// Variables
	private DatabaseDefinition platform = null;
	private String dbName = null;
	private String dbVersion = null;
	private String dbDriver = null;
	private String fileName = null; // output file name if any
	protected Connection connection = null; // Used when session is kept open
	private boolean listMode = false;
	protected ShellConsole cmdLineConsole = null; // Calling command
	private int maxRowsOnScreen = 0; // Number of rows to display on screen: 0=display all

	private JdbcTemplate jdbcTemplate;
	private DriverManagerDataSource dataSource;

	// The Statement of the command currently executing, if any - used to cancel it on CTRL+C
	// without closing the connection. See docs/TODO.md ("Ameliorations de la GUI").
	private volatile Statement currentStatement;

	public DatabaseConnection() {

	}

	/**
	 * Builds a throwaway instance wired directly with the two collaborators Spring normally injects
	 * ({@link #consoleSettings}, {@link #databaseConnectionsCollection} - both package-private field
	 * injection targets, no public setter) - used for a transient connection to a platform other than
	 * the session's singleton {@code sqlDatabase} bean, e.g. {@code CrossEnvironmentRerun}'s
	 * {@code / <environment>}, without touching the singleton or its Spring-managed state. Callers must
	 * still call {@link #setPlatformCode(String)} and {@link #connect()} themselves.
	 */
	public DatabaseConnection(ConsoleSettings consoleSettings, DatabaseDefinitionsVault databaseConnectionsCollection) {
		this.consoleSettings = consoleSettings;
		this.databaseConnectionsCollection = databaseConnectionsCollection;
	}

	/**
	 * Cancels the currently executing Statement (if any), without closing the connection. Called
	 * from the CTRL+C signal handler in CommandInterpreter. Some JDBC drivers do not support
	 * Statement.cancel(); the failure is logged and otherwise ignored.
	 */
	public void cancelCurrentStatement() {
		Statement statement = currentStatement;
		if (statement != null) {
			try {
				statement.cancel();
			} catch (SQLException e) {
				log.debug("Unable to cancel the current statement: {}", e.getLocalizedMessage());
			}
		}
	}

	public DatabaseConnection(String databaseId, boolean outputToScreen, String fName) throws BroadSQLException {
		super();
		this.setPlatformCode(databaseId);
		this.setFileName(fName);
		this.toScreen = outputToScreen;
	}

	public void setPlatformCode(String databaseId) throws BroadSQLException {
		this.platform = databaseConnectionsCollection.getDatabaseConnection(databaseId);
		if (this.platform == null) {
			throw new BroadSQLException("No database connection for ID: " + databaseId);
		}
	}

	public boolean isListMode() {
		return listMode;
	}

	public void setListMode(boolean listMode) {
		this.listMode = listMode;
	}

	public ShellConsole getCmdLineConsole() {
		return cmdLineConsole;
	}

	public void setCmdLineConsole(ShellConsole cmdLineConsole) {
		this.cmdLineConsole = cmdLineConsole;
	}

	public void debug(boolean d) {
		debug = d;
	}

	public String getDbName() {
		return dbName;
	}

	public String getDbVersion() {
		return dbVersion;
	}

	public String getDbDriver() {
		return dbDriver;
	}

	public String getFileName() {
		return fileName;
	}

	public Connection getDirectConnection() {
		return connection;
	}

	public void setDirectConnection(Connection directConnection) {
		this.connection = directConnection;
	}

	/**
	 * Sets the output file for the next {@link #executeSelectQuery(String)} (used by {@code EXPORT}
	 * and {@code DUMP}) and chooses the {@link IQueryExtractor} that will write it, based on
	 * {@code fileName}'s extension (case-insensitive):
	 *
	 * <ul>
	 * <li>{@code .xlsx} - {@link QueryExtractorToExcel2007}</li>
	 * <li>{@code .xls} - not supported; throws {@link BroadSQLException}</li>
	 * <li>{@code .mdb} - {@link QueryExtractorToAccess}</li>
	 * <li>{@code .ods} - {@link QueryExtractorToODS}</li>
	 * <li>{@code .csv} - {@link QueryExtractorToFile}, with the column separator ({@link #sep})
	 * forced to a comma regardless of what it was set to before this call</li>
	 * <li>anything else (including {@code .txt}) - {@link QueryExtractorToFile}, using whatever
	 * separator is already set</li>
	 * </ul>
	 *
	 * <p>A blank or {@code null} {@code fileName} turns extraction off ({@link QueryExtractorToScreen}
	 * - results go back to the screen).
	 *
	 * @param fileName the target file path, or blank/{@code null} to write to the screen instead
	 * @throws BroadSQLException if {@code fileName} ends in {@code .xls}
	 */
	public final void setFileName(String fileName) throws BroadSQLException {
		this.fileName = fileName;

		if (StringUtils.isNotBlank(fileName)) {
			toScreen = false;
			String ext = StringUtils.substringAfterLast(fileName, ".");
			if (ext.equalsIgnoreCase("xlsx")) {
				extractor = new QueryExtractorToExcel2007();
			} else if (ext.equalsIgnoreCase("xls")) {
				// Legacy Excel 97-2003 binary format, dropped: see docs/TECHNICAL_CHANGE.md.
				throw new BroadSQLException("'.xls' export is no longer supported - use '.xlsx' instead");
			} else if (ext.equalsIgnoreCase("mdb")) {
				extractor = new QueryExtractorToAccess();
			} else if (ext.equalsIgnoreCase("ods")) {
				extractor = new QueryExtractorToODS();
			} else if (ext.equalsIgnoreCase("csv")) {
				// True CSV: comma-separated regardless of the configured FieldsSeparator/SET SEPARATOR,
				// which stays in effect for a plain .txt (or any other) extension.
				extractor = new QueryExtractorToFile();
				this.sep = ',';
			} else {
				extractor = new QueryExtractorToFile();
			}
		} else {
			extractor = new QueryExtractorToScreen();
		}

	}

	public char getSep() {
		return sep;
	}

	public void setSep(char sep) {
		this.sep = sep;
	}

	public boolean isToScreen() {
		return toScreen;
	}

	public void setToScreen(boolean toScreen) {
		this.toScreen = toScreen;
	}

	public boolean isAutoCommit() {
		return autoCommit;
	}

	public void setAutoCommit(boolean autoCommit) throws BroadSQLException {
		if (this.connection != null) {
			this.autoCommit = autoCommit;
			try {
				this.connection.setAutoCommit(this.autoCommit);
			} catch (SQLException se) {
				throw new BroadSQLException(se);
			}
		}
	}

	public int getMaxRowsOnScreen() {
		return maxRowsOnScreen;
	}

	public void setMaxRowsOnScreen(int maxRowsOnScreen) {
		this.maxRowsOnScreen = maxRowsOnScreen;
	}

	public DatabaseDefinition getPlatform() {
		return platform;
	}

	public void setPlatform(DatabaseDefinition platform) {
		this.platform = platform;
	}

	public boolean isHasUncommitted() {
		return hasUncommitted;
	}

	public void setHasUncommitted(boolean hasUncommitted) {
		this.hasUncommitted = hasUncommitted;
	}

	/**
	 * Executes a SELECT query and outputs the results on screen only
	 * 
	 * @param query(String) a well formed SQL query to be executed
	 */
	public void executeSelectQuery(String query) throws BroadSQLException {
		executeSelectQuery(query, false);
	}

	/**
	 * Executes a SELECT query and outputs the results on screen or in a file
	 * 
	 * @param query(String)  a well formed SQL query to be executed
	 * @param isAppendToFile if true, the results are appended to the output file
	 */
	public void executeSelectQuery(String query, boolean isAppendToFile) throws BroadSQLException {
		executeSelect(query, null, isAppendToFile);
	}

	/**
	 * SPRINT 0110A: {@link #executeSelectQuery(String)} for a statement with {@code ${name}} references: prepared
	 * with BroadSQL's generated markers and bound ({@link JdbcBinder}); displayed, captured, recorded and
	 * error-rendered exactly as the same statement without references (the text as written).
	 */
	public void executeSelectQuery(PreparedSql prepared) throws BroadSQLException {
		executeSelect(prepared.getOriginalText(), prepared, false);
	}

	private void executeSelect(String query, PreparedSql prepared, boolean isAppendToFile) throws BroadSQLException {

		char screenSep = consoleSettings.getOnScreenSeparator();

		if (StringUtils.isNotBlank(query)) {
			Statement statement = null;
			ResultSet results = null;
			try {
				// In case of application termination by CTRL+C
				attachShutDownHook(connection);

				// Gets data from the results set
				// ******************************
				if (prepared == null) {
					statement = connection.createStatement();
				} else {
					PreparedStatement preparedStatement = connection.prepareStatement(prepared.getJdbcText());
					statement = preparedStatement;
					JdbcBinder.bindAll(preparedStatement, prepared.getBinds());
				}
				currentStatement = statement;
				results = null;

				Instant start = Instant.now();
				// log.debug("Start: {}", start.getNano());
				results = prepared == null ? statement.executeQuery(query) : ((PreparedStatement) statement).executeQuery();

				if (extractor == null) {
					extractor = new QueryExtractorToScreen();
				}

				// SPRINT 2309T (#163): inside DUMP LIB, the script's tabular results are captured for the
				// export instead of being displayed (see TabularResultCapture) - except BroadSQL's own internal
				// lookups (LastCaptureSuppressor, e.g. HELP), which are never a script's result.
				TabularResultSpool capture = LastCaptureSuppressor.isSuppressed() ? null : TabularResultCapture.current();
				if (capture != null) {
					int captured = capture.replaceWith(results);
					if (cmdLineConsole != null) {
						cmdLineConsole.writeln(captured + " row(s) captured for DUMP LIB.");
					}
				} else if (extractor != null) {
					extractor.extract(platform, cmdLineConsole, query, results, maxRowsOnScreen, listMode, fileName,
							screenSep, sep, isAppendToFile, start);
				}

				statement.close();

			} catch (SQLException e) {
				throw new SqlExecutionException(query, e, connectionIdForErrorDisplay(), "SELECT");
			} catch (IOException e) {
				throw new BroadSQLException(e);
			} finally {
				currentStatement = null;
				try {
					if (results != null) {
						results.close();
					}
					if (statement != null) {
						statement.close();
					}
				} catch (SQLException e) {
					throw new BroadSQLException(e);
				}
			}
		} else {
			throw new BroadSQLException("query is null");
		}
	}

	/** {@code platform}'s id, or null if there is no current connection - display-only, used by {@link SqlExecutionException}. */
	private String connectionIdForErrorDisplay() {
		return platform != null ? platform.getId() : null;
	}

	/**
	 * Returns metaData of a table
	 * 
	 * @param tableName(String) name of the table
	 * @return metaData(ResultSetMetaData) a resultset of meta data
	 */
	public ResultSetMetaData getMetaData(String tableName) throws BroadSQLException {
		ResultSetMetaData metaData = null;
		String query = "SELECT * FROM " + tableName + " WHERE 1=0";
		try {
			Statement statement = connection.createStatement();
			ResultSet results = statement.executeQuery(query);
			if (results != null) {
				metaData = results.getMetaData();
			}
		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		}
		return (metaData);
	}

	/**
	 * Returns columns types of a table
	 * 
	 * @param tableName(String) name of the table
	 * @return columnType(HashMap<String, Integer>) a map containing
	 *         columnName->columnType
	 */
	public HashMap<String, Integer> getColumnType(String tableName) throws BroadSQLException {
		HashMap<String, Integer> metaDataMap = new HashMap<String, Integer>();
		ResultSetMetaData metaData = this.getMetaData(tableName);
		try {
			for (int i = 0; i < metaData.getColumnCount(); i++) {
				// String columnName = metaData.getColumnName(i + 1);
				String columnName = metaData.getColumnLabel(i + 1);
				int columnType = metaData.getColumnType(i + 1);
				metaDataMap.put(columnName, columnType);
			}
		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		}
		return (metaDataMap);
	}

	/*
	 * Returns a map indicating for each column of a table if it is nullable or not
	 * The key of the map is the column label, not name
	 * 
	 * @param tableName(String) name of the table
	 * @return columnType(HashMap<String, Integer>) a map containing columnName->columnType
	 */
	public HashMap<String, Integer> getColumnNullable(String tableName) throws BroadSQLException {
		HashMap<String, Integer> metaDataMap = new HashMap<String, Integer>();
		String query = "select * from " + tableName + " where 1=0";
		try {
			Statement statement = connection.createStatement();
			ResultSet results = statement.executeQuery(query);
			if (results != null) {
				ResultSetMetaData metaData = results.getMetaData();
				for (int i = 0; i < metaData.getColumnCount(); i++) {
					// String columnName = metaData.getColumnName(i + 1);
					String columnName = metaData.getColumnLabel(i + 1);
					int isNullable = metaData.isNullable(i + 1);
					metaDataMap.put(columnName, isNullable);
				}
			}
		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		}
		return (metaDataMap);
	}

	/**
	 * Execute a stored procedure
	 * 
	 * @param query
	 * @throws BroadSQLException
	 */
	public void callProcedure(String query) throws BroadSQLException {
		try {
			CallableStatement cs = connection.prepareCall("{" + query + "}");
			ResultSet results = cs.executeQuery();
			if (results != null) {
				// Get MetaData
				ResultSetMetaData metaData = results.getMetaData();
				for (int i = 0; i < metaData.getColumnCount(); i++) {
					if (this.toScreen) {
						int size = metaData.getColumnDisplaySize(i + 1);
						String padded = StringUtils.rightPad(metaData.getColumnLabel(i + 1), size, " ");
						cmdLineConsole.write(padded + sep);
					}
				}
				if (this.toScreen) {
					cmdLineConsole.writeln("");
				}

				// Get Results
				int rowId = 0;
				while (results.next()) {
					rowId++;
					for (int i = 0; i < metaData.getColumnCount(); i++) {
						String cell = results.getString(i + 1);
						if (this.toScreen) {
							int size = metaData.getColumnDisplaySize(i + 1);
							String padded = StringUtils.rightPad(cell, size, " ");
							cmdLineConsole.write(padded + sep);
						}
					}
					if (this.toScreen) {
						cmdLineConsole.writeln("");
					}
				}
			}
		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		}
	}

	/**
	 * Determines whether flag hasUncommitted should be set to true based on the
	 * query
	 * 
	 * @param query
	 */
	public void setFlagUncommitted(String query) {
		if (StringUtils.isNotBlank(query)) {
			// Support for "transactional DDL" : ability to commit/rollback after a DDL
			// command
			// Some databases that have no restrictions of rollback over DDL commands :
			// Derby, SQLite, SQL Server, DB2, Sybase, Informix, Firebird and PostgreSQL
			// For the list see
			// https://wiki.postgresql.org/wiki/Transactional_DDL_in_PostgreSQL:_A_Competitive_Analysis
			// or
			// https://stackoverflow.com/questions/4692690/is-it-possible-to-roll-back-create-table-and-alter-table-statements-in-major-sql
			String[] noDdlLimit = { SpringPropertiesConfig.DBTYPE_DERBY_Embedded,
					SpringPropertiesConfig.DBTYPE_DERBY_Client, SpringPropertiesConfig.DBTYPE_SQLITE,
					SpringPropertiesConfig.DBTYPE_PostgreSQL,
					SpringPropertiesConfig.DBTYPE_SQL_Server, SpringPropertiesConfig.DBTYPE_Sybase,
					SpringPropertiesConfig.DBTYPE_DB2, SpringPropertiesConfig.DBTYPE_Informix,
					SpringPropertiesConfig.DBTYPE_Firebird };
			List<String> noDdlLimitLst = Arrays.asList(noDdlLimit);
			if (noDdlLimitLst.contains(this.platform.getDbType())) {
				// For these databases, in all cases there is a rollback
				this.hasUncommitted = true;
				// log.debug("No DDL limit for {} , autocommit set to {}",
				// this.platform.getDbType(), this.hasUncommitted);
			} else {
				// For other database types, only non-DDL commands are rolled back because DDL
				// commands make an implicit commit
				String[] ddlCommands = { "CREATE", "ALTER", "DROP", "TRUNCATE" };
				boolean isDdl = false;
				String command = "";
				for (String ddlCommand : ddlCommands) {
					if (query.toUpperCase().startsWith(ddlCommand)) {
						isDdl = true;
						command = ddlCommand;
						break;
					}
				}
				if (!isDdl) {
					this.hasUncommitted = true;
					// log.debug("DDL limit without DDL command, autocommit set to {}",
					// this.hasUncommitted);
				} else {
					// log.debug("DDL limit with DDL command {}, autocommit set to {}", command,
					// this.hasUncommitted);
				}
			}
		}
	}

	/*
	 * Executes an update query that does not returns a ResultSet (update, insert, etc)
	 */
	public void executeUpdateQuery(String query) throws BroadSQLException {
		executeUpdateQuery(query, true);
	}

	/**
	 * Executes an UPDATE query
	 * 
	 * @param query
	 * @param showResult
	 * @throws BroadSQLException
	 */
	public void executeUpdateQuery(String query, boolean showResult) throws BroadSQLException {
		executeUpdate(query, null, showResult);
	}

	/** SPRINT 0110A: {@link #executeUpdateQuery(String)} for a statement with {@code ${name}} references (see {@link #executeSelectQuery(PreparedSql)}). */
	public void executeUpdateQuery(PreparedSql prepared) throws BroadSQLException {
		executeUpdate(prepared.getOriginalText(), prepared, true);
	}

	private void executeUpdate(String query, PreparedSql prepared, boolean showResult) throws BroadSQLException {
		Statement statement = null;
		if (StringUtils.isNotBlank(query)) {
			try {
				int result;
				if (prepared == null) {
					statement = connection.createStatement();
					currentStatement = statement;
					// log.debug("DirConn AutoCommit(i): {}", directConnection.getAutoCommit());
					result = statement.executeUpdate(query);
					// log.debug("DirConn AutoCommit(f): {}", directConnection.getAutoCommit());
				} else {
					PreparedStatement preparedStatement = connection.prepareStatement(prepared.getJdbcText());
					statement = preparedStatement;
					currentStatement = statement;
					JdbcBinder.bindAll(preparedStatement, prepared.getBinds());
					result = preparedStatement.executeUpdate();
				}

				setFlagUncommitted(query);

				// SPRINT 0110A: the row-count feedback is routine output, hidden by OUTPUT QUIET in a Script
				if (showResult && cmdLineConsole != null) {
					String verb;
					if (query.toUpperCase().startsWith("INSERT")) {
						verb = "created.";
					} else if (query.toUpperCase().startsWith("UPDATE")) {
						verb = "updated.";
					} else if (query.toUpperCase().startsWith("DELETE")) {
						verb = "deleted.";
					} else {
						verb = "processed.";
					}
					cmdLineConsole.routineWriteln("" + result + " row(s) " + verb);
				}
				statement.close();
			} catch (SQLException ie) {
				throw new SqlExecutionException(query, ie, connectionIdForErrorDisplay(), "UPDATE");
			} finally {
				currentStatement = null;
				if (statement != null) {
					try {
						statement.close();
					} catch (Exception e) {
						throw new BroadSQLException(e);
					}
				}
			}
		}
	}

	/**
	 * Opens a connection to the database
	 * 
	 * @throws BroadSQLException
	 */
	public Connection openConnection() throws SQLException, ClassNotFoundException, InstantiationException,
			IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException,
			SecurityException, BroadSQLException {
		return (openConnection(false));
	}

	/**
	 * Opens a connection to the database, used by connect()
	 * 
	 * @param alignAutoCommit(boolean) if true, it means the getColumn is kept open,
	 *                                 and in that case the autocommit is taken from
	 *                                 the database
	 * @throws BroadSQLException
	 * @output (Connection) a JDBC getColumn
	 */
	public Connection openConnection(boolean alignAutoCommit)
			throws SQLException, ClassNotFoundException, InstantiationException, IllegalAccessException,
			IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException,
			BroadSQLException {
		Connection conn = null;
		// Derby Database
		if (this.platform.getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_DERBY_Embedded)
				|| this.platform.getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_DERBY_Client)) {
			initDerbyLog();
			Class.forName(this.platform.getDbDriver()).getDeclaredConstructor().newInstance();
			Properties props = new Properties();
			if (this.platform.getUserName() != null && !this.platform.getUserName().trim().equals("")) {
				props.put("user", this.platform.getUserName());
				props.put("password", this.platform.getUserPassword());
			}
			conn = DriverManager.getConnection(this.platform.getConnectorDatabase(), props);

			// H2 Database
		} else if (this.platform.getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_H2)) {
			Class.forName(this.platform.getDbDriver()).getDeclaredConstructor().newInstance();
			String connector = this.platform.getConnectorDatabase();
			if (this.platform.getUserName() != null && !this.platform.getUserName().trim().equalsIgnoreCase("")) {
				connector = connector + ";USER=" + this.platform.getUserName();
			}
			if (this.platform.getUserPassword() != null
					&& !this.platform.getUserPassword().trim().equalsIgnoreCase("")) {
				connector = connector + ";PASSWORD=" + this.platform.getUserPassword();
			}
			conn = DriverManager.getConnection(connector);

			// All other databases (MySQL, PostgreSQL, Oracle, etc)
		} else {
			Class.forName(this.platform.getDbDriver()).getDeclaredConstructor().newInstance();
			Properties props = new Properties();
			if (this.platform.getUserName() != null && !this.platform.getUserName().trim().equals("")) {
				props.put("user", this.platform.getUserName());
				props.put("password", this.platform.getUserPassword());
			}
			conn = DriverManager.getConnection(this.platform.getConnectorDatabase(), props);
		}

		// DatabaseMetaData object
		if (conn != null) {
			getDbInformation(conn);

			// Setting autocommit options
			this.autoCommit = this.consoleSettings.isAutoCommit();
			conn.setAutoCommit(this.autoCommit);
			this.hasUncommitted = false;

		} else {
			throw new BroadSQLException(
					"Unable to connect to " + this.getPlatform().getId() + " (" + this.getPlatform().getDbType() + ")");
		}

		// Last, prepare the JdbcTemplate object
		initJdbcTemplate();

		return (conn);
	}

	private void getDbInformation(Connection conn) throws SQLException {
		DatabaseMetaData meta = conn.getMetaData();
		dbDriver = meta.getDriverName() + ", " + meta.getDriverVersion();
		dbName = meta.getDatabaseProductName();
		dbVersion = meta.getDatabaseProductVersion();
	}

	/**
	 * For intercepting CTRL+C
	 * 
	 * @param connection
	 */
	public void attachShutDownHook(final Connection connection) {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				if (connection != null) {
					try {
						connection.close();
					} catch (SQLException se) {
						se.printStackTrace();
					}
				}
			}
		});
	}

	/**
	 * Connects or reconnects to a database
	 * 
	 * @throws BroadSQLException
	 */
	public void connect() throws BroadSQLException {
		try {
			if (this.isConnected()) {
				this.close();
			}
			// Connects to the database
			this.connection = openConnection(true);

		} catch (SQLException ie) {
			log.error(ie.getLocalizedMessage());
			throw new BroadSQLException(ie);
		} catch (ClassNotFoundException ie) {
			throw new BroadSQLException(missingDriverMessage(this.platform));
		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException
				| InvocationTargetException | NoSuchMethodException | SecurityException ie) {
			throw new BroadSQLException(ie);
		}
	}

	/**
	 * Whether the connection has uncommitted work, before it is closed with autocommit off: {@code TRUE},
	 * {@code FALSE}, or {@code null} when it cannot be determined.
	 *
	 * <p>Oracle and H2 are asked with their own catalog query ({@code DBMS_TRANSACTION}, H2's
	 * {@code INFORMATION_SCHEMA.SESSIONS}); every other database, HSQLDB included (it has no such view), uses
	 * BroadSQL's own record of the statements it ran ({@link #setFlagUncommitted}). A provider probe that fails
	 * (a revoked {@code DBMS_TRANSACTION} grant, a missing view) is not an error of the command that closes the
	 * connection: the state is then unknown ({@code null}) and {@link #close(boolean)} rolls back.
	 */
	private Boolean checkForUncommitted() {
		String sql = null;
		if (this.platform.getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_Oracle)) {
			sql = "SELECT COUNT(dbms_transaction.step_id) NB FROM DUAL";
		} else if (this.platform.getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_H2)) {
			sql = "SELECT COUNT(*) from INFORMATION_SCHEMA.SESSIONS WHERE CONTAINS_UNCOMMITTED";
		}
		if (sql == null) {
			return this.hasUncommitted;
		}
		try {
			Integer nb = getInt(sql);
			return nb != null && nb > 0;
		} catch (BroadSQLException | RuntimeException e) {
			log.debug("Uncommitted-work probe failed on {}: {}", this.platform.getDbType(), e.getLocalizedMessage());
			return null;
		}
	}

	/**
	 * Returns the integer value of a query result Fetches only the first row, other
	 * rows are ignorer
	 * 
	 * @param query
	 * @return
	 * @throws BroadSQLException
	 */
	public Integer getInt(String query) throws BroadSQLException {
		Integer result = null;
		if (StringUtils.isNotBlank(query)) {
			Statement statement = null;
			try {
				statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(query);
				try {
					if (rs != null) {
						while (rs.next()) {
							result = rs.getInt(1);
							break;
						}
						statement.close();
					}
				} catch (SQLException ie) {
					throw new BroadSQLException(ie);
				} finally {
					if (rs != null) {
						rs.close();
					}
				}
			} catch (SQLException ie) {
				throw new BroadSQLException(ie);
			} finally {
				if (statement != null) {
					try {
						statement.close();
					} catch (SQLException e) {
						throw new BroadSQLException(e);
					}
				}
			}
		} else {
			throw new BroadSQLException("query is null");
		}
		return result;
	}

	/**
	 * SPRINT 0110A: the scalar query assignment {@code LET name = <query>} (spec section 8.3). Runs {@code prepared}
	 * on the current connection, in the current transaction (it sees uncommitted changes), as a
	 * {@link PreparedStatement} with {@code maxRows = 2}, registered for CTRL+C, and returns its single value. At most
	 * two rows are read. Exactly one column and one row are required: no result set, zero or several columns, an
	 * unsupported column type, zero rows or more than one row are errors, and the first row is never used. SQL NULL
	 * is a typed NULL. Statement and result set are closed before returning, in every case. Nothing is displayed,
	 * captured, recorded as the last result, or flagged as pending work.
	 *
	 * @throws SqlExecutionException a SQL error (rendered like any SQL error)
	 * @throws BroadSQLException     a result-shape error, or no connection
	 */
	public ScriptValue queryScalar(PreparedSql prepared) throws BroadSQLException {
		if (connection == null) {
			throw new BroadSQLException("No active SQL connection: a query assignment runs on the current connection");
		}
		PreparedStatement statement = null;
		ResultSet results = null;
		try {
			statement = connection.prepareStatement(prepared.getJdbcText());
			currentStatement = statement;
			statement.setMaxRows(2);
			JdbcBinder.bindAll(statement, prepared.getBinds());
			boolean hasResultSet = statement.execute();
			if (!hasResultSet) {
				throw new BroadSQLException("The query returns no result: a variable is assigned from a query that returns exactly one row and one column");
			}
			results = statement.getResultSet();
			ResultSetMetaData metaData = results.getMetaData();
			int columns = metaData.getColumnCount();
			if (columns != 1) {
				throw new BroadSQLException("The query returned " + columns + " columns, exactly 1 is required");
			}
			int jdbcType = metaData.getColumnType(1);
			String typeName = metaData.getColumnTypeName(1);
			int effectiveType = TemporalValues.effectiveType(jdbcType, typeName);
			if (!JdbcTypedValueReader.isScalarVariableType(effectiveType)) {
				throw new BroadSQLException("The query returned a column of type " + typeName + ", which a variable cannot hold "
						+ "(supported: character, numeric, boolean, date, time and timestamp types)");
			}
			if (!results.next()) {
				throw new BroadSQLException("The query returned no row, exactly 1 is required");
			}
			String text = results.getString(1);
			Object value = text == null ? null : JdbcTypedValueReader.typedValue(results, 1, effectiveType, text);
			if (results.next()) {
				throw new BroadSQLException("The query returned more than one row, exactly 1 is required");
			}
			return ScriptValue.fromQuery(value, jdbcType, typeName, connectionIdForErrorDisplay());
		} catch (SQLException e) {
			throw new SqlExecutionException(prepared.getOriginalText(), e, connectionIdForErrorDisplay(), "SELECT");
		} finally {
			currentStatement = null;
			try {
				if (results != null) {
					results.close();
				}
			} catch (SQLException ignored) {
				log.debug("Closing the result set of a query assignment failed: {}", ignored.getLocalizedMessage());
			}
			try {
				if (statement != null) {
					statement.close();
				}
			} catch (SQLException ignored) {
				log.debug("Closing the statement of a query assignment failed: {}", ignored.getLocalizedMessage());
			}
		}
	}

	/**
	 * SPRINT 0110A: whether the current connection's driver is the PostgreSQL JDBC driver (pgjdbc), identified by
	 * {@code DatabaseMetaData.getDriverName()} - the one driver on which raw {@code ?} is escaped as {@code ??} when
	 * BroadSQL prepares a statement (spec section 9.6). {@code false} when it cannot be determined.
	 */
	public boolean isPgJdbc() {
		if (connection == null) {
			return false;
		}
		try {
			return isPgJdbcDriverName(connection.getMetaData().getDriverName());
		} catch (SQLException | RuntimeException e) {
			return false;
		}
	}

	/** Whether a {@code DatabaseMetaData.getDriverName()} value names pgjdbc ({@code PostgreSQL JDBC Driver}). */
	public static boolean isPgJdbcDriverName(String driverName) {
		return driverName != null && driverName.toLowerCase(java.util.Locale.ROOT).contains("postgresql");
	}

	/**
	 * SPRINT 0110A: the existing rollback after a SQL error on the SQL path or in a query assignment (spec 15.3),
	 * now reported: returns whether pending work tracked by BroadSQL was discarded (autocommit OFF and the
	 * pending-change flag set before the rollback), so the caller prints the rollback warning after the error.
	 */
	public boolean rollbackAfterSqlError() throws BroadSQLException {
		boolean pending = !this.autoCommit && this.hasUncommitted;
		rollback();
		return pending;
	}

	/**
	 * Close the JDBC connection
	 */
	public void close() throws BroadSQLException {
		close(true);
	}

	/**
	 * Close the JDBC connection: commit (Autocommit on) or check for and roll back pending work (Autocommit off),
	 * close it, and report "Disconnected".
	 *
	 * <p>Idempotent: on a connection that is already closed (closed by an earlier call, or underneath BroadSQL) it
	 * does nothing, since there is nothing left to check, roll back or report. More than one cleanup reaches the
	 * same connection on EXIT: {@code CommandInterpreter.closeConnectionOnExit()}, then the Spring context's destroy
	 * callback on the {@code sqlDatabase} bean, and the CTRL+C hook; only the first one runs this lifecycle.
	 * Synchronized so the CTRL+C hook's thread and the main thread cannot run it concurrently.
	 *
	 * @param displayConfirmationMsg
	 * @throws BroadSQLException
	 */
	public synchronized void close(boolean displayConfirmationMsg) throws BroadSQLException {
		if (isJdbcConnectionClosed()) {
			return;
		}
		boolean isClosed = false;
		if (this.connection != null) {
			try {
				if (this.connection != null) {
					if (this.autoCommit) {
						this.commit();
					} else {
						// Never commit here: pending work, known or not, is rolled back, and a failure to
						// determine or roll it back never prevents the connection from being closed.
						Boolean pending = checkForUncommitted();
						if (this.cmdLineConsole != null) {
							if (pending == null) {
								this.cmdLineConsole.warn(new BroadSQLException(
										"Could not determine whether uncommitted changes are pending. Rolling back."));
							} else if (pending) {
								this.cmdLineConsole.warn(new BroadSQLException("Uncommitted transactions aborted. Rolling back."));
							}
						}
						try {
							this.rollback();
						} catch (BroadSQLException re) {
							if (this.cmdLineConsole != null) {
								this.cmdLineConsole.error(re);
							} else {
								log.debug(re.getMessage());
							}
						}
						this.hasUncommitted = false;
					}
					this.connection.close();
					isClosed = true;
				}
			} catch (SQLException se) {
				if (this.cmdLineConsole != null) {
					this.cmdLineConsole.error(new BroadSQLException(se));
				} else {
					log.debug(se.getMessage());
				}
			} finally {
				try {
					if (this.connection != null) {
						this.connection.close();
						isClosed = true;
					}
				} catch (Exception e) {
					throw new BroadSQLException(e);
				}
			}
			if (isClosed && this.cmdLineConsole != null) {
				if (displayConfirmationMsg) {
					this.cmdLineConsole.println("Disconnected from '" + this.platform.getDbName() + "'");
				}
			}
		}
	}

	/**
	 * Whether there is no open JDBC connection left to close: none was ever opened, or it is already closed
	 * ({@link Connection#isClosed()}, which JDBC allows on a closed connection). When the driver cannot tell, the
	 * connection is treated as open, so {@link #close(boolean)} still runs its full lifecycle.
	 */
	private boolean isJdbcConnectionClosed() {
		if (this.connection == null) {
			return true;
		}
		try {
			return this.connection.isClosed();
		} catch (SQLException e) {
			log.debug("Could not determine whether the connection is closed: {}", e.getLocalizedMessage());
			return false;
		}
	}

	/**
	 * 
	 * @return
	 * @throws BroadSQLException
	 */
	public boolean isConnected() throws BroadSQLException {
		int timeout = 3; // 3 sec
		boolean connected = false;
		if (this.connection != null) {
			try {
				try {
					connected = this.connection.isValid(timeout);
				} catch (java.lang.AbstractMethodError ea) {
					// isValid not supported: oracle before 10.2.0.5
					String query = "select count(*) from DUAL";
					Statement stat = this.connection.createStatement();
					ResultSet rs = stat.executeQuery(query);
					if (rs != null) {
						rs.close();
					}
					if (stat != null) {
						stat.close();
					}
					connected = true;
				}
			} catch (SQLException ce) {
				throw new BroadSQLException(ce);
			}
		} else {
			connected = false;
		}
		return (connected);
	}

	/**
	 * 
	 * @throws BroadSQLException
	 */
	public void commit() throws BroadSQLException {
		try {
			if (this.connection != null) {
				this.connection.commit();
				this.hasUncommitted = false;
			}
		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		}
	}

	/**
	 * 
	 * @throws BroadSQLException
	 */
	public void rollback() throws BroadSQLException {
		try {
			if (this.connection != null) {
				this.connection.rollback();
				this.hasUncommitted = false;
			}
		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		}
	}

	/**
	 * Execute an UPDATE query
	 * 
	 * @param query
	 * @return
	 * @throws BroadSQLException
	 */
	protected String executeQuery(String query) throws BroadSQLException {
		return (executeQuery(this.connection, query));
	}

	/**
	 * Execute an UPDATE query
	 * 
	 * @param connection
	 * @param query
	 * @return
	 * @throws BroadSQLException
	 */
	protected String executeQuery(Connection connection, String query) throws BroadSQLException {
		Statement statement = null;
		try {
			statement = connection.createStatement();
			int result = statement.executeUpdate(query);
			setFlagUncommitted(query);
			String msg = "" + result + " row ";
			if (query.toUpperCase().startsWith("INSERT ")) {
				msg = msg + "created.";
			} else if (query.toUpperCase().startsWith("CREATE ")) {
				msg = msg + "created.";
			} else if (query.toUpperCase().startsWith("ALTER ")) {
				msg = msg + "updated.";
			} else if (query.toUpperCase().startsWith("UPDATE ")) {
				msg = msg + "updated.";
			} else if (query.toUpperCase().startsWith("DELETE ")) {
				msg = msg + "deleted.";
			} else {
				msg = msg + "processed.";
			}
			if (statement != null) {
				statement.close();
			}
			return (msg);
		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		} finally {
			if (statement != null) {
				try {
					statement.close();
				} catch (Exception e) {
					throw new BroadSQLException(e);
				}
			}
		}
	}

	/**
	 * Returns number of records in a table
	 *
	 * @param tableName(String) the name of a table (eg customer), resolved by {@link #sqlTableReference}
	 *
	 */
	public int getNumberOfRecords(String tableName) throws BroadSQLException {
		return (getNumberOfRecords(tableName, null));
	}

	/**
	 * Returns number of records in a table
	 *
	 * @param tableName   the table as typed, resolved by {@link #sqlTableReference}
	 * @param whereClause
	 * @return
	 * @throws BroadSQLException
	 */
	public int getNumberOfRecords(String tableName, String whereClause) throws BroadSQLException {
		String tableReference = sqlTableReference(tableName);
		try {
			int nbRecords = 0;
			String result = null;
			Statement statement = connection.createStatement();
			try {
				String query = "SELECT COUNT(*) NBREC FROM " + tableReference;
				if (StringUtils.isNotBlank(whereClause)) {
					query = query + " WHERE " + whereClause;
				}
				ResultSet results = statement.executeQuery(query);
				try {
					if (results != null) {
						results.next();
						result = results.getString("NBREC");
						try {
							nbRecords = Integer.parseInt(result);
						} catch (NumberFormatException nfe) {
							nbRecords = -1;
						}
					}
					return (nbRecords);
				} catch (SQLException se) {
					throw new BroadSQLException(se);
				} finally {
					try {
						if (results != null) {
							results.close();
						}
					} catch (SQLException se) {
						throw new BroadSQLException(se);
					}
				}
			} catch (SQLException se) {
				throw new BroadSQLException(se);
			} finally {
				try {
					if (statement != null) {
						statement.close();
					}
				} catch (SQLException se) {
					throw new BroadSQLException(se);
				}
			}
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}

	/**
	 * Close a connection
	 */
	public void closeConnection(Connection conn) throws BroadSQLException {
		try {
			if (conn != null) {
				conn.close();
			}
		} catch (Exception e) {
			throw new BroadSQLException(e);
		} finally {
			try {
				if (conn != null) {
					conn.close();
				}
			} catch (Exception e) {
				throw new BroadSQLException(e);
			}
		}
	}

	private void initDerbyLog() {
		// To get rid of derby.log error file
		// Moves derby.log to the subdirectory /logs
		System.setProperty("derby.system.home", "logs");
		// The following is for info only. Allows to display log info in the console
		// System.setProperty("derby.stream.error.field", "MyApp.DEV_NULL");
	}

	/**
	 * Test a connection to another EXISTING platform
	 */
	public StringBuffer testConnectionToExistingPlatform(String platformId) throws BroadSQLException {
		Connection conn = null;
		try {
			StringBuffer result = new StringBuffer();
			if (StringUtils.isNotBlank(platformId)) {
				DatabaseDefinition pl = databaseConnectionsCollection.getDatabaseConnection(platformId);
				result = testConnectionToPlatform(pl);
			}
			return (result);
		} catch (BroadSQLException ex) {
			throw new BroadSQLException(ex);
		} finally {
			closeConnection(conn);
		}
	}

	/**
	 * Test a connection to any platform
	 */
	public StringBuffer testConnectionToPlatform(DatabaseDefinition platform) throws BroadSQLException {
		Connection conn = null;
		try {
			StringBuffer result = new StringBuffer();
			if (platform != null) {
				// !! Do not replace below block by openConnection() !!!
				// openConnection is working with this.platform, while below code works with any
				// platform
				if (platform.getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_DERBY_Embedded)
						|| platform.getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_DERBY_Client)) {
					// Derby Database
					initDerbyLog();
					Class.forName(platform.getDbDriver()).getDeclaredConstructor().newInstance();
					Properties props = new Properties();
					if (platform.getUserName() != null && !platform.getUserName().trim().equalsIgnoreCase("")) {
						props.put("user", platform.getUserName());
						props.put("password", platform.getUserPassword());
					}
					conn = DriverManager.getConnection(platform.getConnectorDatabase(), props);

					// H2 Database
				} else if (platform.getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_H2)) {
					Class.forName(platform.getDbDriver()).getDeclaredConstructor().newInstance();
					String connector = platform.getConnectorDatabase();
					if (platform.getUserName() != null && !platform.getUserName().trim().equalsIgnoreCase("")) {
						connector = connector + ";USER=" + platform.getUserName();
					}
					if (platform.getUserPassword() != null && !platform.getUserPassword().trim().equalsIgnoreCase("")) {
						connector = connector + ";PASSWORD=" + platform.getUserPassword();
					}
					conn = DriverManager.getConnection(connector);
				} else {
					Class.forName(platform.getDbDriver()).getDeclaredConstructor().newInstance();
					Properties props = new Properties();
					if (platform.getUserName() != null && !platform.getUserName().trim().equals("")) {
						props.put("user", platform.getUserName());
						props.put("password", platform.getUserPassword());
					}
					conn = DriverManager.getConnection(platform.getConnectorDatabase(), props);
				}

				// Create DatabaseMetaData object
				if (conn != null) {
					getDbInformation(conn);
					result.append("JDBC driver: " + dbDriver + "\n");
					result.append("Database name: " + dbName + "\n");
					result.append("Database version: " + dbVersion + "\n");
					if (conn != null) {
						conn.close();
					}
				} else {
					throw new BroadSQLException("Unable to connect to " + this.getPlatform().getId() + " ("
							+ this.getPlatform().getDbType() + ")");
				}
			}
			return (result);
		} catch (ClassNotFoundException ex) {
			throw new BroadSQLException(missingDriverMessage(platform));
		} catch (SQLException | InstantiationException | IllegalAccessException
				| IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException ex) {
			throw new BroadSQLException(ex);
		} finally {
			closeConnection(conn);
		}
	}

	/**
	 * Builds the user-facing error for a {@code ClassNotFoundException} thrown while loading a
	 * Connection's JDBC driver class - replaces the raw exception (previously just wrapped as-is) with
	 * an actionable message, since this is the single most common way a Connection fails: its
	 * {@code TYPE}'s driver jar was never copied into {@code drivers/}/{@code lib/} (see
	 * {@code DatabaseDefinitionsVault#isDriverAvailable}, which flags exactly this situation ahead of
	 * time in the {@code CONFIG}/CLI connection wizard, without preventing the type from being picked).
	 */
	private static String missingDriverMessage(DatabaseDefinition platform) {
		return "Cannot connect to '" + platform.getId() + "': JDBC driver class '" + platform.getDbDriver()
				+ "' for type '" + platform.getDbType() + "' was not found. Copy the matching driver .jar file "
				+ "into the drivers/ or lib/ folder and restart BroadSQL, then try again.";
	}

	/**
	 *
	 * @param schemaNamePattern
	 * @param tableNamePattern
	 * @return
	 * @throws BroadSQLException
	 */
	public MetadataService getMetadataService() {
		return new MetadataService(connection);
	}

	/** A plain SQL identifier chain ({@code NAME}, {@code SCHEMA.NAME}): no space, quote or other punctuation. */
	private static final java.util.regex.Pattern PLAIN_IDENTIFIER_CHAIN = java.util.regex.Pattern
			.compile("[\\p{L}_][\\p{L}\\p{N}_$#@]*(\\.[\\p{L}_][\\p{L}\\p{N}_$#@]*)*");

	/**
	 * The SQL reference to use for a table named by the user in a command that generates SQL from it ({@code ALL},
	 * {@code CNT}, {@code DUMP <table>}, {@code PULL <table>}): the table is resolved through metadata
	 * ({@link MetadataService#resolveTable}) and referenced by its resolved identity, quoted for this driver
	 * ({@link MetadataService#sqlName}), so the database reads exactly the table the lookup found, never a
	 * differently cased or partially named one.
	 *
	 * <p>When the lookup finds nothing (or the driver cannot answer it) and the name is a plain identifier chain,
	 * the name is used as typed, so objects that metadata does not list in the current schema but the database
	 * itself resolves (a synonym, an object on the search path, a system view) keep working. Any other name that
	 * does not resolve, and an ambiguous or invalid name, is an error: nothing is run.
	 */
	public String sqlTableReference(String userName) throws BroadSQLException {
		MetadataService metadata = getMetadataService();
		try {
			return metadata.sqlName(metadata.resolveTable(userName));
		} catch (MetadataException e) {
			boolean unresolved = e.getKind() == MetadataException.Kind.NOT_FOUND || e.getKind() == MetadataException.Kind.UNSUPPORTED
					|| e.getKind() == MetadataException.Kind.RETRIEVAL_FAILED;
			String typed = StringUtils.trimToEmpty(userName);
			if (unresolved && PLAIN_IDENTIFIER_CHAIN.matcher(typed).matches()) {
				return typed;
			}
			throw e;
		}
	}

	/**
	 * Note: a substring search ({@code %name%}), not an exact lookup - see {@link MetadataService#resolveTable} for exact resolution.
	 */
	public boolean existsTable(String schemaNamePattern, String tableNamePattern) throws BroadSQLException {
		TreeSet<TableMetadata> tables = getTables(null, schemaNamePattern, tableNamePattern);
		return (tables != null && !tables.isEmpty());
	}

	/**
	 * The connection's current schema, or {@code null} if the driver doesn't report one. Callers that
	 * need to resolve an unqualified table/schema name to the current schema (rather than passing a
	 * blank pattern through to a {@code DatabaseMetaData} lookup, which the JDBC spec treats as "search
	 * every schema") should call this first - see {@code CommandDescr}.
	 *
	 * @throws BroadSQLException
	 */
	public String getCurrentSchema() throws BroadSQLException {
		try {
			return connection.getSchema();
		} catch (SQLException se) {
			throw new BroadSQLException(se);
		}
	}

	/**
	 * Retrieves list of table types for a given database
	 */
	public String[] getTableTypes() throws BroadSQLException {
		String[] tableTypes = null;
		try {
			List<String> typesList = new ArrayList<>();
			String TABLE_TYPE = "TABLE_TYPE";

			DatabaseMetaData dbmd = connection.getMetaData();

			ResultSet tables = dbmd.getTableTypes();
			try {
				while (tables.next()) {
					String tableType = tables.getString(TABLE_TYPE);
					typesList.add(tableType);
				}
				if (typesList != null && !typesList.isEmpty()) {
					tableTypes = new String[typesList.size()];
					tableTypes = typesList.toArray(tableTypes);
				}
			} catch (SQLException se) {
				throw new BroadSQLException(se);
			} finally {
				try {
					if (tables != null) {
						tables.close();
					}
				} catch (SQLException se) {
					throw new BroadSQLException(se);
				}
			}
		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		}
		return tableTypes;
	}

	/**
	 * The real tables matching the patterns (substring searches, see {@link #getTablesOrViews}): only the table
	 * types of {@link MetadataService#tableTypes} (ordinary, temporary and system tables), never a view, synonym,
	 * sequence, index or other object type the driver reports. Used by {@code SHOW TABLES}, {@code LOAD} target
	 * resolution and table completion.
	 */
	public TreeSet<TableMetadata> getTables(String catalog, String schemaPattern, String tableNamePattern)
			throws BroadSQLException {
		String[] tableTypes;
		try {
			tableTypes = MetadataService.tableTypes(connection.getMetaData(), true);
		} catch (SQLException se) {
			throw new BroadSQLException(se);
		}
		return getTablesOrViews(tableNamePattern, catalog, schemaPattern, tableTypes);
	}

	/*
	 * Retrieves list of tables
	 * @param tableFilter(String) a substring used for search
	 * @param schemaFilter(String) a substring used for search
	 * @param typeFilter(String[]) used for specifying a filter type like VIEW. If null, returns all types
	 */
	private TreeSet<TableMetadata> getTablesOrViews(String tableNamePattern, String catalog, String schemaNamePattern,
			String[] types) throws BroadSQLException {
		try {
			TreeSet<TableMetadata> results = new TreeSet<TableMetadata>();
			String TABLE_CAT = "TABLE_CAT";
			String TABLE_NAME = "TABLE_NAME";
			String TABLE_SCHEMA = "TABLE_SCHEM";
			String TABLE_TYPE = "TABLE_TYPE";
			String TABLE_REMARK = "REMARKS";
			// String[] TABLE_TYPES = {"TABLE"};

			DatabaseMetaData dbmd = connection.getMetaData();

			if (StringUtils.isNotBlank(tableNamePattern)) {
				tableNamePattern = "%" + tableNamePattern + "%";
			} else {
				tableNamePattern = null;
			}

			if (StringUtils.isNotBlank(schemaNamePattern)) {
				schemaNamePattern = "%" + schemaNamePattern + "%";
			} else {
				schemaNamePattern = connection.getSchema();
			}

			ResultSet tables = dbmd.getTables(catalog, schemaNamePattern, tableNamePattern, types);
			try {
				while (tables.next()) {
					String schemaName = tables.getString(TABLE_SCHEMA);
					TableMetadata table = new TableMetadata();
					table.setName(tables.getString(TABLE_NAME));
					table.setSchema(schemaName);
					table.setType(tables.getString(TABLE_TYPE));
					table.setCatalog(tables.getString(TABLE_CAT));
					table.setRemark(tables.getString(TABLE_REMARK));
					results.add(table);
				}
				return (results);
			} catch (SQLException se) {
				throw new BroadSQLException(se);
			} finally {
				try {
					if (tables != null) {
						tables.close();
					}
				} catch (SQLException se) {
					throw new BroadSQLException(se);
				}
			}
		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		}
	}

	/**
	 * The views matching the patterns: the view types of {@link MetadataService#viewTypes} ({@code VIEW}, and
	 * {@code MATERIALIZED VIEW} where the driver has it). Used by {@code SHOW VIEWS} and completion.
	 */
	public TreeSet<TableMetadata> getViews(String catalog, String schemaPattern, String tableNamePattern)
			throws BroadSQLException {
		String[] viewTypes;
		try {
			viewTypes = MetadataService.viewTypes(connection.getMetaData());
		} catch (SQLException se) {
			throw new BroadSQLException(se);
		}
		return (this.getTablesOrViews(tableNamePattern, catalog, schemaPattern, viewTypes));
	}

	/**
	 * 
	 * @param catalogNameFilter
	 * @return
	 * @throws BroadSQLException
	 */
	public TreeSet<String> getCatalogs(String catalogNameFilter) throws BroadSQLException {
		try {
			TreeSet<String> results = new TreeSet<String>();
			DatabaseMetaData dmd = connection.getMetaData();
			ResultSet rs = dmd.getCatalogs();
			try {
				if (rs != null) {
					while (rs.next()) {
						String catalog = rs.getString("TABLE_CAT");
						if (StringUtils.isBlank(catalogNameFilter)
								|| StringUtils.containsIgnoreCase(catalog, catalogNameFilter)) {
							results.add(catalog);
						}
					}
				}
				return (results);
			} catch (SQLException se) {
				throw new BroadSQLException(se);
			} finally {
				try {
					if (rs != null) {
						rs.close();
					}
				} catch (SQLException se) {
					throw new BroadSQLException(se);
				}
			}
		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		}
	}

	/**
	 * 
	 * @param schemasNameFilter
	 * @return
	 * @throws BroadSQLException
	 */
	public TreeSet<SchemaMetadata> getSchemas(String schemasNameFilter) throws BroadSQLException {
		try {
			TreeSet<SchemaMetadata> results = new TreeSet<SchemaMetadata>();
			DatabaseMetaData dmd = connection.getMetaData();
			String currentSchema = connection.getSchema();
			ResultSet rs = dmd.getSchemas();
			try {
				if (rs != null) {
					while (rs.next()) {
						SchemaMetadata schema = new SchemaMetadata();
						String name = rs.getString("TABLE_SCHEM");
						// the following trick is for Oracle database where the column TABLE_CATALOG is
						// unknown
						String catalog;
						try {
							catalog = rs.getString("TABLE_CATALOG");
						} catch (SQLException e) {
							catalog = "";
						}
						schema.setName(name);
						schema.setCatalog(catalog);
						if (StringUtils.isNotBlank(currentSchema) && currentSchema.equalsIgnoreCase(name)) {
							schema.setCurrent(true);
						}
						boolean addSchema = true;
						if (StringUtils.isBlank(schemasNameFilter)) {
							addSchema = true;
						} else {
							if (StringUtils.containsIgnoreCase(name, schemasNameFilter)) {
								addSchema = true;
							} else {
								addSchema = false;
							}
						}
						if (addSchema) {
							results.add(schema);
						}
					}
				}
				return (results);
			} catch (SQLException se) {
				throw new BroadSQLException(se);
			} finally {
				try {
					if (rs != null) {
						rs.close();
					}
				} catch (SQLException se) {
					throw new BroadSQLException(se);
				}
			}
		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		}
	}

	/**
	 * 
	 * @param schemasName
	 * @throws BroadSQLException
	 */
	public void setSchema(String schemasName) throws BroadSQLException {
		try {
			connection.setSchema(schemasName);
		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		}
	}

	/**
	 * @param catalog
	 * @param schemaPattern a blank/{@code null} pattern is passed through to the JDBC driver as-is,
	 *        which means "do not narrow the search by schema" (matches every schema) - callers that
	 *        want to default to the connection's current schema instead (e.g. {@code DESCR}) must
	 *        resolve that themselves before calling, since some callers (e.g. {@code SHOW COLUMN})
	 *        deliberately rely on the whole-database search
	 * @param tableNamePattern
	 * @param columnNamePattern
	 * @return
	 * @throws BroadSQLException
	 */
	public TreeSet<ColumnMetadata> getColumns(String catalog, String schemaPattern, String tableNamePattern,
			String columnNamePattern) throws BroadSQLException {
		try {
			TreeSet<ColumnMetadata> results = new TreeSet<ColumnMetadata>();
			DatabaseMetaData dmd = connection.getMetaData();
			ResultSet rs = dmd.getColumns(catalog, schemaPattern, tableNamePattern, columnNamePattern);
			try {
				if (rs != null) {
					while (rs.next()) {
						// SPRINT 2409K: the same row mapping as MetadataService
						results.add(MetadataService.toColumn(rs));
					}
				}
				return (results);
			} catch (SQLException se) {
				se.printStackTrace();
				throw new BroadSQLException(se);
			} finally {
				try {
					if (rs != null) {
						rs.close();
					}
				} catch (SQLException se) {
					throw new BroadSQLException(se);
				}
			}
		} catch (SQLException ie) {
			ie.printStackTrace();
			throw new BroadSQLException(ie);
		}
	}

	/**
	 * @param catalog
	 * @param schemaPattern if blank, defaults to the connection's current schema rather than being
	 *        passed through as {@code null} - a {@code null} schema tells the JDBC driver not to
	 *        narrow the search by schema at all, which returns primary keys from every schema whose
	 *        table happens to match {@code tableNamePattern}
	 * @param tableNamePattern
	 * @param columnNamePattern
	 * @return
	 * @throws BroadSQLException
	 */
	public TreeSet<PrimaryKeyMetadata> getPrimaryKeys(String catalog, String schemaPattern, String tableNamePattern,
			String columnNamePattern) throws BroadSQLException {
		try {
			TreeSet<PrimaryKeyMetadata> results = new TreeSet<PrimaryKeyMetadata>();
			DatabaseMetaData dmd = connection.getMetaData();
			if (StringUtils.isBlank(schemaPattern)) {
				schemaPattern = connection.getSchema();
			}
			ResultSet rs = dmd.getPrimaryKeys(catalog, schemaPattern, tableNamePattern);
			try {
				if (rs != null) {
					while (rs.next()) {
						PrimaryKeyMetadata primaryKey = new PrimaryKeyMetadata();
						primaryKey.setName(rs.getString("COLUMN_NAME"));
						primaryKey.setTable(rs.getString("TABLE_NAME"));
						primaryKey.setCatalog(rs.getString("TABLE_CAT"));
						primaryKey.setSchema(rs.getString("TABLE_SCHEM"));
						primaryKey.setPkName(rs.getString("PK_NAME"));
						primaryKey.setKeySec(rs.getInt("KEY_SEQ"));
						results.add(primaryKey);
					}
				}
				return (results);
			} catch (SQLException se) {
				se.printStackTrace();
				throw new BroadSQLException(se);
			} finally {
				try {
					if (rs != null) {
						rs.close();
					}
				} catch (SQLException se) {
					throw new BroadSQLException(se);
				}
			}
		} catch (SQLException ie) {
			ie.printStackTrace();
			throw new BroadSQLException(ie);
		}
	}

	/**
	 * 
	 * @param userName
	 * @param oldPwd
	 * @param newPwd
	 * @throws BroadSQLException
	 */
	public void changePassword(String userName, String oldPwd, String newPwd) throws BroadSQLException {

		// String query = "ALTER USER " + userName + " IDENTIFIED BY \"" + newPwd + "\"
		// replace \"" + oldPwd + "\"";
		String query = null;
		if (this.platform.getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_Oracle)) {
			// Oracle
			query = "ALTER USER " + userName + " IDENTIFIED BY \"" + newPwd + "\" REPLACE \"" + oldPwd + "\"";
		} else if (this.platform.getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_MySQL)) {
			// MySQL above version 5.7.6
			// For MySQL, support only servers above 5.7.6 (most recent version as of Feb
			// 2018)
			// See https://dev.mysql.com/doc/refman/5.7/en/default-privileges.html
			query = "ALTER USER " + userName + " IDENTIFIED BY '" + newPwd + "'";
		} else if (this.platform.getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_PostgreSQL)) {
			// PostgreSQL
			query = "ALTER USER " + userName + " PASSWORD '" + newPwd + "'";
		} else if (this.platform.getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_H2)
				|| this.platform.getDbType().equalsIgnoreCase(SpringPropertiesConfig.DBTYPE_HSQL)) {
			// H2, HSQL
			query = "ALTER USER " + userName + " SET PASSWORD '" + newPwd + "'";
		} else {
			// Not supported for all other types
			throw new BroadSQLException(
					"Operation not supported for database type of '" + this.getPlatform().getDbType() + "'");
		}

		try {
			PreparedStatement stmt = connection.prepareStatement(query);
			stmt.setEscapeProcessing(false);
			stmt.execute();
			stmt.close();
		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		}
	}

	/* JdbcTemplate Implementation
	 * https://examples.javacodegeeks.com/enterprise-java/spring/jdbc/create-data-source-for-jdbctemplate/ 
	 * 
	 */
	public JdbcTemplate getJdbcTemplate() {
		return jdbcTemplate;
	}

	public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Data source for the current database
	 * @param dbDriver
	 * @param url
	 * @param userName
	 * @param password
	 * @return
	 */
	protected DriverManagerDataSource getDataSource(String dbDriver, String url, String userName, String password) {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName(dbDriver);
		dataSource.setUrl(url);
		dataSource.setUsername(userName);
		dataSource.setPassword(password);
		return dataSource;
	}

	/**
	 * Data source for the current database
	 * @return
	 */
	protected DriverManagerDataSource getDataSource() {
		return getDataSource(this.platform.getDbDriver(), 
				this.platform.getUrl(), 
				this.platform.getUserName(),
				this.platform.getUserPassword());
	}
	
	/**
	 * Data source to another database
	 * @param platform
	 * @return
	 */
	public DriverManagerDataSource getDataSource(DatabaseDefinition platform) {
		return getDataSource(platform.getDbDriver(), 
				platform.getUrl(), 
				platform.getUserName(),
				platform.getUserPassword());
	}

	/**
	 * Init JDBC template for this database
	 */
	protected void initJdbcTemplate() {
		dataSource = getDataSource();
		jdbcTemplate = new JdbcTemplate();
		jdbcTemplate.setDataSource(dataSource);
	}
}
