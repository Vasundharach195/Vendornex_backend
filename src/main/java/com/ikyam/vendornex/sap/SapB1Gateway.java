package com.ikyam.vendornex.sap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything VendorNex needs from SAP Business One. There are two implementations:
 * {@link ServiceLayerGateway} (real Service Layer over HTTPS) and {@link MockSapB1Gateway}
 * (in-process simulator with the same validation rules, for demos and development).
 * Write methods take the exact Service Layer JSON body built by {@link B1Payloads}, so the
 * payload that is logged in sync_transactions is the payload B1 receives.
 */
public interface SapB1Gateway {

    ConnectionInfo testConnection();

    // ------------------------------------------------------------- master data (read)
    List<Warehouse> warehouses();
    List<Item> items();
    List<VendorGroup> vendorGroups();
    List<BusinessPartner> vendors();
    List<Employee> employees();
    List<TaxCode> taxCodes();

    // ------------------------------------------------------------- documents (read)
    List<Document> openPurchaseRequests();
    /** Open purchase orders changed on/after {@code since} (null = all open). */
    List<Document> openPurchaseOrders(LocalDate since);
    Document purchaseOrder(int docEntry);
    /** CardName of an existing BP, or null if the CardCode is free. */
    String businessPartnerName(String cardCode);

    // ------------------------------------------------------------- writes
    Result createBusinessPartner(ObjectNode payload);
    Result createPurchaseRequest(ObjectNode payload);
    Result createPurchaseOrder(ObjectNode payload);
    /** Drafts with DocObjectCode = oPurchaseDeliveryNotes (GRPO draft from an ASN). */
    Result createDraft(ObjectNode payload);
    /** Posts a Goods Receipt PO (PurchaseDeliveryNotes). */
    Result createGoodsReceiptPo(ObjectNode payload);
    void deleteDraft(int docEntry);

    // ------------------------------------------------------------- DTOs
    record ConnectionInfo(boolean ok, String message, String version) {}

    record Warehouse(String code, String name, boolean active) {}

    record Stock(String warehouseCode, BigDecimal inStock, BigDecimal committed, BigDecimal ordered) {}

    record Item(String code, String name, Integer groupCode, String groupName, String purchaseUom,
                String inventoryUom, BigDecimal avgPrice, boolean active, List<Stock> stock) {}

    record VendorGroup(int code, String name) {}

    record BusinessPartner(String cardCode, String cardName, Integer groupCode, boolean active, String federalTaxId, String email) {}

    record Employee(int id, String name, String department, String email) {}

    record TaxCode(String code, String name, BigDecimal rate) {}

    record DocLine(int lineNum, String itemCode, String itemName, String uom, BigDecimal quantity,
                   BigDecimal openQty, BigDecimal price, String warehouseCode, String taxCode, LocalDate date) {}

    record Document(int docEntry, int docNum, String cardCode, String requesterName, String department,
                    LocalDate docDate, LocalDate dueDate, String currency, String comments, String status,
                    List<DocLine> lines) {}

    /** Result of a create call: DocEntry / DocNum for documents, CardCode (key) for BPs. */
    record Result(Integer docEntry, Integer docNum, String key, JsonNode raw) {}
}
