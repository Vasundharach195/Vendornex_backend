package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Maps {@code purchase_request_lines}. */
@Entity
@Table(name = "purchase_request_lines")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PurchaseRequestLine {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "purchase_request_id", nullable = false, columnDefinition = "uuid")
    private UUID purchaseRequestId;

    @Column(name = "line_num", nullable = false)
    private Integer lineNum;

    @Column(name = "item_code", nullable = false, length = 50)
    private String itemCode;

    @Column(name = "item_name", nullable = false, length = 200)
    private String itemName;

    @Column(length = 20)
    private String uom;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    /** Quantity already moved into an RFQ or PO. */
    @Builder.Default
    @Column(name = "sourced_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal sourcedQty = BigDecimal.ZERO;

    @Column(name = "warehouse_code", length = 8)
    private String warehouseCode;

    @Column(name = "required_date")
    private LocalDate requiredDate;
}
