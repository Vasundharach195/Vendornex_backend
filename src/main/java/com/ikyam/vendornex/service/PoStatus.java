package com.ikyam.vendornex.service;

import com.ikyam.vendornex.db.Db;

import java.util.UUID;

/** Derives a purchase order's logistics status from its line quantities. */
public final class PoStatus {
    private PoStatus() {}

    public static void recompute(UUID poId) {
        Db.exec("""
                UPDATE purchase_orders p SET status = CASE
                    WHEN q.all_received THEN 'COMPLETED'
                    WHEN q.any_received THEN 'PARTIALLY_RECEIVED'
                    WHEN q.all_shipped  THEN 'SHIPPED'
                    WHEN q.any_shipped  THEN 'PARTIALLY_SHIPPED'
                    WHEN p.status IN ('PARTIALLY_SHIPPED','SHIPPED','PARTIALLY_RECEIVED','COMPLETED')
                         THEN CASE WHEN p.acknowledged_at IS NULL THEN 'PENDING_ACK' ELSE 'ACKNOWLEDGED' END
                    ELSE p.status END,
                    updated_at = now()
                  FROM (SELECT bool_and(received_qty >= quantity) AS all_received, bool_or(received_qty > 0) AS any_received,
                               bool_and(shipped_qty  >= quantity) AS all_shipped,  bool_or(shipped_qty  > 0) AS any_shipped
                          FROM purchase_order_lines WHERE purchase_order_id = ?) q
                 WHERE p.id = ? AND p.status NOT IN ('SAP_PENDING','SAP_FAILED','CANCELLED','DECLINED')""", poId, poId);
    }
}
