package com.upandcoding.broadsql.dao;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.h2.tools.ChangeFileEncryption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.DatabaseGroupDefinition;
import com.upandcoding.broadsql.dao.model.EnvironmentDefinition;
import com.upandcoding.broadsql.dao.model.TypeDefinition;
import com.upandcoding.broadsql.dao.model.TypeSyncResult;
import com.upandcoding.broadsql.dao.model.UserScriptLine;

public class DatabaseDefinitionsVault {

	private final static Logger log = LoggerFactory.getLogger(DatabaseDefinitionsVault.class);

	private static final int H2_PWD_ERROR_CODE = 90050;
	private static final int H2_PWD_DB_ERROR_CODE = 90049;
	private static final int H2_ERR_CODE = 0;

	public static final String H2_ADMIN = "ADMIN";

	/**
	 * Canonical ID of the built-in, non-production Environment used by standalone (no Database Group)
	 * connections such as those {@code PULL ... AS H2} creates - see docs/CONNECTION_MODEL.md.
	 * {@link #seedLocalEnvironmentIfNeeded} guarantees a case-insensitive match for this ID always exists
	 * after {@link #load()}; {@link #resolveLocalEnvironmentId()} is the only place that should be used
	 * to reference "the LOCAL environment" by ID, since it returns whatever casing is actually persisted
	 * (this constant's exact casing only if no pre-existing case variant was found to preserve).
	 */
	public static final String LOCAL_ENVIRONMENT_ID = "LOCAL";

	private HashMap<String, DatabaseDefinition> databaseConnections = new HashMap<>();
	private String password = null;
	private String adminName = H2_ADMIN;
	private String fileName; 		// if not access to dataSource
	private DataSource dataSource; 	// if not access to fileName

	private TreeSet<String> ids = new TreeSet<>();
	private TreeSet<String> groups = new TreeSet<>();
	private TreeSet<String> environments = new TreeSet<>();
	private TreeSet<String> dbTypes = new TreeSet<>();
	private HashMap<String, String> dbDrivers = new HashMap<>();

	public DatabaseDefinitionsVault() {
		this(null, null);
	}

	public DatabaseDefinitionsVault(String fName, String pwd) {
		fileName = fName;
		password = pwd;
	}

	public DatabaseDefinitionsVault(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 *
	 * @param key the connection ID in the CDF
	 * @return
	 */
	public DatabaseDefinition getDatabaseConnection(String key) {
		DatabaseDefinition p;
		p = (DatabaseDefinition) databaseConnections.get(key);
		return p;
	}

	public HashMap<String, DatabaseDefinition> getPlatforms() {
		return (databaseConnections);
	}

	public boolean contains(String key) {
		return (databaseConnections.containsKey(key));
	}

	public String getAdminName() {
		return adminName;
	}

	public void setAdminName(String adminName) {
		this.adminName = adminName;
	}

	public DataSource getDataSource() {
		return dataSource;
	}

	public void setDataSource(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 *
	 * @throws BroadSQLException
	 */
	public void load() throws BroadSQLException {
		if (StringUtils.isNotBlank(this.fileName)) {
			//log.debug("Loading CDF from file: {}", fileName);
			loadFromFile();
		} else {
			//log.debug("Loading CDF from dataSource: {}", "dataSource");
			loadFromDataSource();
		}
	}// load

	private void loadFromFile() throws BroadSQLException {
		if (this.databaseConnections != null) {
			this.databaseConnections.clear();
		}
		if (ids != null) {
			ids.clear();
		}
		groups.clear();
		environments.clear();
		dbTypes.clear();
		dbDrivers.clear();
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			/* for AES encryption, 2 passwords must be sent, separated by a space: 
			 * String pwds = "filepwd userpwd";
			 * 1st password: is the db password, the 2nd password is the ADMIN password
			 * In this implementation, the choice is to use same file pwd as ADMIN pwd
			 */
			String aesPassword = password + " " + password;
			Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
			migrateEnvironmentSchemaIfNeeded(conn);
			migrateGroupAndEnvironmentColumnsIfNeeded(conn);
			seedLocalEnvironmentIfNeeded(conn);
			migrateTypeSchemaIfNeeded(conn);
			Statement stat = conn.createStatement();

			// Load connections
			// String query = "SELECT ID, DRIVER, URL, NAME, TYPE_ID, USER_NAME,
			// USER_PASSWORD, ENVIRONMENT_ID, INSTANCE_ID, COMMENT FROM CONNECTIONS where
			// STATUS_ID='ACTIVE' ORDER BY ID";
			String query = "SELECT C.ID, T.DRIVER, C.URL, C.NAME, C.TYPE_ID, C.USER_NAME, C.USER_PASSWORD, C.ENVIRONMENT_ID, C.INSTANCE_ID, C.COMMENT, C.STATUS_ID FROM CONNECTIONS C LEFT JOIN TYPE T ON C.TYPE_ID=T.ID WHERE C.STATUS_ID='"
					+ DatabaseDefinition.STATUS_ACTIVE + "' ORDER BY C.ID";
			// log.debug(query);
			ResultSet results = stat.executeQuery(query);
			while (results.next()) {
				String id = results.getString("ID");
				String driver = results.getString("DRIVER");
				String url = results.getString("URL");
				String dbType = results.getString("TYPE_ID");
				String dbName = results.getString("NAME");
				String uName = results.getString("USER_NAME");
				String uPwd = results.getString("USER_PASSWORD");
				String environment = results.getString("ENVIRONMENT_ID");
				String instance_id = results.getString("INSTANCE_ID");
				String comment = results.getString("COMMENT");
				/*
				if (SpringPropertiesConfig.DBTYPE_Oracle.equals(dbType)) {
					log.debug("ID: {} with status: {}", results.getString("ID"), results.getString("STATUS_ID"));
				}
				*/
				DatabaseDefinition connection = new DatabaseDefinition(id, dbType, driver, url, uName, uPwd, dbName);
				connection.setEnvironment(environment);
				connection.setDatabaseGroup(instance_id);
				connection.setComment(comment);
				this.databaseConnections.put(id, connection);
				ids.add(id);
				/*
				if (SpringPropertiesConfig.DBTYPE_Oracle.equals(dbType)) {
					log.debug("Load: {}", connection.toString());
				}
				*/
			}

			// Load types
			query = "SELECT ID, DRIVER FROM TYPE WHERE STATUS_ID='" + DatabaseDefinition.STATUS_ACTIVE + "' ORDER BY ID";
			results = stat.executeQuery(query);
			while (results.next()) {
				dbTypes.add(results.getString(1));
				dbDrivers.put(results.getString(1), results.getString(2));
			}

			// Load database groups
			query = "SELECT ID FROM INSTANCE WHERE STATUS_ID='" + DatabaseDefinition.STATUS_ACTIVE + "' ORDER BY ID";
			results = stat.executeQuery(query);
			while (results.next()) {
				groups.add(results.getString(1));
			}

			// Load environments
			query = "SELECT ID FROM ENVIRONMENT WHERE STATUS_ID='" + DatabaseDefinition.STATUS_ACTIVE + "' ORDER BY ID";
			results = stat.executeQuery(query);
			while (results.next()) {
				environments.add(results.getString(1));
			}

			stat.close();
			conn.close();
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
	}

	/**
	 * One-time, idempotent schema migration from the pre-04/09/2026 terminology (table {@code LANDSCAPE},
	 * column {@code CONNECTIONS.LANDSCAPE_ID}) to the corrected one ({@code ENVIRONMENT}/{@code
	 * ENVIRONMENT_ID}) - see docs/TECHNICAL_CHANGE.md, 04/09/2026, "Instance/Landscape terminology
	 * correction". Every CDF created before this fix physically has the old names on disk; the rest of
	 * this class now only ever reads/writes the new ones, so without this, loading such a CDF would fail
	 * immediately with "table ENVIRONMENT not found". Runs first, before any query in {@link #loadFromFile()}
	 * / {@link #loadFromDataSource()}, on every {@link #load()} - cheap and a no-op once migrated (checked
	 * via {@code INFORMATION_SCHEMA}, not by catching the failure), so safe to call unconditionally rather
	 * than tracking migration state separately. Leaves {@code CONSTRAINT_E71E}/{@code CONSTRAINT_E71EF1}
	 * (the {@code CONNECTIONS.ENVIRONMENT_ID -> ENVIRONMENT.ID} foreign key - see {@link #saveEnvironment}'s
	 * javadoc for why it matters that this is actually enforced) intact: H2's {@code RENAME TO} updates a
	 * table or column's dependents by internal object ID, not by name, so the constraint keeps working
	 * across both renames without needing to be dropped and recreated.
	 */
	private void migrateEnvironmentSchemaIfNeeded(Connection conn) throws SQLException {
		if (!tableExists(conn, "ENVIRONMENT") && tableExists(conn, "LANDSCAPE")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE LANDSCAPE RENAME TO ENVIRONMENT");
			}
		}
		if (!columnExists(conn, "CONNECTIONS", "ENVIRONMENT_ID") && columnExists(conn, "CONNECTIONS", "LANDSCAPE_ID")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE CONNECTIONS ALTER COLUMN LANDSCAPE_ID RENAME TO ENVIRONMENT_ID");
			}
		}
		conn.commit();
	}// migrateEnvironmentSchemaIfNeeded

