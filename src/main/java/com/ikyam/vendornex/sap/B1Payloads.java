package com.ikyam.vendornex.sap;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.http.Json;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Builds Service Layer request bodies from VendorNex rows. All field names in one place so the
 * mapping can be reviewed (and adjusted for a localisation) without touching workflow code.
 *
 * <p>India localisation assumptions — confirm against the customer's B1 company:
 * GSTIN is sent per address (BPAddresses[].GSTIN + GstType) and PAN as the BP-level fiscal ID
 * (BPFiscalTaxIDCollection[Address=""].TaxId0). Other localisations send FederalTaxID instead.
 */
public final class B1Payloads {

    /** B1 object type of a Purchase Request, used as BaseType when a PO copies from it. */
    public static final int OBJ_PURCHASE_REQUEST = 1470000113;
    /** B1 object type of a Purchase Order, used as BaseType on GRPO lines. */
    public static final int OBJ_PURCHASE_ORDER = 22;

    private B1Payloads() {}

    // ------------------------------------------------------------------ Business Partner (vendor)

    public static ObjectNode businessPartner(Row v, Row settings, String cardCode) {
        ObjectNode p = Json.obj();
        if (cardCode != null) p.put("CardCode", cardCode);
        else p.put("Series", settings.integer("bpSeries"));
        p.put("CardName", v.str("legalName"));
        p.put("CardType", "cSupplier");
        if (v.integer("vendorGroupCode") != null) p.put("GroupCode", v.integer("vendorGroupCode"));
        putIf(p, "EmailAddress", v.str("email"));
        putIf(p, "Phone1", v.str("phone"));
        p.put("FreeText", "Onboarded via Ikyam VendorNex (ref " + v.str("id") + ")");

        if (v.str("contactName") != null) {
            p.put("ContactPerson", trim(v.str("contactName"), 50));
            ObjectNode ce = p.putArray("ContactEmployees").addObject();
            ce.put("Name", trim(v.str("contactName"), 50));
            putIf(ce, "E_Mail", v.str("email"));
            putIf(ce, "Phone1", v.str("phone"));
        }

        boolean india = settings.bool("indiaLocalization");
        ObjectNode addr = p.putArray("BPAddresses").addObject();
        addr.put("AddressName", "Bill To");
        addr.put("AddressType", "bo_BillTo");
        putIf(addr, "Street", v.str("street"));
        putIf(addr, "City", v.str("city"));
        putIf(addr, "State", v.str("state"));
        putIf(addr, "ZipCode", v.str("zipCode"));
        putIf(addr, "Country", v.str("country") == null ? (india ? "IN" : null) : v.str("country"));
        if (india && v.str("gstin") != null) {
            addr.put("GSTIN", v.str("gstin"));
            addr.put("GstType", "gstRegularTDSISD");
        }
        p.put("BilltoDefault", "Bill To");

        if (india) {
            if (v.str("pan") != null) {
                ObjectNode fid = p.putArray("BPFiscalTaxIDCollection").addObject();
                fid.put("Address", "");
                fid.put("AddrType", "bo_ShipTo");
                fid.put("TaxId0", v.str("pan"));
            }
        } else {
            putIf(p, "FederalTaxID", v.str("gstin") != null ? v.str("gstin") : v.str("pan"));
        }

        // Bank details go to B1 only when the vendor's bank is mapped to a B1 bank (ODSC) —
        // B1 rejects BPBankAccounts rows whose BankCode does not exist.
        if (v.str("sapBankCode") != null && v.str("bankAccountNo") != null) {
            ObjectNode b = p.putArray("BPBankAccounts").addObject();
            b.put("BankCode", v.str("sapBankCode"));
            b.put("AccountNo", v.str("bankAccountNo"));
            putIf(b, "BICSwiftCode", v.str("bankIfsc"));
            b.put("AccountName", trim(v.str("legalName"), 50));
            b.put("Country", v.str("country") == null ? "IN" : v.str("country"));
            p.put("DefaultBankCode", v.str("sapBankCode"));
            p.put("DefaultAccount", v.str("bankAccountNo"));
        }
        return p;
    }

    // ------------------------------------------------------------------ Purchase Request (push, APP mode)

    public static ObjectNode purchaseRequest(Row pr, List<Row> lines, Integer sapEmployeeId, String slUser) {
        ObjectNode p = Json.obj();
        if (sapEmployeeId != null) {
            p.put("ReqType", 171);          // employee
            p.put("Requester", String.valueOf(sapEmployeeId));
        } else {
            p.put("ReqType", 12);           // B1 user
            p.put("Requester", slUser == null ? "manager" : slUser);
        }
        LocalDate req = pr.date("requiredDate");
        if (req != null) p.put("RequriedDate", req.toString()); // sic — Service Layer property name
        p.put("Comments", trim("VendorNex " + pr.str("prNo") + (pr.str("justification") == null ? "" : " | " + pr.str("justification")), 254));
        ArrayNode dl = p.putArray("DocumentLines");
        for (Row l : lines) {
            ObjectNode o = dl.addObject();
            o.put("ItemCode", l.str("itemCode"));
            o.put("Quantity", l.dec("quantity"));
            putIf(o, "WarehouseCode", l.str("warehouseCode"));
            LocalDate d = l.date("requiredDate") != null ? l.date("requiredDate") : req;
            if (d != null) o.put("RequiredDate", d.toString());
        }
        return p;
    }

