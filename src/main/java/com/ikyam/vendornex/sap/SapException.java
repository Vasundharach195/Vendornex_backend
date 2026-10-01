package com.ikyam.vendornex.sap;

/**
 * Service Layer failure. {@code retryable} is true for transport problems, timeouts and 5xx
 * (worth retrying later) and false for business-rule rejections (HTTP 4xx) that will fail
 * again until someone fixes the data.
 */
public class SapException extends RuntimeException {

    public final int httpStatus;
    public final boolean retryable;
    public final String sapCode;

    public SapException(String message, int httpStatus, boolean retryable, String sapCode) {
        super(message);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.sapCode = sapCode;
    }

    public static SapException business(String message) {
        return new SapException(message, 400, false, null);
    }
}