	/**
	 * One-time, idempotent schema migration adding the columns Phase 1 of the {@code
	 * docs/CONNECTION_MODEL.md} rework needs: {@code ENVIRONMENT.PRODUCTION} and {@code ENVIRONMENT.COMMENT}
	 * (Environment gains a full CRUD lifecycle and needs both), and {@code INSTANCE.COMMENT} (the "Database
	 * Group" concept needs a Comment field, per that document's §4, which the {@code INSTANCE} table
	 * never had). Guarded by {@code columnExists} the same way {@link #migrateEnvironmentSchemaIfNeeded}
	 * is, so it is a no-op on every CDF this has already run against. {@code ADD COLUMN IF NOT EXISTS}
	 * would achieve the same idempotence more tersely, but the explicit guard keeps this migration in the
	 * same style as its predecessor.
	 *
	 * <p>Also guards {@code ENVIRONMENT.DESCR}/{@code INSTANCE.DESCR} - every real CDF has always had
	 * these, so this is a no-op there, but {@link #seedLocalEnvironmentIfNeeded} (added later, standalone-
	 * connections model) unconditionally writes {@code ENVIRONMENT.DESCR} on every {@link #load()}, which
	 * surfaced a handful of minimal test-only schemas (e.g. a bare {@code (ID, STATUS_ID)} table) that
	 * never had it - this migration is the general fix, not a workaround in the seeding method itself.
	 */
	private void migrateGroupAndEnvironmentColumnsIfNeeded(Connection conn) throws SQLException {
		if (!columnExists(conn, "ENVIRONMENT", "DESCR")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE ENVIRONMENT ADD COLUMN DESCR VARCHAR");
			}
		}
		if (!columnExists(conn, "INSTANCE", "DESCR")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE INSTANCE ADD COLUMN DESCR VARCHAR");
			}
		}
		if (!columnExists(conn, "ENVIRONMENT", "PRODUCTION")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE ENVIRONMENT ADD COLUMN PRODUCTION BOOLEAN DEFAULT FALSE");
			}
		}
		if (!columnExists(conn, "ENVIRONMENT", "COMMENT")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE ENVIRONMENT ADD COLUMN COMMENT VARCHAR");
			}
		}
		if (!columnExists(conn, "INSTANCE", "COMMENT")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE INSTANCE ADD COLUMN COMMENT VARCHAR");
			}
		}
		conn.commit();
	}// migrateGroupAndEnvironmentColumnsIfNeeded

	/**
	 * One-time, idempotent row-seed guaranteeing every CDF - new or upgraded - ends up with a built-in
	 * {@link #LOCAL_ENVIRONMENT_ID} Environment row, active and non-production. Needed so
	 * {@code PULL ... AS H2}'s standalone connections always have a real, valid Environment to reference
	 * (see docs/CONNECTION_MODEL.md and docs/TODO.md's now-resolved "PULL's Environment doesn't validate
	 * against the ENVIRONMENT table" item) - unlike Database Group, which never had this problem, nothing
	 * previously guaranteed any Environment row existed at all.
	 *
	 * <p>Must run after {@link #migrateGroupAndEnvironmentColumnsIfNeeded} in the {@link #load()} chain -
	 * it writes to {@code ENVIRONMENT.PRODUCTION}/{@code COMMENT}, which that migration is what adds to a
	 * pre-Phase-1 CDF.
	 *
	 * <p>The existence check is deliberately case-insensitive ({@code UPPER(ID)=UPPER(...)}, matching
	 * {@link #environmentExists}, not the exact-match {@link #exactEnvironmentIdExists}) and checks both
	 * active and inactive rows - per docs/CONNECTION_MODEL.md §5.1, Environment IDs are already unique
	 * case-insensitively project-wide (enforced by {@link #saveEnvironment} whenever a new row is
	 * created), so at most one casing of "local" can ever exist; this seed must respect that same
	 * invariant rather than risk inserting a second, differently-cased row alongside a pre-existing one
	 * (e.g. a user-created {@code Local}). If any case variant already exists, it is left completely
	 * untouched - its Description/Comment/Production/Active values are the user's own and are never
	 * overwritten - only the canonical, all-uppercase {@code LOCAL} row is inserted, and only when no
	 * variant exists at all. This is also why callers must never hardcode the literal {@code "LOCAL"}
	 * string when referencing this Environment on a Connection - see {@link #resolveLocalEnvironmentId()}.
	 */
	private void seedLocalEnvironmentIfNeeded(Connection conn) throws SQLException {
		boolean alreadyExists;
		try (PreparedStatement stat = conn.prepareStatement("SELECT COUNT(*) FROM ENVIRONMENT WHERE UPPER(ID)=UPPER(?)")) {
			stat.setString(1, LOCAL_ENVIRONMENT_ID);
			try (ResultSet rs = stat.executeQuery()) {
				rs.next();
				alreadyExists = rs.getInt(1) > 0;
			}
		}
		if (!alreadyExists) {
			try (PreparedStatement insert = conn.prepareStatement(
					"INSERT INTO ENVIRONMENT (ID, DESCR, PRODUCTION, COMMENT, STATUS_ID) VALUES (?, ?, ?, ?, ?)")) {
				insert.setString(1, LOCAL_ENVIRONMENT_ID);
				insert.setString(2, "Local");
				insert.setBoolean(3, false);
				insert.setString(4, "Local database or working copy");
				insert.setString(5, DatabaseDefinition.STATUS_ACTIVE);
				insert.executeUpdate();
			}
		}
		conn.commit();
	}// seedLocalEnvironmentIfNeeded

	/**
	 * One-time, idempotent schema migration adding {@code TYPE.MODE} and {@code TYPE.DESCR} - the CDF's
	 * pre-existing {@code TYPE} table only ever had {@code ID}/{@code DRIVER}/{@code STATUS_ID} (see
	 * {@code docs/SUPPORTED_DATABASES.md}, section 2). Needed for {@link #syncTypeCatalog} to have
	 * somewhere to store a connection mode (only meaningful for Apache Derby's {@code Embedded}/
	 * {@code Client} split - every other recognized type uses {@code Default}) and a human-readable
	 * description. Guarded by {@code columnExists}, same style as
	 * {@link #migrateGroupAndEnvironmentColumnsIfNeeded}, so it is a no-op on a CDF this has already run
	 * against. Runs unconditionally on every {@link #load()} - cheap, and needed before
	 * {@link #syncTypeCatalog} can write either column - unlike {@link #syncTypeCatalog} itself, which
	 * only ever runs when {@code SYNC TYPE CATALOG} is explicitly typed.
	 */
	private void migrateTypeSchemaIfNeeded(Connection conn) throws SQLException {
		if (!columnExists(conn, "TYPE", "MODE")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE TYPE ADD COLUMN MODE VARCHAR");
			}
		}
		if (!columnExists(conn, "TYPE", "DESCR")) {
			try (Statement stat = conn.createStatement()) {
				stat.execute("ALTER TABLE TYPE ADD COLUMN DESCR VARCHAR");
			}
		}
		conn.commit();
	}// migrateTypeSchemaIfNeeded

	private boolean tableExists(Connection conn, String tableName) throws SQLException {
		try (PreparedStatement stat = conn.prepareStatement("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME=?")) {
			stat.setString(1, tableName);
			try (ResultSet rs = stat.executeQuery()) {
				rs.next();
				return rs.getInt(1) > 0;
			}
		}
	}// tableExists

	private boolean columnExists(Connection conn, String tableName, String columnName) throws SQLException {
		try (PreparedStatement stat = conn.prepareStatement("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME=? AND COLUMN_NAME=?")) {
			stat.setString(1, tableName);
			stat.setString(2, columnName);
			try (ResultSet rs = stat.executeQuery()) {
				rs.next();
				return rs.getInt(1) > 0;
			}
		}
	}// columnExists

	/**
	 * Backs up the CDF to a timestamped {@code .zip} file - the mandatory first step of
	 * {@link #swapInstanceAndEnvironment()} (docs/TECHNICAL_CHANGE.md, 2026-09-05), since that operation
	 * has no automatic "already applied" safeguard the way {@link #migrateEnvironmentSchemaIfNeeded} does.
	 *
	 * <p>Uses H2's own {@code BACKUP TO} statement, run over a live JDBC connection to the CDF, rather
	 * than an OS-level file copy of {@code <fileName>.mv.db}. A plain file copy was tried first and fails
	 * every time this is actually useful: whenever {@code FIX INSTANCE ENVIRONMENT SWAP} is run, the user
	 * is necessarily already connected to {@code $CDF}, which holds the {@code .mv.db} file locked for as
	 * long as that connection is open - Windows enforces this at the OS level, so
	 * {@code java.nio.file.Files.copy} against it fails with {@code FileSystemException}
	 * ("the process cannot access the file..."). {@code BACKUP TO}, run through the engine that already
	 * holds the lock, has no such problem - it is H2's documented mechanism for backing up a database
	 * while it is in active use.
	 *
	 * <p>fileName-based only, like the operation it protects; not meaningful for a {@code DataSource}-
	 * backed vault (a bare in-memory one, as most tests use), which has no physical file to back up.
	 *
	 * @return the absolute path of the backup {@code .zip} file created
	 */
	public String backupCdfFile() throws BroadSQLException {
		if (StringUtils.isBlank(fileName)) {
			throw new BroadSQLException("Cannot back up: this vault is not backed by a CDF file.");
		}
		String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
		Path target = Paths.get(fileName + "_backup_" + timestamp + ".zip");
		String escapedPath = target.toAbsolutePath().toString().replace("'", "''");
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					Statement stat = conn.createStatement()) {
				stat.execute("BACKUP TO '" + escapedPath + "'");
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		return target.toAbsolutePath().toString();
	}// backupCdfFile

	/**
	 * One-time correction for every CDF that predates this fix (docs/TECHNICAL_CHANGE.md, 2026-09-05,
	 * "Instance/Environment data swap"): {@code CONNECTIONS.INSTANCE_ID}/{@code ENVIRONMENT_ID}, and the
	 * {@code INSTANCE}/{@code ENVIRONMENT} reference tables, have always held each other's data - every
	 * real connection's "instance" value is actually a deployment stage (e.g. {@code DEV}, {@code QA},
	 * {@code PROD}) and its "environment" value is actually a product/application name (e.g. {@code DESK},
	 * {@code SALES}). This physically swaps both, preserving every value - nothing is created, deleted,
	 * or reinterpreted, only relocated to the column/table whose name actually matches what it holds.
	 * Every existing piece of code that reads {@code getInstance()}/{@code getEnvironment()} (the GUI,
	 * the shell wizard, {@code SHOW CONNECTION}, the "Instances" tab's CRUD lifecycle) is already wired
	 * correctly and needs no change once this has run.
	 *
	 * <p>Unlike {@link #migrateEnvironmentSchemaIfNeeded}, this has <b>no safe "already applied" signal</b>
	 * to detect automatically - both {@code INSTANCE_ID}/{@code ENVIRONMENT_ID} and {@code INSTANCE}/
	 * {@code ENVIRONMENT} exist before and after, just with contents exchanged, so there is no structural
	 * difference to check for the way a vanished old name gives one. Per the user's explicit instruction,
	 * this is therefore NOT wired into {@link #load()} - it must be triggered deliberately, exactly once
	 * per CDF, via {@code FIX INSTANCE ENVIRONMENT SWAP}. Running it a second time on an already-swapped
	 * CDF silently swaps the data right back to wrong. Always preceded by {@link #backupCdfFile()}.
	 *
	 * @return the absolute path of the backup file {@link #backupCdfFile()} created before the swap
	 */
	public String swapInstanceAndEnvironment() throws BroadSQLException {
		String backupPath = backupCdfFile();
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword)) {
				conn.setAutoCommit(false);
				try (Statement stat = conn.createStatement()) {
					stat.execute("ALTER TABLE CONNECTIONS ALTER COLUMN INSTANCE_ID RENAME TO INSTANCE_ID_SWAP_TMP");
					stat.execute("ALTER TABLE CONNECTIONS ALTER COLUMN ENVIRONMENT_ID RENAME TO INSTANCE_ID");
					stat.execute("ALTER TABLE CONNECTIONS ALTER COLUMN INSTANCE_ID_SWAP_TMP RENAME TO ENVIRONMENT_ID");

					stat.execute("ALTER TABLE INSTANCE RENAME TO INSTANCE_SWAP_TMP");
					stat.execute("ALTER TABLE ENVIRONMENT RENAME TO INSTANCE");
					stat.execute("ALTER TABLE INSTANCE_SWAP_TMP RENAME TO ENVIRONMENT");
					conn.commit();
				} catch (SQLException ex) {
					conn.rollback();
					throw ex;
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		load();
		return backupPath;
	}// swapInstanceAndEnvironment

	private void loadFromDataSource() throws BroadSQLException {
		if (this.databaseConnections != null) {
			this.databaseConnections.clear();
		}
		if (ids != null) {
			ids.clear();
		}
		groups.clear();
		environments.clear();
		dbTypes.clear();
		dbDrivers.clear();
		try {
			Connection conn = dataSource.getConnection();
			migrateEnvironmentSchemaIfNeeded(conn);
			migrateGroupAndEnvironmentColumnsIfNeeded(conn);
			seedLocalEnvironmentIfNeeded(conn);
			migrateTypeSchemaIfNeeded(conn);
			Statement stat = conn.createStatement();

			// Load connections
			// String query = "SELECT ID, DRIVER, URL, NAME, TYPE_ID, USER_NAME,
			// USER_PASSWORD, ENVIRONMENT_ID, INSTANCE_ID, COMMENT FROM CONNECTIONS where
			// STATUS_ID='ACTIVE' ORDER BY ID";
			String query = "SELECT C.ID, T.DRIVER, C.URL, C.NAME, C.TYPE_ID, C.USER_NAME, C.USER_PASSWORD, C.ENVIRONMENT_ID, C.INSTANCE_ID, C.COMMENT, C.STATUS_ID FROM CONNECTIONS C LEFT JOIN TYPE T ON C.TYPE_ID=T.ID WHERE C.STATUS_ID='"
					+ DatabaseDefinition.STATUS_ACTIVE + "' ORDER BY C.ID";
			// log.debug(query);
			ResultSet results = stat.executeQuery(query);
			while (results.next()) {
				String id = results.getString("ID");
				String driver = results.getString("DRIVER");
				String url = results.getString("URL");
				String dbType = results.getString("TYPE_ID");
				String dbName = results.getString("NAME");
				String uName = results.getString("USER_NAME");
				String uPwd = results.getString("USER_PASSWORD");
				String environment = results.getString("ENVIRONMENT_ID");
				String instance_id = results.getString("INSTANCE_ID");
				String comment = results.getString("COMMENT");
				/*
				if (SpringPropertiesConfig.DBTYPE_Oracle.equals(dbType)) {
					log.debug("ID: {} with status: {}", results.getString("ID"), results.getString("STATUS_ID"));
				}
				*/
				DatabaseDefinition connection = new DatabaseDefinition(id, dbType, driver, url, uName, uPwd, dbName);
				connection.setEnvironment(environment);
				connection.setDatabaseGroup(instance_id);
				connection.setComment(comment);
				this.databaseConnections.put(id, connection);
				ids.add(id);
				/*
				if (SpringPropertiesConfig.DBTYPE_Oracle.equals(dbType)) {
					log.debug("Load: {}", connection.toString());
				}
				*/
			}

			// Load types
			query = "SELECT ID, DRIVER FROM TYPE WHERE STATUS_ID='" + DatabaseDefinition.STATUS_ACTIVE + "' ORDER BY ID";
			results = stat.executeQuery(query);
			while (results.next()) {
				dbTypes.add(results.getString(1));
				dbDrivers.put(results.getString(1), results.getString(2));
			}

			// Load database groups
			query = "SELECT ID FROM INSTANCE WHERE STATUS_ID='" + DatabaseDefinition.STATUS_ACTIVE + "' ORDER BY ID";
			results = stat.executeQuery(query);
			while (results.next()) {
				groups.add(results.getString(1));
			}

			// Load environments
			query = "SELECT ID FROM ENVIRONMENT WHERE STATUS_ID='" + DatabaseDefinition.STATUS_ACTIVE + "' ORDER BY ID";
			results = stat.executeQuery(query);
			while (results.next()) {
				environments.add(results.getString(1));
			}

			stat.close();
			conn.close();
		} catch (SQLException ex) {
			throw new BroadSQLException(ex);
		}
	}

	public List<String> getInactiveIds() throws BroadSQLException {
		List<String> ids = new ArrayList<>();
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			/* for AES encryption, 2 passwords must be sent, separated by a space:
			 * String pwds = "filepwd userpwd";
			 * 1st password: is the db password, the 2nd password is the ADMIN password
			 * In this implementation, the choice is to use same file pwd as ADMIN pwd
			 */
			String aesPassword = password + " " + password;
			Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
			Statement stat = conn.createStatement();

			String query = "SELECT ID FROM CONNECTIONS WHERE STATUS_ID='" + DatabaseDefinition.STATUS_INACTIVE + "' ORDER BY ID";
			ResultSet results = stat.executeQuery(query);
			while (results.next()) {
				ids.add(results.getString(1));
			}

			stat.close();
			conn.close();
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		return ids;
	}

	/**
	 * True if {@code id} names a {@code CONNECTIONS} row currently {@code INACTIVE} - {@code false}
	 * for an active connection or an unknown ID alike (callers that need to tell "inactive" apart
	 * from "doesn't exist" combine this with {@link #contains(String)}, which only ever sees active
	 * rows since {@link #load()} filters on {@code STATUS_ID='ACTIVE'}). Like {@link #getInactiveIds()}
	 * itself, only meaningful for a {@code fileName}-based vault (the real CDF always is one); a vault
	 * built without a file name (a {@code DataSource}-backed or bare in-memory one, as many tests use)
	 * has no inactive rows to speak of, so this returns {@code false} rather than attempting a
	 * {@code jdbc:h2:null;...} connection.
	 */
	public boolean isInactiveConnection(String id) throws BroadSQLException {
		if (StringUtils.isBlank(fileName)) {
			return false;
		}
		return getInactiveIds().contains(id);
	}

	/**
	 * Every {@code CONNECTIONS} row currently {@code INACTIVE}, full detail (not just the ID, unlike
	 * {@link #getInactiveIds()}) - backs the config screen's "Inactive connections" view.
	 */
	public List<DatabaseDefinition> getInactiveConnectionDetails() throws BroadSQLException {
		List<DatabaseDefinition> result = new ArrayList<>();
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					Statement stat = conn.createStatement();
					ResultSet results = stat.executeQuery(
							"SELECT C.ID, T.DRIVER, C.URL, C.NAME, C.TYPE_ID, C.USER_NAME, C.USER_PASSWORD, C.ENVIRONMENT_ID, C.INSTANCE_ID, C.COMMENT FROM CONNECTIONS C LEFT JOIN TYPE T ON C.TYPE_ID=T.ID WHERE C.STATUS_ID='"
									+ DatabaseDefinition.STATUS_INACTIVE + "' ORDER BY C.ID")) {
				while (results.next()) {
					DatabaseDefinition connection = new DatabaseDefinition(results.getString("ID"), results.getString("TYPE_ID"), results.getString("DRIVER"), results.getString("URL"),
							results.getString("USER_NAME"), results.getString("USER_PASSWORD"), results.getString("NAME"));
					connection.setEnvironment(results.getString("ENVIRONMENT_ID"));
					connection.setDatabaseGroup(results.getString("INSTANCE_ID"));
					connection.setComment(results.getString("COMMENT"));
					connection.setStatus(DatabaseDefinition.STATUS_INACTIVE);
					result.add(connection);
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		return result;
	}// getInactiveConnectionDetails

	/**
	 * Reactivates an inactive connection (status back to {@code ACTIVE}), unchanged otherwise - the
	 * counterpart to {@link #softDeleteDatabaseDefinition(String)}. Used by {@code REACTIVATE
	 * CONNECTION} and by the config screen's "Inactive connections" view and ID-collision dialog.
	 * Does not check the current status itself (callers already know it's inactive, having reached
	 * this ID through {@link #isInactiveConnection(String)}) and reactivating an already-active
	 * connection is harmless (a no-op {@code UPDATE}).
	 */
	public void reactivateDatabaseDefinition(String id) throws BroadSQLException {
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
			String query = "UPDATE CONNECTIONS SET STATUS_ID=? WHERE ID=?";
			PreparedStatement stat = conn.prepareStatement(query);
			stat.setString(1, DatabaseDefinition.STATUS_ACTIVE);
			stat.setString(2, id);
			stat.executeUpdate();
			stat.close();
			conn.commit();
			conn.close();
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		load();
	}// reactivateDatabaseDefinition

	public void saveDatabaseDefinition(DatabaseDefinition connection) throws BroadSQLException {
		if (connection.isNotNull() && StringUtils.isNotBlank(connection.getId())) {
			assertEnvironmentIsValid(connection.getEnvironment());
			assertGroupIsValidIfSpecified(connection.getDatabaseGroup());
			assertGroupEnvironmentPairAvailable(connection.getDatabaseGroup(), connection.getEnvironment(), connection.getId());
			try {
				String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
				Class.forName("org.h2.Driver");
				/* for AES encryption, 2 passwords must be sent, separated by a space:
				 * String pwds = "filepwd userpwd";
				 * 1st password: is the db password, the 2nd password is the ADMIN password
				 * In this implementation, the choice is to use same file pwd as ADMIN pwd
				 */
				String aesPassword = password + " " + password;
				Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
				PreparedStatement stat = null;
				String query = "";
				if (databaseConnections.containsKey(connection.getId())) {
					// Update an existing connection
					// query = "UPDATE CONNECTIONS SET NAME=?, DRIVER=?, URL=?, TYPE_ID=?,
					// USER_NAME=?, USER_PASSWORD=?, INSTANCE_ID=?, ENVIRONMENT_ID=?, COMMENT=?,
					// LAST_MODIFIED=CURRENT_TIMESTAMP() WHERE ID= ?";
					query = "UPDATE CONNECTIONS SET NAME=?, URL=?, TYPE_ID=?, USER_NAME=?, USER_PASSWORD=?, INSTANCE_ID=?, ENVIRONMENT_ID=?, COMMENT=?, STATUS_ID=?, LAST_MODIFIED=CURRENT_TIMESTAMP() WHERE ID= ?";
					stat = conn.prepareStatement(query);
					stat.setString(1, connection.getDbName());
					// stat.setString(2, connection.getDbDriver());
					stat.setString(2, connection.getUrl());
					stat.setString(3, connection.getDbType());
					stat.setString(4, connection.getUserName());
					stat.setString(5, connection.getUserPassword());
					stat.setString(6, connection.getDatabaseGroup());
					stat.setString(7, connection.getEnvironment());
					stat.setString(8, connection.getComment());
					stat.setString(9, connection.getStatus());
					stat.setString(10, connection.getId());
				} else {
					// Create a new connection
					// query = "INSERT INTO CONNECTIONS (ID, NAME, DRIVER, URL, TYPE_ID, USER_NAME,
					// USER_PASSWORD, INSTANCE_ID, ENVIRONMENT_ID, STATUS_ID, COMMENT) VALUES
					// (?,?,?,?,?,?,?,?,?,?,?)";
					query = "INSERT INTO CONNECTIONS (ID, NAME, URL, TYPE_ID, USER_NAME, USER_PASSWORD, INSTANCE_ID, ENVIRONMENT_ID, STATUS_ID, COMMENT) VALUES (?,?,?,?,?,?,?,?,?,?)";
					stat = conn.prepareStatement(query);
					stat.setString(1, connection.getId());
					stat.setString(2, connection.getDbName());
					// stat.setString(3, connection.getDbDriver());
					stat.setString(3, connection.getUrl());
					stat.setString(4, connection.getDbType());
					stat.setString(5, connection.getUserName());
					stat.setString(6, connection.getUserPassword());
					stat.setString(7, connection.getDatabaseGroup());
					stat.setString(8, connection.getEnvironment());
					stat.setString(9, DatabaseDefinition.STATUS_ACTIVE);
					stat.setString(10, connection.getComment());
				}
				stat.executeUpdate();
				conn.commit();
				stat.close();
				conn.close();
			} catch (SQLException | ClassNotFoundException ex) {
				throw new BroadSQLException(ex);
			}
		}
	}// saveConnection

	/**
	 * Enforces that every Connection references a real Environment - Environment is mandatory on a
	 * Connection (unlike Database Group, which is optional; see {@link #assertGroupIsValidIfSpecified}).
	 * Rejects a blank ID outright, and otherwise requires an exact-match {@link #exactEnvironmentIdExists}
	 * hit - not the case-insensitive {@link #environmentExists} - so this stays consistent with every
	 * other exact-match use of {@code ENVIRONMENT_ID} elsewhere in this class (e.g.
	 * {@link #assertGroupEnvironmentPairAvailable}'s collision check, {@link #getConnectionsForGroup}'s
	 * ordering). This is why {@link #resolveLocalEnvironmentId()} exists: a caller must pass the exact
	 * persisted casing, never assume a literal {@code "LOCAL"}.
	 *
	 * <p>Existence-only, deliberately not an active/inactive check - a connection pointing at an
	 * already-inactive Environment is a separate, pre-existing gap (docs/TODO.md), out of scope here.
	 */
	private void assertEnvironmentIsValid(String environmentId) throws BroadSQLException {
		if (StringUtils.isBlank(environmentId)) {
			throw new BroadSQLException("Environment is required for a Connection.");
		}
		if (!exactEnvironmentIdExists(environmentId)) {
			throw new BroadSQLException("Environment '" + environmentId + "' does not exist.");
		}
	}// assertEnvironmentIsValid

	/**
	 * Enforces that a Connection's Database Group, when specified, references a real Database Group.
	 * Unlike {@link #assertEnvironmentIsValid}, a blank value is valid here - it means the Connection is
	 * standalone (no Database Group) - so this is a no-op in that case, not a rejection.
	 *
	 * <p>Existence-only, same active/inactive scope note as {@link #assertEnvironmentIsValid}.
	 */
	private void assertGroupIsValidIfSpecified(String groupId) throws BroadSQLException {
		if (StringUtils.isBlank(groupId)) {
			return;
		}
		if (!groupExists(groupId)) {
			throw new BroadSQLException("Database Group '" + groupId + "' does not exist.");
		}
	}// assertGroupIsValidIfSpecified

	/**
	 * Enforces {@code docs/CONNECTION_MODEL.md} §3.1: a Database Group may contain at most one
	 * Connection per Environment. No-op if either {@code groupId} or {@code environmentId} is blank -
	 * {@code groupId} blank means the connection is standalone (not subject to this rule at all, by
	 * design - see docs/CONNECTION_MODEL.md's standalone-connections model); {@code environmentId} blank
	 * should no longer occur for a connection that passed {@link #assertEnvironmentIsValid} first, but
	 * the guard is kept for defense in depth (e.g. pre-existing connections saved before that check
	 * existed). Checks active <b>and</b> inactive connections, consistent with
	 * {@link #assertNoConnectionsReferenceGroup}/{@link #assertNoConnectionsReferenceEnvironment} -
	 * the {@code INSTANCE_ID}/{@code ENVIRONMENT_ID} foreign keys are {@code NOCHECK}, so this is the
	 * only thing enforcing the rule. {@code excludingConnectionId} is the connection being saved -
	 * excluded so that re-saving a connection unchanged, or under its own existing ID, never conflicts
	 * with itself.
	 */
	private void assertGroupEnvironmentPairAvailable(String groupId, String environmentId, String excludingConnectionId) throws BroadSQLException {
		if (StringUtils.isBlank(groupId) || StringUtils.isBlank(environmentId)) {
			return;
		}
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					PreparedStatement stat = conn.prepareStatement("SELECT ID FROM CONNECTIONS WHERE INSTANCE_ID=? AND ENVIRONMENT_ID=? AND ID<>?")) {
				stat.setString(1, groupId);
				stat.setString(2, environmentId);
				stat.setString(3, StringUtils.defaultString(excludingConnectionId));
				try (ResultSet rs = stat.executeQuery()) {
					if (rs.next()) {
						throw new BroadSQLException("Database Group '" + groupId + "' already has a connection for environment '" + environmentId + "': '"
								+ rs.getString(1) + "'.");
					}
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
	}// assertGroupEnvironmentPairAvailable

	/**
	 * Inserts a new row into the CDF's {@code INSTANCE} table. Reloads the vault afterward, same as
	 * {@link #saveDatabaseDefinition}, so {@link #getGroups()} reflects the new row immediately.
	 *
	 * <p>Plain insert-only convenience, kept separate from the fuller {@link #saveGroup(DatabaseGroupDefinition)}
	 * lifecycle below. {@code CommandPull} no longer calls this (PULL-created connections are standalone,
	 * per docs/CONNECTION_MODEL.md) - this overload remains a plain test/utility convenience.
	 */
	public void saveGroup(String id, String descr) throws BroadSQLException {
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
			String query = "INSERT INTO INSTANCE (ID, DESCR, STATUS_ID) VALUES (?, ?, ?)";
			PreparedStatement stat = conn.prepareStatement(query);
			stat.setString(1, id);
			stat.setString(2, descr);
			stat.setString(3, DatabaseDefinition.STATUS_ACTIVE);
			stat.executeUpdate();
			conn.commit();
			stat.close();
			conn.close();
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		load();
	}// saveGroup

	/**
	 * Every row of the CDF's {@code INSTANCE} table (active and inactive), ordered by ID - backs the
	 * "Database Groups" tab. {@link #getGroups()} is the narrower, active-only, ID-only view used to
	 * populate the connection form's Database Group combo box.
	 *
	 * <p>Named {@code getGroupDetails}/{@code DatabaseGroupDefinition} per {@code docs/CONNECTION_MODEL.md}'s
	 * "Database Group" terminology (Phase 1 of that rework, following on from Phase 0's
	 * docs/TECHNICAL_CHANGE.md, 2026-09-05 retargeting of this CRUD lifecycle onto {@code INSTANCE});
	 * the physical table stays {@code INSTANCE}.
	 */
	public List<DatabaseGroupDefinition> getGroupDetails() throws BroadSQLException {
		List<DatabaseGroupDefinition> result = new ArrayList<>();
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					Statement stat = conn.createStatement();
					ResultSet results = stat.executeQuery("SELECT ID, DESCR, COMMENT, STATUS_ID FROM INSTANCE ORDER BY ID")) {
				while (results.next()) {
					result.add(new DatabaseGroupDefinition(results.getString("ID"), results.getString("DESCR"), results.getString("COMMENT"), results.getString("STATUS_ID")));
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		return result;
	}// getGroupDetails

	/**
	 * Inserts or updates an {@code INSTANCE} row, keyed by {@code group.getId()} - equivalent to
	 * {@link #saveGroup(DatabaseGroupDefinition, String)} with a {@code null} {@code previousId}, i.e.
	 * no rename. Used whenever the ID itself is not being changed.
	 */
	public void saveGroup(DatabaseGroupDefinition group) throws BroadSQLException {
		saveGroup(group, null);
	}// saveGroup

	/**
	 * Inserts or updates an {@code INSTANCE} row (update if a row with that ID already exists, insert
	 * otherwise), then reloads the vault so {@link #getGroups()} reflects the change immediately - same
	 * pattern as {@link #saveDatabaseDefinition}. Used by the "Database Groups" tab.
	 *
	 * <p>When {@code previousId} is given and differs (case-sensitively, matching {@code INSTANCE.ID}
	 * and {@code CONNECTIONS.INSTANCE_ID}, both plain {@code VARCHAR}) from {@code group.getId()}, this
	 * is a rename: the {@code INSTANCE} row's ID itself changes, and every {@code CONNECTIONS} row
	 * referencing {@code previousId} - active or inactive alike, not just active ones - is repointed at
	 * the new ID, in the same transaction. Refuses (throws, without changing anything) if an
	 * {@code INSTANCE} row already exists under the target ID. Per {@code docs/CONNECTION_MODEL.md}
	 * §4, a Database Group's ID is not meant to be renamed at all once created - this rename support
	 * predates that document and is retained for the same reason the "Database Groups" tab's ID field
	 * stays editable: reassigning every referencing Connection by hand would otherwise be the only way
	 * to correct a typo'd ID.
	 *
	 * <p><b>Rename ordering, and why it isn't a plain {@code UPDATE ... SET ID=}</b> (fixed 03/09/2026,
	 * a real rename against a real CDF threw {@code JdbcSQLIntegrityConstraintViolationException} on
	 * {@code CONSTRAINT_E71E}): the CDF's FK constraints, all declared {@code NOCHECK}, do not mean
	 * "not enforced." In H2, {@code NOCHECK} on {@code ALTER TABLE ADD CONSTRAINT} only skips
	 * validating data that already existed when the constraint was added; it does not disable enforcement
	 * of the constraint on later statements. {@code CONNECTIONS.INSTANCE_ID -> INSTANCE.ID} is enforced,
	 * and the constraint has no {@code ON UPDATE CASCADE}, so directly changing a still-referenced
	 * {@code INSTANCE.ID} is rejected no matter which order a plain rename-then-repoint (or
	 * repoint-then-rename) runs in - one of the two steps always leaves some {@code CONNECTIONS} row
	 * pointing at an ID that does not exist in {@code INSTANCE} at that instant. Renaming under an
	 * actively-enforced FK with no cascade instead needs three steps, each always leaving every
	 * {@code CONNECTIONS} row pointing at an existing {@code INSTANCE} row: insert the new row first (old
	 * and new rows briefly coexist, no conflict), repoint every {@code CONNECTIONS} row from the old ID
	 * to the new one (legal - the new row already exists), then delete the old row (legal - nothing
	 * references it anymore).
	 *
	 * <p><b>Business rule (added 03/09/2026, at the user's request after testing): a connection must
	 * stay attached to an active Database Group</b> - saving {@code group} as inactive (the "Active"
	 * checkbox unchecked, then Save) is refused exactly like {@link #softDeleteGroup} refuses, with the
	 * same check ({@link #getActiveConnectionIdsForGroup}), if any active connection still references
	 * it. Before this fix, only the "Delete" button (which calls {@link #softDeleteGroup}) enforced
	 * this - saving the same deactivation through the "Active" checkbox instead silently bypassed the
	 * guard entirely, since this method's non-rename branch only ever did a plain
	 * {@code UPDATE ... SET STATUS_ID=?} with no check at all.
	 *
	 * @param previousId the group's ID before this save, or {@code null}/blank for a brand-new group
	 *                    or a save that does not change the ID
	 */
	public void saveGroup(DatabaseGroupDefinition group, String previousId) throws BroadSQLException {
		if (group == null || !group.isNotNull()) {
			return;
		}
		boolean rename = StringUtils.isNotBlank(previousId) && !previousId.equals(group.getId());
		if (rename && groupExists(group.getId())) {
			throw new BroadSQLException("Database Group '" + group.getId() + "' already exists.");
		}
		if (!group.isActive()) {
			// Whichever ID currently carries the connections about to end up on an inactive
			// group: the old ID being renamed away from, or the (unchanged) ID itself otherwise.
			assertNoConnectionsReferenceGroup(rename ? previousId : group.getId());
		}
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword)) {
				conn.setAutoCommit(false);
				try {
					if (rename) {
						try (PreparedStatement insert = conn.prepareStatement("INSERT INTO INSTANCE (ID, DESCR, COMMENT, STATUS_ID) VALUES (?, ?, ?, ?)")) {
							insert.setString(1, group.getId());
							insert.setString(2, group.getDescr());
							insert.setString(3, group.getComment());
							insert.setString(4, group.getStatusId());
							insert.executeUpdate();
						}
						try (PreparedStatement propagate = conn.prepareStatement("UPDATE CONNECTIONS SET INSTANCE_ID=? WHERE INSTANCE_ID=?")) {
							propagate.setString(1, group.getId());
							propagate.setString(2, previousId);
							propagate.executeUpdate();
						}
						try (PreparedStatement delete = conn.prepareStatement("DELETE FROM INSTANCE WHERE ID=?")) {
							delete.setString(1, previousId);
							delete.executeUpdate();
						}
					} else {
						int updated;
						try (PreparedStatement update = conn.prepareStatement("UPDATE INSTANCE SET DESCR=?, COMMENT=?, STATUS_ID=? WHERE ID=?")) {
							update.setString(1, group.getDescr());
							update.setString(2, group.getComment());
							update.setString(3, group.getStatusId());
							update.setString(4, group.getId());
							updated = update.executeUpdate();
						}
						if (updated == 0) {
							try (PreparedStatement insert = conn.prepareStatement("INSERT INTO INSTANCE (ID, DESCR, COMMENT, STATUS_ID) VALUES (?, ?, ?, ?)")) {
								insert.setString(1, group.getId());
								insert.setString(2, group.getDescr());
								insert.setString(3, group.getComment());
								insert.setString(4, group.getStatusId());
								insert.executeUpdate();
							}
						}
					}
					conn.commit();
				} catch (SQLException ex) {
					conn.rollback();
					throw ex;
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		load();
	}// saveGroup

	private boolean groupExists(String id) throws BroadSQLException {
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					PreparedStatement stat = conn.prepareStatement("SELECT COUNT(*) FROM INSTANCE WHERE ID=?")) {
				stat.setString(1, id);
				try (ResultSet rs = stat.executeQuery()) {
					rs.next();
					return rs.getInt(1) > 0;
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
	}// groupExists

	/**
	 * Enforces the business rule that a Database Group cannot be deactivated while any connection -
	 * active <b>or inactive</b> - still points at it (tightened 03/09/2026 at the user's explicit
	 * request: an inactive connection keeps its {@code INSTANCE_ID} and remains viewable/reactivable,
	 * so leaving it pointed at a deactivated group would silently break it the moment it's
	 * reactivated). Uses {@link #getConnectionIdsForGroup}, unfiltered by status - the FK is
	 * {@code NOCHECK} (see {@link #saveGroup(DatabaseGroupDefinition, String)}'s javadoc for what that
	 * actually means), so this is the only thing that stops a connection being left pointing at an
	 * inactive group. Shared by every path that can flip an {@code INSTANCE} row to {@code INACTIVE} -
	 * {@link #softDeleteGroup} and {@link #saveGroup(DatabaseGroupDefinition, String)}.
	 */
	private void assertNoConnectionsReferenceGroup(String groupId) throws BroadSQLException {
		List<String> referencing = getConnectionIdsForGroup(groupId);
		if (!referencing.isEmpty()) {
			throw new BroadSQLException("Cannot deactivate Database Group '" + groupId + "': still referenced by connection(s) " + String.join(", ", referencing)
					+ ". Reassign or hard-delete those connections first.");
		}
	}// assertNoConnectionsReferenceGroup

	/**
	 * The IDs of every currently-active {@code CONNECTIONS} row pointing at {@code groupId} - used
	 * only by test assertions and any caller that specifically needs the active-only view.
	 * {@link #assertNoConnectionsReferenceGroup}, the group-deactivation guard, uses the unfiltered
	 * {@link #getConnectionIdsForGroup} instead - the {@code INSTANCE_ID} foreign key is declared
	 * {@code NOCHECK} in the CDF schema, so nothing at the database level stops a connection from
	 * being left pointing at a deactivated group.
	 */
	public List<String> getActiveConnectionIdsForGroup(String groupId) throws BroadSQLException {
		return getConnectionIdsForGroup(groupId, DatabaseDefinition.STATUS_ACTIVE);
	}// getActiveConnectionIdsForGroup

	/**
	 * The IDs of every {@code CONNECTIONS} row pointing at {@code groupId}, active or inactive alike -
	 * see {@link #assertNoConnectionsReferenceGroup}.
	 */
	public List<String> getConnectionIdsForGroup(String groupId) throws BroadSQLException {
		return getConnectionIdsForGroup(groupId, null);
	}// getConnectionIdsForGroup

	private List<String> getConnectionIdsForGroup(String groupId, String statusFilter) throws BroadSQLException {
		List<String> ids = new ArrayList<>();
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			String query = "SELECT ID FROM CONNECTIONS WHERE INSTANCE_ID=?" + (statusFilter != null ? " AND STATUS_ID=?" : "");
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					PreparedStatement stat = conn.prepareStatement(query)) {
				stat.setString(1, groupId);
				if (statusFilter != null) {
					stat.setString(2, statusFilter);
				}
				try (ResultSet results = stat.executeQuery()) {
					while (results.next()) {
						ids.add(results.getString(1));
					}
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		return ids;
	}// getConnectionIdsForGroup

	/**
	 * Soft-deletes (status to {@code INACTIVE}) an {@code INSTANCE} row, refusing if any connection -
	 * active or inactive - still references it (see {@link #getConnectionIdsForGroup}) - the FK is
	 * {@code NOCHECK}, so this is the only thing that stops a connection being silently orphaned.
	 * Reloads the vault afterward. Used by the "Database Groups" tab.
	 */
	public void softDeleteGroup(String id) throws BroadSQLException {
		assertNoConnectionsReferenceGroup(id);
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					PreparedStatement stat = conn.prepareStatement("UPDATE INSTANCE SET STATUS_ID=? WHERE ID=?")) {
				stat.setString(1, DatabaseDefinition.STATUS_INACTIVE);
				stat.setString(2, id);
				stat.executeUpdate();
				conn.commit();
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		load();
	}// softDeleteGroup

	/**
	 * Every {@code CONNECTIONS} row (ID + Environment) belonging to Database Group {@code groupId},
	 * active only, ordered by Environment - backs the "Database Groups" tab's group-detail view
	 * (docs/CONNECTION_MODEL.md §4.2).
	 */
	public List<DatabaseDefinition> getConnectionsForGroup(String groupId) throws BroadSQLException {
		List<DatabaseDefinition> result = new ArrayList<>();
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					PreparedStatement stat = conn.prepareStatement(
							"SELECT ID, NAME, ENVIRONMENT_ID FROM CONNECTIONS WHERE INSTANCE_ID=? AND STATUS_ID=? ORDER BY ENVIRONMENT_ID")) {
				stat.setString(1, groupId);
				stat.setString(2, DatabaseDefinition.STATUS_ACTIVE);
				try (ResultSet results = stat.executeQuery()) {
					while (results.next()) {
						DatabaseDefinition connection = new DatabaseDefinition();
						connection.setId(results.getString("ID"));
						connection.setDbName(results.getString("NAME"));
						connection.setEnvironment(results.getString("ENVIRONMENT_ID"));
						connection.setDatabaseGroup(groupId);
						result.add(connection);
					}
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		return result;
	}// getConnectionsForGroup

	/**
	 * Every row of the CDF's {@code ENVIRONMENT} table (active and inactive), ordered by ID - backs the
	 * "Environments" tab (docs/CONNECTION_MODEL.md §5). {@link #getEnvironments()} remains the narrower,
	 * active-only, ID-only view used to populate the connection form's Environment combo box.
	 *
	 * <p>Phase 1 of the {@code docs/CONNECTION_MODEL.md} rework: {@code ENVIRONMENT} gets a full CRUD
	 * lifecycle for the first time, mirroring {@link #getGroupDetails()}/{@link #saveGroup(DatabaseGroupDefinition, String)}/
	 * {@link #softDeleteGroup} - but targeting the table it always correctly belonged to, so (unlike
	 * that Instance/Group history) there is no "wrong table" story here.
	 */
	public List<EnvironmentDefinition> getEnvironmentDetails() throws BroadSQLException {
		List<EnvironmentDefinition> result = new ArrayList<>();
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					Statement stat = conn.createStatement();
					ResultSet results = stat.executeQuery("SELECT ID, DESCR, PRODUCTION, COMMENT, STATUS_ID FROM ENVIRONMENT ORDER BY ID")) {
				while (results.next()) {
					result.add(new EnvironmentDefinition(results.getString("ID"), results.getString("DESCR"), results.getBoolean("PRODUCTION"), results.getString("COMMENT"),
							results.getString("STATUS_ID")));
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		return result;
	}// getEnvironmentDetails

	/**
	 * Inserts or updates an {@code ENVIRONMENT} row, keyed by {@code environment.getId()}, then reloads
	 * the vault so {@link #getEnvironments()} reflects the change immediately - same pattern as
	 * {@link #saveGroup(DatabaseGroupDefinition)}. Used by the "Environments" tab.
	 *
	 * <p>Unlike {@link #saveGroup(DatabaseGroupDefinition, String)}, there is no rename support: per
	 * {@code docs/CONNECTION_MODEL.md} §5.1, an Environment's ID is immutable after creation - to
	 * replace one, the user creates a new Environment, reassigns the relevant Connections, then deletes
	 * the old one. ID uniqueness is case-insensitive (§5.1: {@code PR} and {@code pr} must not
	 * coexist) - enforced by {@link #environmentExists}, checked only when this is genuinely a new row
	 * (no existing row under the exact ID), so an in-place edit of an existing row is never blocked by
	 * its own ID.
	 *
	 * <p>Deactivating an Environment (the "Active" checkbox unchecked, then Save) is refused exactly
	 * like {@link #softDeleteEnvironment} refuses if any connection - active or inactive - still
	 * references it, and also refused if it is the last remaining active Environment (§5.5: "the list
	 * must contain at least ONE environment") - see {@link #assertAtLeastOneEnvironmentRemains}.
	 */
	public void saveEnvironment(EnvironmentDefinition environment) throws BroadSQLException {
		if (environment == null || !environment.isNotNull()) {
			return;
		}
		boolean isNewRow = !exactEnvironmentIdExists(environment.getId());
		if (isNewRow && environmentExists(environment.getId())) {
			throw new BroadSQLException("Environment '" + environment.getId() + "' already exists (environment IDs are case-insensitive).");
		}
		if (!environment.isActive()) {
			assertNoConnectionsReferenceEnvironment(environment.getId());
			assertAtLeastOneEnvironmentRemains(environment.getId());
		}
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword)) {
				int updated;
				try (PreparedStatement update = conn.prepareStatement("UPDATE ENVIRONMENT SET DESCR=?, PRODUCTION=?, COMMENT=?, STATUS_ID=? WHERE ID=?")) {
					update.setString(1, environment.getDescr());
					update.setBoolean(2, environment.isProduction());
					update.setString(3, environment.getComment());
					update.setString(4, environment.getStatusId());
					update.setString(5, environment.getId());
					updated = update.executeUpdate();
				}
				if (updated == 0) {
					try (PreparedStatement insert = conn.prepareStatement("INSERT INTO ENVIRONMENT (ID, DESCR, PRODUCTION, COMMENT, STATUS_ID) VALUES (?, ?, ?, ?, ?)")) {
						insert.setString(1, environment.getId());
						insert.setString(2, environment.getDescr());
						insert.setBoolean(3, environment.isProduction());
						insert.setString(4, environment.getComment());
						insert.setString(5, environment.getStatusId());
						insert.executeUpdate();
					}
				}
				conn.commit();
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		load();
	}// saveEnvironment

	private boolean exactEnvironmentIdExists(String id) throws BroadSQLException {
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					PreparedStatement stat = conn.prepareStatement("SELECT COUNT(*) FROM ENVIRONMENT WHERE ID=?")) {
				stat.setString(1, id);
				try (ResultSet rs = stat.executeQuery()) {
					rs.next();
					return rs.getInt(1) > 0;
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
	}// exactEnvironmentIdExists

	/**
	 * Resolves the exact, persisted casing of the built-in {@link #LOCAL_ENVIRONMENT_ID} Environment -
	 * seeded (or preserved, if a pre-existing case variant was found) by {@link #seedLocalEnvironmentIfNeeded}
	 * on every {@link #load()}. Callers that need to reference "the LOCAL environment" by ID (currently
	 * only {@code CommandPull}) must go through this method rather than hardcoding the literal
	 * {@code "LOCAL"} string: connection-save validation ({@code assertEnvironmentIsValid}) checks
	 * exact-match existence via {@link #exactEnvironmentIdExists}, so a connection must be given whatever
	 * casing is actually stored (e.g. {@code Local}, if that's what a pre-existing row used) or that
	 * validation would incorrectly reject it.
	 *
	 * <p>Falls back to the literal {@link #LOCAL_ENVIRONMENT_ID} if, unexpectedly, no case-insensitive
	 * match is found at all - defensive only, since {@link #load()} always seeds one before this can be
	 * called in practice.
	 */
	public String resolveLocalEnvironmentId() throws BroadSQLException {
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					PreparedStatement stat = conn.prepareStatement("SELECT ID FROM ENVIRONMENT WHERE UPPER(ID)=UPPER(?)")) {
				stat.setString(1, LOCAL_ENVIRONMENT_ID);
				try (ResultSet rs = stat.executeQuery()) {
					if (rs.next()) {
						return rs.getString(1);
					}
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		return LOCAL_ENVIRONMENT_ID;
	}// resolveLocalEnvironmentId

	/**
	 * Case-insensitive existence check per {@code docs/CONNECTION_MODEL.md} §5.1 ({@code PR} and
	 * {@code pr} must not coexist) - unlike {@link #exactEnvironmentIdExists}, used only to decide
	 * whether a brand-new ID collides with an existing one under different casing.
	 */
	private boolean environmentExists(String id) throws BroadSQLException {
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					PreparedStatement stat = conn.prepareStatement("SELECT COUNT(*) FROM ENVIRONMENT WHERE UPPER(ID)=UPPER(?)")) {
				stat.setString(1, id);
				try (ResultSet rs = stat.executeQuery()) {
					rs.next();
					return rs.getInt(1) > 0;
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
	}// environmentExists

	/**
	 * Enforces the business rule that an Environment cannot be deactivated while any connection -
	 * active <b>or inactive</b> - still points at it, mirroring {@link #assertNoConnectionsReferenceGroup}.
	 * Uses {@link #getConnectionIdsForEnvironment}, unfiltered by status - the {@code ENVIRONMENT_ID}
	 * FK is {@code NOCHECK}, so this is the only thing that stops a connection being left pointing at
	 * an inactive Environment.
	 */
	private void assertNoConnectionsReferenceEnvironment(String environmentId) throws BroadSQLException {
		List<String> referencing = getConnectionIdsForEnvironment(environmentId);
		if (!referencing.isEmpty()) {
			throw new BroadSQLException("Cannot deactivate environment '" + environmentId + "': still referenced by connection(s) " + String.join(", ", referencing)
					+ ". Reassign or hard-delete those connections first.");
		}
	}// assertNoConnectionsReferenceEnvironment

	/**
	 * Enforces {@code docs/CONNECTION_MODEL.md} §5.5: "the list must contain at least ONE environment -
	 * the last environment cannot be deleted." Counts every currently-active {@code ENVIRONMENT} row
	 * other than {@code excludingId} (the one about to be deactivated); refuses if none would remain.
	 */
	private void assertAtLeastOneEnvironmentRemains(String excludingId) throws BroadSQLException {
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					PreparedStatement stat = conn.prepareStatement("SELECT COUNT(*) FROM ENVIRONMENT WHERE STATUS_ID=? AND ID<>?")) {
				stat.setString(1, DatabaseDefinition.STATUS_ACTIVE);
				stat.setString(2, StringUtils.defaultString(excludingId));
				try (ResultSet rs = stat.executeQuery()) {
					rs.next();
					if (rs.getInt(1) == 0) {
						throw new BroadSQLException("Cannot deactivate environment '" + excludingId + "': at least one active environment must remain.");
					}
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
	}// assertAtLeastOneEnvironmentRemains

	/**
	 * The IDs of every currently-active {@code CONNECTIONS} row pointing at {@code environmentId} -
	 * mirrors {@link #getActiveConnectionIdsForGroup}.
	 */
	public List<String> getActiveConnectionIdsForEnvironment(String environmentId) throws BroadSQLException {
		return getConnectionIdsForEnvironment(environmentId, DatabaseDefinition.STATUS_ACTIVE);
	}// getActiveConnectionIdsForEnvironment

	/**
	 * The IDs of every {@code CONNECTIONS} row pointing at {@code environmentId}, active or inactive
	 * alike - see {@link #assertNoConnectionsReferenceEnvironment}.
	 */
	public List<String> getConnectionIdsForEnvironment(String environmentId) throws BroadSQLException {
		return getConnectionIdsForEnvironment(environmentId, null);
	}// getConnectionIdsForEnvironment

	private List<String> getConnectionIdsForEnvironment(String environmentId, String statusFilter) throws BroadSQLException {
		List<String> ids = new ArrayList<>();
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			String query = "SELECT ID FROM CONNECTIONS WHERE ENVIRONMENT_ID=?" + (statusFilter != null ? " AND STATUS_ID=?" : "");
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					PreparedStatement stat = conn.prepareStatement(query)) {
				stat.setString(1, environmentId);
				if (statusFilter != null) {
					stat.setString(2, statusFilter);
				}
				try (ResultSet results = stat.executeQuery()) {
					while (results.next()) {
						ids.add(results.getString(1));
					}
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		return ids;
	}// getConnectionIdsForEnvironment

	/**
	 * Soft-deletes (status to {@code INACTIVE}) an {@code ENVIRONMENT} row, refusing if any connection
	 * still references it or if it is the last active Environment - see
	 * {@link #assertNoConnectionsReferenceEnvironment} and {@link #assertAtLeastOneEnvironmentRemains}.
	 * Reloads the vault afterward. Used by the "Environments" tab.
	 */
	public void softDeleteEnvironment(String id) throws BroadSQLException {
		assertNoConnectionsReferenceEnvironment(id);
		assertAtLeastOneEnvironmentRemains(id);
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					PreparedStatement stat = conn.prepareStatement("UPDATE ENVIRONMENT SET STATUS_ID=? WHERE ID=?")) {
				stat.setString(1, DatabaseDefinition.STATUS_INACTIVE);
				stat.setString(2, id);
				stat.executeUpdate();
				conn.commit();
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		load();
	}// softDeleteEnvironment

	/**
	 * For the password of the connection, not the password of the SDF (Servers
	 * Definition File)
	 */
	public void updateDatabaseDefinitionPassword(String databaseConnectionFileName, String id, String newPassword) throws BroadSQLException {
		try {
			String connectionStr = "jdbc:h2:" + databaseConnectionFileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
			// Statement stat = conn.createStatement();
			// String query = "UPDATE CONNECTIONS SET USER_PASSWORD='" + newPassword + "'
			// where upper(USER_NAME)='" + userName.toUpperCase() + "' and ID='" +
			// databaseConnection + "' and USER_PASSWORD='" + oldPassword + "';";
			/*String query = "UPDATE CONNECTIONS SET USER_PASSWORD=? where upper(USER_NAME)=? and ID=? and USER_PASSWORD=?";
			 PreparedStatement stat = conn.prepareStatement(query);
			 stat.setString(1, newPassword);
			 stat.setString(2, userName.toUpperCase());
			 stat.setString(3, id);
			 stat.setString(4, oldPassword);*/
			String query = "UPDATE CONNECTIONS SET USER_PASSWORD=? WHERE ID=?";
			PreparedStatement stat = conn.prepareStatement(query);
			stat.setString(1, newPassword);
			stat.setString(2, id);
			stat.executeUpdate();
			stat.close();
			conn.commit();
			conn.close();
		} catch (SQLException | ClassNotFoundException ex) {
			// ex.printStackTrace();
			throw new BroadSQLException(ex);
		}
		load();
	}

	/*
	 * Soft deletion: turn status to INACTIVE
	 */
	public void softDeleteDatabaseDefinition(String id) throws BroadSQLException {
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
			String query = "UPDATE CONNECTIONS SET STATUS_ID=? WHERE ID=?";
			PreparedStatement stat = conn.prepareStatement(query);
			stat.setString(1, DatabaseDefinition.STATUS_INACTIVE);
			stat.setString(2, id);
			stat.executeUpdate();
			stat.close();
			conn.commit();
			conn.close();
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		load();
	}

	/**
	 * Hard deletion: remove the record from the CDF outright, not a status flip. Only ever legal on an
	 * already-inactive connection - refuses an active one ("deactivate it first") and an unknown ID
	 * alike, so this is the single enforcement point regardless of caller ({@code HARDDEL} or the
	 * config screen's "Inactive connections" view Delete button).
	 */
	public void deleteDatabaseDefinition(String id) throws BroadSQLException {
		if (contains(id)) {
			throw new BroadSQLException("Cannot hard delete an active connection '" + id + "'. Deactivate it first.");
		}
		if (!isInactiveConnection(id)) {
			throw new BroadSQLException("Connection '" + id + "' does not exist");
		}
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
			String query = "DELETE FROM CONNECTIONS WHERE ID=?";
			PreparedStatement stat = conn.prepareStatement(query);
			stat.setString(1, id);
			stat.executeUpdate();
			stat.close();
			conn.commit();
			conn.close();
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		load();
	}

	/**
	 * The active, ordered SQL text of a connection's login script - what actually runs on connect.
	 * Delegates to {@link #getUserScriptLines(String, String, String)} and keeps only the enabled
	 * lines, in {@code sql_order}.
	 *
	 * <p>Scoped per-connection ({@code databaseConnection}/{@code server_id}), not per-user: the CDF
	 * schema's {@code USERS_ID} column and this method's former {@code userID} parameter suggested
	 * per-user scoping, but the query never actually filtered on it (see docs/TODO.md item 13,
	 * confirmed 03/09/2026) - the {@code userID} parameter has been dropped rather than wired up, per
	 * that item's resolution.
	 *
	 * <p><b>Fixed 03/09/2026</b>: previously collected into a {@code TreeSet<String>}, which
	 * re-sorted the lines alphabetically by SQL text (discarding the {@code sql_order} the query
	 * itself asked for) and silently dropped any two textually-identical lines. Now returns a
	 * {@code List}, preserving both the requested order and duplicates.
	 */
	public List<String> getUserLoginScript(String databaseConnectionFileName, String password, String databaseConnection) throws BroadSQLException {
		List<String> scripts = new ArrayList<>();
		for (UserScriptLine line : getUserScriptLines(databaseConnectionFileName, password, databaseConnection)) {
			if (line.isActive() && line.isNotBlank()) {
				scripts.add(line.getSqlCommand().trim());
			}
		}
		return scripts;
	}// getUserLoginScript

	/**
	 * Every login-script line registered for {@code databaseConnection} (active and inactive), in
	 * {@code sql_order} - the read side of the CONFIG screen's "Login Scripts" tab (docs/TODO.md item
	 * 13). {@link #getUserLoginScript} is the narrower, active-only view used at connect time.
	 */
	public List<UserScriptLine> getUserScriptLines(String databaseConnectionFileName, String password, String databaseConnection) throws BroadSQLException {
		List<UserScriptLine> lines = new ArrayList<>();
		try {
			String connectionStr = "jdbc:h2:" + databaseConnectionFileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
					PreparedStatement stat = conn.prepareStatement(
							"SELECT SERVER_ID, SQL_COMMAND, SQL_ORDER, STATUS_ID, SQL_COMMENT FROM USERS_SCRIPT WHERE SERVER_ID=? ORDER BY SQL_ORDER ASC")) {
				stat.setString(1, databaseConnection);
				try (ResultSet results = stat.executeQuery()) {
					while (results.next()) {
						lines.add(new UserScriptLine(results.getString("SERVER_ID"), results.getString("SQL_COMMAND"), results.getInt("SQL_ORDER"), results.getString("STATUS_ID"),
								results.getString("SQL_COMMENT")));
					}
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		return lines;
	}// getUserScriptLines

	/**
	 * Replaces the entire login script of {@code databaseConnection} with {@code lines}, in one
	 * transaction (delete all existing rows for that connection, then reinsert {@code lines} in
	 * order with freshly assigned, gap-free {@code sql_order} values). The write side of the CONFIG
	 * screen's "Login Scripts" tab (docs/TODO.md item 13) - simpler and safer than per-row CRUD given
	 * how few lines a login script typically has, and avoids ever leaving a partially-reordered list
	 * behind.
	 */
	public void saveUserScriptLines(String databaseConnection, List<UserScriptLine> lines) throws BroadSQLException {
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword)) {
				try (PreparedStatement delete = conn.prepareStatement("DELETE FROM USERS_SCRIPT WHERE SERVER_ID=?")) {
					delete.setString(1, databaseConnection);
					delete.executeUpdate();
				}
				try (PreparedStatement insert = conn.prepareStatement(
						"INSERT INTO USERS_SCRIPT (SERVER_ID, STATUS_ID, SQL_COMMAND, SQL_ORDER, SQL_COMMENT) VALUES (?, ?, ?, ?, ?)")) {
					int order = 1;
					for (UserScriptLine line : lines) {
						insert.setString(1, databaseConnection);
						insert.setString(2, line.getStatusId());
						insert.setString(3, line.getSqlCommand());
						insert.setInt(4, order++);
						insert.setString(5, line.getSqlComment());
						insert.executeUpdate();
					}
				}
				conn.commit();
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
	}// saveUserScriptLines

	private boolean isValidInput(String input) {
		boolean result;
		result = !(input == null || input.trim().equals(""));
		return (result);
	}// isValidInput

	/**
	 * Connects to a database. If the database does not exist, create it and
	 * encrypt with AES algo
	 *
	 * @TODO 14.01.2015 review completely this method
	 * @param pfName(String) the file name of the db. Can be relative or
	 * absolute.
	 * @param password (String) both password of ADMIN user and aes encrypted
	 * file
	 */
	public boolean createDatabase(String pfName, String password) throws BroadSQLException {
		boolean result = false;
		if (adminName == null || adminName.trim().equals("")) {
			adminName = H2_ADMIN;
		}
		if (isValidInput(pfName) && isValidInput(password)) {
			try {

				String connectionStr = "jdbc:h2:" + pfName + ";CIPHER=AES";
				Class.forName("org.h2.Driver");
				/* for AES encryption, 2 passwords must be sent, separated by a space: 
				 * String pwds = "filepwd userpwd";
				 * 1st password: is the db password, the 2nd password is the ADMIN password
				 * In this implementation, the choice is to use same file pwd as ADMIN pwd
				 */
				String aesPassword = password + " " + password;
				Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword);
				Statement stat = conn.createStatement();
				// Creating tables
				ArrayList<String> qs1 = new ArrayList<>();
				ArrayList<String> qs2 = new ArrayList<>();
				qs1.add("CREATE TABLE IF NOT EXISTS SERVERS (ID varchar(15), LOCAL BOOLEAN, ENCRYPTED BOOLEAN, FILE_NAME varchar(255), HOST_NAME VARCHAR(80), PORT INT, NAME VARCHAR(45), TYPE_ID VARCHAR(20), STATUS_ID VARCHAR(2))");
				qs1.add("create table if not exists TYPE (ID VARCHAR(20), DEFAULT_DRIVER VARCHAR(80), STATUS_ID VARCHAR(2))");
				qs2.add("insert into TYPE values ('Oracle',null,'30')");
				qs2.add("insert into TYPE values ('Derby','org.apache.derby.jdbc.EmbeddedDriver','30')");
				qs2.add("insert into TYPE values ('H2','org.h2.Driver','30')");
				qs2.add("insert into TYPE values ('MySQL','com.mysql.cj.jdbc.Driver','30')");
				qs2.add("create table if not exists STATUS (ID VARCHAR(2), DESCR VARCHAR(45))");
				qs2.add("insert into status values ('30','Active')");
				qs2.add("insert into status values ('90','Inactive')");
				qs1.add("create table if not exists USERS (SERVER_ID VARCHAR(15), DEFAULT BOOLEAN, ID varchar(45), PASSWORD VARCHAR(80), STATUS_ID varchar(2))");

				// Populating tables
				Set<String> keys = this.getPlatforms().keySet();
				for (String key : keys) {
					DatabaseDefinition pl = this.getDatabaseConnection(key);
					String u = "INSERT INTO USERS (SERVER_ID, DEFAULT, ID, PASSWORD, STATUS_ID) VALUES ('" + key + "',TRUE,'" + pl.getUserName() + "','" + pl.getUserPassword() + "','30')";
					qs2.add(u);
					String q = "INSERT INTO SERVERS (ID, LOCAL, ENCRYPTED, FILE_NAME, HOST_NAME, PORT, NAME, TYPE_ID, STATUS_ID) VALUES ";
					String serverName = pl.getDbName();
					if (serverName.length() > 255) {
						serverName = key;
					}
					/*
					 if (pl.getServerPort().equalsIgnoreCase("-1")) {
					 q += "('" + key + "',TRUE," + pl.getEncrypted().toUpperCase() + ",'" + pl.getDbName() + "',null,-1,'" + serverName + "','" + pl.getDbType() + "','30')";
					 } else {
					 q += "('" + key + "',FALSE," + pl.getEncrypted().toUpperCase() + ",'" + pl.getServerName() + "','" + pl.getServerName() + "'," + pl.getServerPort() + ",'" + serverName + "','" + pl.getDbType() + "','30')";
					 }
					 * 
					 */
					qs2.add(q);

				}
				// Adding self
				// Executing qs1
				try {
					FileWriter fw = new FileWriter("c:\\temp\\queries.txt");
					PrintWriter pw = new PrintWriter(fw);
					for (String query : qs1) {
						// log.debug(query);
						pw.println(query + ";");
						/*
						 int ok = stat.executeUpdate(query);
						 log.debug(" Result ===> " + ok);
						 log.debug("________________");
						 * 
						 */
					} // for
						// Executing qs2
					for (String query : qs2) {
						// log.debug(query);
						pw.println(query + ";");
						/*
						 int ok = stat.executeUpdate(query);
						 log.debug(" Result ===> " + ok);
						 log.debug("________________");
						 * 
						 */
					} // for
					fw.close();
				} catch (IOException ie) {
					ie.printStackTrace();
				}
				if (stat != null) {
					stat.close();
				}
				// Returning result
				result = true;
				if (conn != null) {
					conn.close();
				}
			} catch (SQLException ex) {
				throw new BroadSQLException(ex);
			} catch (ClassNotFoundException ex) {
				throw new BroadSQLException(ex);
			}
		} else {
		}
		return (result);
	}// createDatabase

	public boolean isValidPassword() throws BroadSQLException {
		boolean result = false;
		Connection conn = null;
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";IFEXISTS=TRUE;CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			// log.debug("Password2: "+aesPassword);
			// log.debug("ConnectionStr: {}", connectionStr);
			conn = DriverManager.getConnection(connectionStr, "ADMIN", aesPassword);
			result = true;
			if (conn != null) {
				conn.close();
			}
		} catch (SQLException ex) {
			int errorCode = ex.getErrorCode();
			if (errorCode != H2_PWD_ERROR_CODE && errorCode != H2_PWD_DB_ERROR_CODE && errorCode != H2_ERR_CODE) {
				throw new BroadSQLException(ex);
			}
		} catch (ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		} finally {
			try {
				if (conn != null) {
					conn.close();
				}
			} catch (SQLException se) {
				throw new BroadSQLException(se);
			}
		}
		return (result);
	}// isValidPassword

	public void changeMasterPassword(String databaseConnectionFileName, String oldPassword, String newPassword) throws BroadSQLException {
		boolean changeDbPwd = true;
		boolean changeEncryptPwd = true;
		try {
			// STEP1 - change the database password
			if (changeDbPwd) {
				String connectionStr = "jdbc:h2:" + databaseConnectionFileName + ";IFEXISTS=TRUE;CIPHER=AES";
				Class.forName("org.h2.Driver");
				String aesPassword = oldPassword + " " + oldPassword;
				Connection conn = DriverManager.getConnection(connectionStr, "ADMIN", aesPassword);
				if (conn != null) {
					// log.debug("Changing db password ...");
					String query = "ALTER USER ADMIN SET PASSWORD ?";
					PreparedStatement stmt = conn.prepareStatement(query);
					stmt.setString(1, newPassword);
					int r = stmt.executeUpdate();
					stmt.close();

					String query2 = "UPDATE CONNECTIONS SET USER_PASSWORD=? WHERE ID=?";
					PreparedStatement stmt2 = conn.prepareStatement(query2);
					stmt2.setString(1, newPassword + " " + newPassword);
					stmt2.setString(2, SpringPropertiesConfig.CDF_ID);
					int r2 = stmt2.executeUpdate();
					stmt2.close();

					conn.commit();
					// log.debug("Result: " + r2);
					conn.close();
				}
			}

			// STEP2 - change the AES encryption password
			if (changeEncryptPwd) {
				char[] oldPwd = oldPassword.toCharArray();
				char[] newPwd = newPassword.toCharArray();
				String dbConnName = StringUtils.replace(databaseConnectionFileName, "/", SpringPropertiesConfig.getFileSep());
				if (dbConnName.startsWith("file:")) {
					dbConnName = StringUtils.substringAfter(dbConnName, "file:");
				}
				String dbDir = StringUtils.substringBeforeLast(dbConnName, SpringPropertiesConfig.getFileSep());
				if (dbDir == null || dbDir.trim().equals("")) {
					dbDir = ".";
				}
				String dbName = StringUtils.substringAfterLast(dbConnName, SpringPropertiesConfig.getFileSep());
				if (dbName == null || dbName.trim().equals("")) {
					dbName = dbConnName;
				}
				/*
				 log.debug("Changing encryption password:");
				 log.debug("DbDir=" + dbDir);
				 log.debug("DbName=" + dbName);
				 log.debug("OldPwd=" + oldPassword);
				 log.debug("NewPwd=" + newPassword);
				 * 
				 */
				if (".".equalsIgnoreCase(dbDir)) {
					Path currentRelativePath = Paths.get("");
					dbDir = currentRelativePath.toAbsolutePath().toString();
				}
				/*
				log.debug("DbDir=" + dbDir);
				log.debug("dbName=" + dbName);
				*/
				ChangeFileEncryption.execute(dbDir, dbName, "AES", oldPwd, newPwd, false);
			}

		} catch (ClassNotFoundException ex) {
			ex.printStackTrace();
			throw new BroadSQLException(ex);
		} catch (SQLException ex) {
			ex.printStackTrace();
			int errorCode = ex.getErrorCode();
			if (errorCode != H2_PWD_ERROR_CODE && errorCode != H2_PWD_DB_ERROR_CODE) {
				throw new BroadSQLException(ex);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
			throw new BroadSQLException(ex);
		}
	}// changePassword

	/*
	public void save(DatabaseDefinition connection) throws BroadSQLException {
		throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
	}
	*/

	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	public TreeSet<String> getIds() {
		return ids;
	}

	public void setIds(TreeSet<String> ids) {
		this.ids = ids;
	}

	public TreeSet<String> getGroups() {
		return groups;
	}

	public void setGroups(TreeSet<String> groups) {
		this.groups = groups;
	}

	public TreeSet<String> getEnvironments() {
		return environments;
	}

	public void setEnvironments(TreeSet<String> environments) {
		this.environments = environments;
	}

	public TreeSet<String> getDbTypes() {
		return dbTypes;
	}

	public void setDbTypes(TreeSet<String> dbTypes) {
		this.dbTypes = dbTypes;
	}

	public HashMap<String, String> getDbDrivers() {
		return dbDrivers;
	}

	/**
	 * Per-driver-class-name cache of {@link #probeDriverClassAvailable}, so two {@code TYPE} rows
	 * sharing a driver (e.g. {@code Informix}/{@code IDS Server}) only pay the {@code Class.forName}
	 * probe once. Never needs invalidating: {@code drivers/}/{@code lib/} are already on the JVM's
	 * classpath at launch (see {@code deploy/BroadSQL.bat}'s {@code -cp lib/*;drivers/*;extensions/*}
	 * and {@code docs/TODO.md} §3.1), so a driver's loadability cannot change for the life of this
	 * process - a jar dropped in afterward only takes effect on the next restart, which creates a new
	 * vault (and therefore a new, empty cache) anyway.
	 */
	private final HashMap<String, Boolean> driverAvailability = new HashMap<>();

	/**
	 * Whether the JDBC driver class registered for CDF {@code TYPE} row {@code typeId} is actually
	 * loadable on this JVM's classpath - i.e. whether a Connection using this type stands any chance of
	 * connecting. {@code false} for an unknown type ID, a type with a blank {@code DRIVER} (the
	 * "no driver, no row" types {@code CommandSyncTypeCatalog} always skips), or a driver class
	 * {@code Class.forName} cannot find - the last case being the actual point of this method: a driver
	 * jar not yet copied into {@code drivers/}/{@code lib/} is detected exactly the way it would fail
	 * later at {@code CONNECT} time, without opening a single JAR file (unlike
	 * {@code DatabaseDriversExplorer}, which reopens and scans every class of every JAR under those
	 * folders and is deliberately not reused here - see its own "long task" warning surfaced by
	 * {@code CommandShowDrivers}).
	 *
	 * <p>Used to mark (never to remove) a type in the {@code CONFIG} type dropdown and the CLI
	 * connection wizard - see {@code docs/TECHNICAL_CHANGE.md}, 10/09/2026.
	 */
	public boolean isDriverAvailable(String typeId) {
		String driverClassName = dbDrivers.get(typeId);
		if (StringUtils.isBlank(driverClassName)) {
			return false;
		}
		return driverAvailability.computeIfAbsent(driverClassName, this::probeDriverClassAvailable);
	}

	/**
	 * Every active {@code TYPE} ID whose driver is not currently available - see
	 * {@link #isDriverAvailable(String)}.
	 */
	public Set<String> getUnavailableTypes() {
		Set<String> result = new TreeSet<>();
		for (String typeId : dbTypes) {
			if (!isDriverAvailable(typeId)) {
				result.add(typeId);
			}
		}
		return result;
	}

	private boolean probeDriverClassAvailable(String driverClassName) {
		try {
			Class.forName(driverClassName, false, this.getClass().getClassLoader());
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	public HashMap<String, DatabaseDefinition> getDatabaseConnections() {
		return databaseConnections;
	}

	public void setDatabaseConnections(HashMap<String, DatabaseDefinition> databaseConnections) {
		this.databaseConnections = databaseConnections;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public void setDbDrivers(HashMap<String, String> dbDrivers) {
		this.dbDrivers = dbDrivers;
	}

	/**
	 * Every row of the CDF's {@code TYPE} table (active and inactive), ordered by ID - backs
	 * {@code SYNC TYPE CATALOG}'s "list what exists" step. Unlike {@link #getDbTypes()}/
	 * {@link #getDbDrivers()} (active-only, ID/driver pairs, populated by {@link #load()}), this reads
	 * {@code MODE}/{@code DESCR}/{@code STATUS_ID} too, directly from the CDF, so it reflects the table
	 * even before the in-memory cache has been reloaded.
	 */
	public List<TypeDefinition> getTypeDetails() throws BroadSQLException {
		List<TypeDefinition> result = new ArrayList<>();
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword)) {
				migrateTypeSchemaIfNeeded(conn);
				try (Statement stat = conn.createStatement();
						ResultSet results = stat.executeQuery("SELECT ID, DESCR, MODE, DRIVER, STATUS_ID FROM TYPE ORDER BY ID")) {
					while (results.next()) {
						result.add(new TypeDefinition(results.getString("ID"), results.getString("DESCR"), results.getString("MODE"),
								results.getString("DRIVER"), results.getString("STATUS_ID")));
					}
				}
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		return result;
	}// getTypeDetails

	// Same width as the pre-existing INSTANCE.DESCR/ENVIRONMENT.DESCR columns (see
	// TestDatabaseConnections' CDF fixture) - syncTypeCatalog truncates to this length defensively,
	// since a real CDF's TYPE.DESCR column may already exist (created by hand, or by an older version
	// of this migration) at a width narrower than the plain, unbounded VARCHAR migrateTypeSchemaIfNeeded
	// itself adds on a brand-new column.
	private static final int TYPE_DESCR_MAX_LENGTH = 80;

	/**
	 * Reconciles the CDF's {@code TYPE} table against {@code recognizedTypes} (see
	 * {@code CommandSyncTypeCatalog}, the only caller): every entry is inserted if its {@code ID} is
	 * absent, or updated in place (overwriting {@code DESCR}/{@code MODE}/{@code DRIVER}/{@code STATUS_ID})
	 * if it already exists - same "{@code UPDATE}, then {@code INSERT} if nothing matched" pattern as
	 * {@link #saveEnvironment}, just looped over a whole list in one connection/transaction instead of
	 * one row per call, since this can touch two dozen rows at once. Never removes or renames a row -
	 * an ID not present in {@code recognizedTypes} (e.g. a user's own custom type, or the legacy {@code
	 * Derby} row predating the {@code DERBY Embedded}/{@code DERBY Client} split - see
	 * {@code docs/SUPPORTED_DATABASES.md}) is left exactly as it is.
	 *
	 * <p>An entry whose {@code getDriver()} is blank is skipped entirely - neither inserted nor updated,
	 * even if a row under that {@code ID} already exists (see {@code docs/TECHNICAL_CHANGE.md},
	 * 10/09/2026, "no driver, no row"). Several recognized type names have no confidently known driver
	 * class (a long-discontinued product, a bridge removed from the JDK, a driver generation that
	 * depends on the target version) - a blank {@code DRIVER} is exactly how {@code CommandSyncTypeCatalog}
	 * marks those, and this is the one place that fact is actually enforced, per the user's explicit
	 * rule: never create - or touch - a {@code TYPE} row for one of those.
	 */
	public TypeSyncResult syncTypeCatalog(List<TypeDefinition> recognizedTypes) throws BroadSQLException {
		TypeSyncResult result = new TypeSyncResult();
		try {
			String connectionStr = "jdbc:h2:" + fileName + ";CIPHER=AES";
			Class.forName("org.h2.Driver");
			String aesPassword = password + " " + password;
			try (Connection conn = DriverManager.getConnection(connectionStr, adminName, aesPassword)) {
				migrateTypeSchemaIfNeeded(conn);
				for (TypeDefinition type : recognizedTypes) {
					if (StringUtils.isBlank(type.getDriver())) {
						result.getSkippedNoDriver().add(type.getId());
						continue;
					}
					String descr = StringUtils.truncate(type.getDescr(), TYPE_DESCR_MAX_LENGTH);
					int updated;
					try (PreparedStatement update = conn.prepareStatement("UPDATE TYPE SET DESCR=?, MODE=?, DRIVER=?, STATUS_ID=? WHERE ID=?")) {
						update.setString(1, descr);
						update.setString(2, type.getMode());
						update.setString(3, type.getDriver());
						update.setString(4, type.getStatusId());
						update.setString(5, type.getId());
						updated = update.executeUpdate();
					}
					if (updated == 0) {
						try (PreparedStatement insert = conn.prepareStatement("INSERT INTO TYPE (ID, DESCR, MODE, DRIVER, STATUS_ID) VALUES (?, ?, ?, ?, ?)")) {
							insert.setString(1, type.getId());
							insert.setString(2, descr);
							insert.setString(3, type.getMode());
							insert.setString(4, type.getDriver());
							insert.setString(5, type.getStatusId());
							insert.executeUpdate();
						}
						result.getAdded().add(type.getId());
					} else {
						result.getUpdated().add(type.getId());
					}
				}
				conn.commit();
			}
		} catch (SQLException | ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		load();
		return result;
	}// syncTypeCatalog

}
