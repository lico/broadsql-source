package com.upandcoding.broadsql.dao.export;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.pull.PullToH2Exporter;

/**
 * {@code DUMP LIB} (SPRINT 2309T, #163): holds the latest tabular result produced while a Scripts
 * Library script runs in capture mode ({@link TabularResultCapture}). Each tabular result streams, row by
 * row, into a throwaway H2 database file in the system temporary folder, replacing the previous one, so
 * when the script ends the spool holds exactly its final tabular result - complete, typed, and never held
 * in memory as a whole (a large report script stays a large-export-friendly workload).
 *
 * <p>Always {@link #close() closed} by its owner; the temporary database file is deleted then.
 */
public final class TabularResultSpool implements AutoCloseable {

	private static final String TABLE_NAME = "CAPTURED_RESULT";

	private final String basePath;
	private final Connection connection;
	private boolean hasResult;
	private int rowCount;

	private TabularResultSpool(String basePath, Connection connection) {
		this.basePath = basePath;
		this.connection = connection;
	}

	public static TabularResultSpool open() throws BroadSQLException {
		String basePath = new File(System.getProperty("java.io.tmpdir"), "broadsql_dump_" + UUID.randomUUID().toString().replace("-", ""))
				.getAbsolutePath();
		try {
			Class.forName("org.h2.Driver");
			Connection connection = DriverManager.getConnection("jdbc:h2:" + basePath);
			connection.setAutoCommit(false);
			return new TabularResultSpool(basePath, connection);
		} catch (ReflectiveOperationException | SQLException e) {
			throw new BroadSQLException("Unable to create the temporary database holding the DUMP LIB result: " + e.getLocalizedMessage(), e);
		}
	}

	/**
	 * Replaces the held result with {@code results}, consumed to the end (the caller still owns and
	 * closes it). Column types follow the {@code PULL ... AS H2} mapping, so a column type no export
	 * format supports fails here, naming the column.
	 *
	 * @return the number of rows now held
	 */
	public int replaceWith(ResultSet results) throws BroadSQLException {
		try {
			hasResult = false;
			rowCount = new PullToH2Exporter().copyInto(connection, TABLE_NAME, results);
			connection.commit();
			hasResult = true;
			return rowCount;
		} catch (SQLException e) {
			try {
				connection.rollback();
			} catch (SQLException ignored) {
				// the original failure is what matters
			}
			throw new BroadSQLException(e);
		}
	}

	/** @return whether any tabular result has been captured since this spool was opened */
	public boolean hasResult() {
		return hasResult;
	}

	/** @return the row count of the held result; meaningful only when {@link #hasResult()} */
	public int getRowCount() {
		return rowCount;
	}

	/**
	 * @return a result set over the held rows, positioned before the first row; closed with this spool
	 * @throws BroadSQLException when nothing has been captured
	 */
	public ResultSet openResult() throws BroadSQLException {
		if (!hasResult) {
			throw new BroadSQLException("No tabular result has been captured.");
		}
		try {
			Statement select = connection.createStatement();
			return select.executeQuery("SELECT * FROM \"" + TABLE_NAME + "\"");
		} catch (SQLException e) {
			throw new BroadSQLException(e);
		}
	}

	@Override
	public void close() {
		try (Statement shutdown = connection.createStatement()) {
			shutdown.execute("SHUTDOWN");
		} catch (SQLException ignored) {
			// best effort: the files are deleted below either way
		}
		try {
			connection.close();
		} catch (SQLException ignored) {
			// already shut down
		}
		new File(basePath + ".mv.db").delete();
		new File(basePath + ".trace.db").delete();
	}
}
