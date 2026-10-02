package com.upandcoding.broadsql.controller.errors;

public class BroadSQLException extends Exception {

    private static final long serialVersionUID = 1L;

	public BroadSQLException() {
        super();
    }

    public BroadSQLException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    public BroadSQLException(String message, Throwable cause) {
        super(message, cause);
    }

    public BroadSQLException(String message) {
        super(message);
    }

    public BroadSQLException(Throwable cause) {
        super(cause);
    }

}
