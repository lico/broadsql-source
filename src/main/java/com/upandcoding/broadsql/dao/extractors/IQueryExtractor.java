package com.upandcoding.broadsql.dao.extractors;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

public interface IQueryExtractor {

	/*
	 * Build a table name from a query and a result set
	 */
	public default String getTableNameFromQuery(String query) throws SQLException {
		String tableName = "DATA";
		if (query != null && !query.trim().equals("")) {
			String vQuery = query.toUpperCase().trim();
			String tmp = StringUtils.substringAfterLast(vQuery, " FROM ");
			tableName = StringUtils.substringBefore(tmp, " ");
			if (tableName.trim().equals("")) {
				tableName = tmp;
			}
			if (tableName.trim().equals("")) {
				tableName = "DATA";
			}
		}
		return (tableName);
	}// getTableNameFromQuery

	public void extract(DatabaseDefinition platform, ShellConsole cmdLineConsole, String query, ResultSet results, int maxRowsOnScreen, boolean listMode, String fileName, char screenSep, char sep, boolean isAppendToFile, Instant start)
			throws BroadSQLException, IOException, SQLException;
}
