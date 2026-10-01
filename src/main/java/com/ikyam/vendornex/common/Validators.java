package com.ikyam.vendornex.common;

import com.ikyam.vendornex.http.ApiException;

import java.util.regex.Pattern;

/** Indian statutory identifier formats plus e-mail. */
public final class Validators {
    private Validators() {}

    private static final Pattern GSTIN = Pattern.compile("^\\d{2}[A-Z]{5}\\d{4}[A-Z][A-Z0-9]Z[A-Z0-9]$");
    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}\\d{4}[A-Z]$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public static String gstin(String v) {
        if (v == null) return null;
        String s = v.trim().toUpperCase();
        if (!GSTIN.matcher(s).matches()) throw ApiException.badRequest("GSTIN '" + v + "' is not in the 15-character GSTIN format");
        return s;
    }

    public static String pan(String v) {
        if (v == null) return null;
        String s = v.trim().toUpperCase();
        if (!PAN.matcher(s).matches()) throw ApiException.badRequest("PAN '" + v + "' is not in the AAAAA9999A format");
        return s;
    }

    public static String ifsc(String v) {
        if (v == null) return null;
        String s = v.trim().toUpperCase();
        if (!IFSC.matcher(s).matches()) throw ApiException.badRequest("IFSC '" + v + "' is not a valid 11-character IFSC");
        return s;
    }

    public static String email(String v) {
        if (v == null) return null;
        String s = v.trim().toLowerCase();
        if (!EMAIL.matcher(s).matches()) throw ApiException.badRequest("'" + v + "' is not a valid e-mail address");
        return s;
    }

    /** GSTIN characters 3–12 are the PAN — catches mismatched data entry. */
    public static void gstinMatchesPan(String gstin, String pan) {
        if (gstin != null && pan != null && !gstin.substring(2, 12).equals(pan)) {
            throw ApiException.badRequest("GSTIN does not contain the PAN " + pan + " (characters 3–12 must match)");
        }
    }
}
