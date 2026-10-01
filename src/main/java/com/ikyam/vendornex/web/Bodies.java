package com.ikyam.vendornex.web;

import com.ikyam.vendornex.http.ApiException;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Raw request-body byte reading, mirroring Req.bytes(maxBytes) exactly (bounded read, same 413 message). */
public final class Bodies {
    private Bodies() {}

    public static byte[] readBytes(HttpServletRequest req, int maxBytes) {
        try {
            byte[] data = req.getInputStream().readNBytes(maxBytes + 1);
            if (data.length > maxBytes) throw new ApiException(413, "Request body too large (max " + maxBytes / 1024 / 1024 + " MB)");
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
