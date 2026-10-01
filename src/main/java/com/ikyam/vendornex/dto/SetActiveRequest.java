package com.ikyam.vendornex.dto;

/** Request body for the user/company "set active status" endpoints (defaults to true, matching
 * {@code Json.optBool(b, "active", true)}). */
public class SetActiveRequest {
    public Boolean active;
}
