package com.ikyam.vendornex.web;

import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.http.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Map;

/** Reproduces Router.handle's exact catch chain, status codes and error-body shape. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> apiException(ApiException e) {
        return body(e.status, e.getMessage());
    }

    @ExceptionHandler(Db.DbException.class)
    public ResponseEntity<?> dbException(Db.DbException e, HttpServletRequest req) {
        String state = e.sqlState();
        if ("23505".equals(state)) return body(409, "A record with the same unique value already exists");
        if ("23514".equals(state) || "23503".equals(state) || "22P02".equals(state)) {
            return body(400, "Invalid data: " + e.getMessage());
        }
        log.error("Database error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return body(500, "Database error");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformedJson(HttpMessageNotReadableException e) {
        return body(400, "Malformed JSON body");
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<?> noHandler(NoHandlerFoundException e) {
        return body(404, "No such endpoint");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return body(405, "Method not allowed");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> generic(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return body(500, "Internal server error");
    }

    private static ResponseEntity<?> body(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? "Error" : message));
    }
}
