package com.ikyam.vendornex.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Body of {@code POST /api/purchase-requests}. Dates and quantities are kept as raw strings and
 * validated in the service with the {@code Json} helpers, so error messages stay the same
 * as before ("'quantity' must be greater than zero", "'requiredDate' must be a date (YYYY-MM-DD)").
 */
@Data
@NoArgsConstructor
public class CreatePurchaseRequestRequest {

    /** Optional, YYYY-MM-DD; default for lines without their own date. */
    private String requiredDate;
    /** Required, max 2000 characters. */
    private String justification;
    /** Optional; defaults to the requester's department. */
    private String department;
    /** At least one line. */
    private List<Line> lines;

    @Data
    @NoArgsConstructor
    public static class Line {
        private String itemCode;
        private String quantity;
        /** Optional; defaults to the company's default warehouse. */
        private String warehouseCode;
        /** Optional; defaults to the header's requiredDate. */
        private String requiredDate;
    }
}
