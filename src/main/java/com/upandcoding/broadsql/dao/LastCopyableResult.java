package com.upandcoding.broadsql.dao;

/**
 * A tiny, immutable tag saying which of the two existing "last result" mechanisms - the SQL
 * {@code lastSQLQuery} text ({@code CommandInterpreter}/{@code Command}) or
 * {@link LastApiExecutionResultHolder} - was produced most recently, for {@code COPY RESULT}'s benefit
 * (see {@link LastCopyableResultHolder}'s own Javadoc for why this exists and how it is kept current).
 *
 * <p>Deliberately not a general result-history mechanism: it holds just enough to let {@code COPY
 * RESULT} pick the right one of the two pre-existing sources and reuse them exactly as before - the SQL
 * text (still re-run fresh, as {@code COPY RESULT} already did) or the already-captured
 * {@link LastApiExecutionResult} object (stored here directly rather than re-read from
 * {@link LastApiExecutionResultHolder}, so a later failed {@code RUN} attempt clearing that holder - for
 * {@code PULL API RESULT}'s own, unrelated stale-result invariant - never invalidates a still-valid,
 * already-successful result this class is holding for {@code COPY RESULT}).
 */
public final class LastCopyableResult {

	public enum Source {
		SQL, API
	}

	private final Source source;
	private final String sqlQuery;
	private final LastApiExecutionResult apiResult;

	private LastCopyableResult(Source source, String sqlQuery, LastApiExecutionResult apiResult) {
		this.source = source;
		this.sqlQuery = sqlQuery;
		this.apiResult = apiResult;
	}

	static LastCopyableResult ofSql(String sqlQuery) {
		return new LastCopyableResult(Source.SQL, sqlQuery, null);
	}

	static LastCopyableResult ofApi(LastApiExecutionResult apiResult) {
		return new LastCopyableResult(Source.API, null, apiResult);
	}

	public Source getSource() {
		return source;
	}

	/** @return the SQL text to re-run - only meaningful when {@link #getSource()} is {@link Source#SQL}. */
	public String getSqlQuery() {
		return sqlQuery;
	}

	/** @return the captured API result - only meaningful when {@link #getSource()} is {@link Source#API}. */
	public LastApiExecutionResult getApiResult() {
		return apiResult;
	}
}
