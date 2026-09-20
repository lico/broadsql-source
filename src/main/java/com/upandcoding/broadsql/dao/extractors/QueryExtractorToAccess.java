package com.upandcoding.broadsql.dao.extractors;

import java.io.File;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import com.healthmarketscience.jackcess.Database;
import com.healthmarketscience.jackcess.DatabaseBuilder;
import com.healthmarketscience.jackcess.util.ImportUtil;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

public class QueryExtractorToAccess implements IQueryExtractor {

	/*
	 * Based on http://jackcess.sourceforge.net/
	 * 
	 */
	public void extractToMsAccess(DatabaseDefinition platform, String query, ResultSet results, String fileName) throws BroadSQLException, IOException, SQLException {

		try {
			if (results != null) {

				String msTableName = getTableNameFromQuery(query);

				File dbFile = new File(fileName);
				Database db = null;
				if (dbFile.exists()) {
					db = DatabaseBuilder.open(dbFile);
				} else {
					db = DatabaseBuilder.create(Database.FileFormat.V2000, dbFile);
				}

				new ImportUtil.Builder(db, msTableName).importResultSet(results);
				db.close();

			}

		} catch (SQLException ie) {
			throw new BroadSQLException(ie);
		} finally {
			try {
				if (results != null) {
					results.close();
				}
			} catch (SQLException ie) {
				throw new BroadSQLException(ie);
			}
		}
	}

	@Override
	public void extract(DatabaseDefinition platform, ShellConsole cmdLineConsole, String query, ResultSet results, int maxRowsOnScreen, boolean listMode, String fileName, char screenSep, char sep, boolean isAppendToFile, Instant start)
			throws BroadSQLException, IOException, SQLException {
		extractToMsAccess(platform, query, results, fileName);

	}

}