    // ------------------------------------------------------------------ Purchase Order

    /**
     * @param prBase for lines copied from a B1-synced Purchase Request: po_line_id → [DocEntry, LineNum]
     */
    public static ObjectNode purchaseOrder(Row po, List<Row> lines, Map<String, int[]> prBase, String ref) {
        ObjectNode p = Json.obj();
        p.put("CardCode", po.str("cardCode"));
        p.put("DocDate", po.str("docDate"));
        p.put("DocDueDate", po.str("docDueDate"));
        p.put("Comments", trim("VendorNex " + ref + (po.str("remarks") == null ? "" : " | " + po.str("remarks")), 254));
        ArrayNode dl = p.putArray("DocumentLines");
        for (Row l : lines) {
            ObjectNode o = dl.addObject();
            int[] base = prBase.get(l.str("id"));
            if (base != null) {
                o.put("BaseType", OBJ_PURCHASE_REQUEST);
                o.put("BaseEntry", base[0]);
                o.put("BaseLine", base[1]);
            }
            o.put("ItemCode", l.str("itemCode"));
            o.put("Quantity", l.dec("quantity"));
            o.put("UnitPrice", l.dec("unitPrice"));
            o.put("WarehouseCode", l.str("warehouseCode"));
            putIf(o, "TaxCode", l.str("taxCode"));
            if (l.str("shipDate") != null) o.put("ShipDate", l.str("shipDate"));
        }
        return p;
    }

    // ------------------------------------------------------------------ GRPO (draft from ASN, then posting)

    public static ObjectNode grpoDraft(Row po, Row asn, List<Row> asnLines) {
        ObjectNode p = grpoHeader(po, asn);
        p.put("DocObjectCode", "oPurchaseDeliveryNotes");
        ArrayNode dl = p.putArray("DocumentLines");
        for (Row l : asnLines) {
            ObjectNode o = dl.addObject();
            o.put("BaseType", OBJ_PURCHASE_ORDER);
            o.put("BaseEntry", po.integer("sapDocEntry"));
            o.put("BaseLine", l.integer("lineNum"));
            o.put("Quantity", l.dec("shippedQty"));
            o.put("WarehouseCode", l.str("warehouseCode"));
            if (l.str("batchNo") != null) {
                o.putArray("BatchNumbers").addObject().put("BatchNumber", l.str("batchNo")).put("Quantity", l.dec("shippedQty"));
            }
        }
        return p;
    }

    public static ObjectNode grpo(Row po, Row asn, Row grpo, List<Row> grpoLines) {
        ObjectNode p = grpoHeader(po, asn);
        if (grpo.str("postingDate") != null) p.put("DocDate", grpo.str("postingDate"));
        ArrayNode dl = p.putArray("DocumentLines");
        for (Row l : grpoLines) {
            BigDecimal qty = l.dec("receivedQty");
            if (qty == null || qty.signum() <= 0) continue;
            ObjectNode o = dl.addObject();
            o.put("BaseType", OBJ_PURCHASE_ORDER);
            o.put("BaseEntry", po.integer("sapDocEntry"));
            o.put("BaseLine", l.integer("lineNum"));
            o.put("Quantity", qty);
            o.put("WarehouseCode", l.str("warehouseCode"));
            if (l.str("batchNo") != null) {
                o.putArray("BatchNumbers").addObject().put("BatchNumber", l.str("batchNo")).put("Quantity", qty);
            }
        }
        return p;
    }

    private static ObjectNode grpoHeader(Row po, Row asn) {
        ObjectNode p = Json.obj();
        p.put("CardCode", po.str("cardCode"));
        p.put("DocDate", LocalDate.now().toString());
        p.put("NumAtCard", trim(asn.str("vendorInvoiceNo") != null ? asn.str("vendorInvoiceNo") : asn.str("asnNo"), 100));
        p.put("Comments", trim("VendorNex " + asn.str("asnNo") + " | " + asn.str("carrier") + " " + asn.str("trackingNo"), 254));
        return p;
    }

    // ------------------------------------------------------------------ helpers

    private static void putIf(ObjectNode n, String f, String v) {
        if (v != null && !v.isBlank()) n.put(f, v);
    }

    private static String trim(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
