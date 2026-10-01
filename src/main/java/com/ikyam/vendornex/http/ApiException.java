package com.ikyam.vendornex.http;

/** An error that maps directly onto an HTTP status + message for the client. */
public class ApiException extends RuntimeException {

    public final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException badRequest(String m) { return new ApiException(400, m); }
    public static ApiException unauthorized(String m) { return new ApiException(401, m); }
    public static ApiException forbidden(String m) { return new ApiException(403, m); }
    public static ApiException notFound(String what) { return new ApiException(404, what + " not found"); }
    public static ApiException conflict(String m) { return new ApiException(409, m); }
    public static ApiException badGateway(String m) { return new ApiException(502, m); }
}
