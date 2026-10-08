package com.upandcoding.broadsql.dao.load;

/**
 * Carries a per-value preflight rejection reason (e.g. "cannot be converted to DATE") from
 * {@link LoadValueConverter} back up to {@link LoadValidator}, which turns it into a
 * {@link LoadRowIssue} - never propagated further, never surfaces a stack trace to the user.
 */
final class LoadValueConversionException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	LoadValueConversionException(String reason) {
		super(reason);
	}
}
