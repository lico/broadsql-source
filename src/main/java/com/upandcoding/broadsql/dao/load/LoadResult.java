package com.upandcoding.broadsql.dao.load;

/** Outcome of {@link LoadExecutor#execute}: always either fully committed, or fully rolled back. */
public final class LoadResult {

	private final int rowsAttempted;
	private final int rowsInserted;
	private final boolean committed;

	public LoadResult(int rowsAttempted, int rowsInserted, boolean committed) {
		this.rowsAttempted = rowsAttempted;
		this.rowsInserted = rowsInserted;
		this.committed = committed;
	}

	public int getRowsAttempted() {
		return rowsAttempted;
	}

	public int getRowsInserted() {
		return rowsInserted;
	}

	public boolean isCommitted() {
		return committed;
	}
}
