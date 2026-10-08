package com.upandcoding.broadsql.controller.shell.commands;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test instrumentation of the real JDBC objects of a connection: every call goes to the real driver (H2), and the
 * test sees what BroadSQL did with them: which SQL was sent, how often {@code Statement.cancel()} was called. Two
 * optional delays stand for a slow network, never for BroadSQL: {@link #holdNextStatementCreation()} (a statement
 * being prepared when CTRL+C arrives, before anything is sent) and {@link #slowFetch(long)} (rows still arriving after
 * the query executed).
 */
final class RecordingJdbc {

	final List<String> executed = new CopyOnWriteArrayList<>();
	final AtomicInteger cancels = new AtomicInteger();
	final CountDownLatch creationHeld = new CountDownLatch(1);
	private volatile CountDownLatch holdCreation;
	private volatile long fetchDelayMillis;

	/** {@code real} seen through this recorder. */
	Connection wrap(Connection real) {
		return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class }, (proxy, method, args) -> {
			String name = method.getName();
			if ((name.equals("createStatement") || name.equals("prepareStatement")) && holdCreation != null) {
				CountDownLatch release = holdCreation;
				holdCreation = null;
				creationHeld.countDown();
				release.await(30, TimeUnit.SECONDS);
			}
			Object result = invoke(real, method, args);
			if (result instanceof PreparedStatement prepared) {
				return statement(prepared, PreparedStatement.class, (String) args[0]);
			}
			if (result instanceof Statement statement) {
				return statement(statement, Statement.class, null);
			}
			return result;
		});
	}

	/** The next statement BroadSQL creates waits until the returned latch is released. */
	CountDownLatch holdNextStatementCreation() {
		CountDownLatch release = new CountDownLatch(1);
		holdCreation = release;
		return release;
	}

	/** Every row read with {@code ResultSet.next()} from now on takes {@code millis}. */
	void slowFetch(long millis) {
		fetchDelayMillis = millis;
	}

	private Object statement(Statement real, Class<?> type, String preparedSql) {
		InvocationHandler handler = (proxy, method, args) -> {
			String name = method.getName();
			if (name.equals("cancel")) {
				cancels.incrementAndGet();
			} else if (name.startsWith("execute")) {
				executed.add(args != null && args.length > 0 && args[0] instanceof String sql ? sql : preparedSql);
			}
			Object result = invoke(real, method, args);
			return result instanceof ResultSet rs ? resultSet(rs) : result;
		};
		return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { type }, handler);
	}

	private Object resultSet(ResultSet real) {
		return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ResultSet.class }, (proxy, method, args) -> {
			if (method.getName().equals("next") && fetchDelayMillis > 0) {
				Thread.sleep(fetchDelayMillis);
			}
			return invoke(real, method, args);
		});
	}

	private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}
}
