package com.kindlereader.app.net;

/** Thrown for any non-2xx API response or network/parsing failure. */
public class ApiException extends Exception {

    public final int httpStatus;
    public final String errorCode;

    public ApiException(String message) {
        this(0, null, message);
    }

    public ApiException(int httpStatus, String errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public boolean isUnauthorized() {
        return httpStatus == 401;
    }
}
