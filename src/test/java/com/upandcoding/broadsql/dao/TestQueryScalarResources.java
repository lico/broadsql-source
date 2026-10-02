package com.upandcoding.broadsql.dao;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.scripts.PreparedSql;

/**
 * SPRINT 0110A: {@link DatabaseConnection#queryScalar} (spec section 8.3) prepares the query, limits it to two rows,
 * and closes its result set and statement on success and on every failure; no row beyond the second is read.
 */
class TestQueryScalarResources {

	private DatabaseConnection db;
	private final List<String> events = new ArrayList<>();
	private int rowsRead;

	@BeforeEach
	void setUp() throws Exception {
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE T (ID INT, A INT)", "INSERT INTO T VALUES (1, 1), (2, 2), (3, 3)");
		Connection real = db.connection;
		db.connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class }, (proxy, method, args) -> {
			Object result = invoke(real, method, args);
			if (method.getName().equals("prepareStatement")) {
				events.add("prepare");
				return statementProxy((PreparedStatement) result);
			}
			return result;
		});
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private PreparedStatement statementProxy(PreparedStatement real) {
		return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] { PreparedStatement.class },
				(proxy, method, args) -> {
					if (method.getName().equals("setMaxRows")) {
						events.add("maxRows=" + args[0]);
					} else if (method.getName().equals("close")) {
						events.add("statement closed");
					}
					Object result = invoke(real, method, args);
					if (method.getName().equals("getResultSet") && result != null) {
						return resultSetProxy((ResultSet) result);
					}
					return result;
				});
	}

	private ResultSet resultSetProxy(ResultSet real) {
		return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] { ResultSet.class }, (proxy, method, args) -> {
			if (method.getName().equals("close")) {
				events.add("result set closed");
			}
			Object result = invoke(real, method, args);
			if (method.getName().equals("next") && Boolean.TRUE.equals(result)) {
				rowsRead++;
			}
			return result;
		});
	}

	private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "SELECT ID FROM T WHERE ID = 1", "SELECT ID FROM T", "SELECT ID FROM T WHERE ID = 9", "SELECT ID, A FROM T WHERE ID = 1",
			"SELECT * FROM NOPE", "INSERT INTO T VALUES (4, 4)" })
	void everyOutcomeClosesItsResourcesAndReadsAtMostTwoRows(String query) {
		try {
			db.queryScalar(PreparedSql.unbound(query));
		} catch (BroadSQLException expectedForMostQueries) {
			// the shape errors and the SQL error are tested elsewhere; here only the resources matter
		}
		if (query.startsWith("SELECT * FROM NOPE")) {
			Assertions.assertTrue(events.isEmpty(), "the preparation itself failed: nothing to close " + events);
		} else {
			Assertions.assertTrue(events.contains("prepare"), events.toString());
			Assertions.assertTrue(events.contains("maxRows=2"), events.toString());
			Assertions.assertTrue(events.contains("statement closed"), events.toString());
		}
		if (query.startsWith("SELECT ID") || query.startsWith("SELECT ID,")) {
			Assertions.assertTrue(events.contains("result set closed"), events.toString());
		}
		Assertions.assertTrue(rowsRead <= 2, "rows read: " + rowsRead);
	}
}
