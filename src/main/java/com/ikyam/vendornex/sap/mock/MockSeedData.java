package com.ikyam.vendornex.sap.mock;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ikyam.vendornex.http.Json;

import java.time.LocalDate;

/** Sample SAP B1 master data for the Service Layer simulator (taken from the VendorNex wireframe). */
final class MockSeedData {

    private MockSeedData() {}

    static ObjectNode build() {
        ObjectNode db = Json.obj();

        ArrayNode wh = db.putArray("Warehouses");
        wh(wh, "WH-01", "Main Store");
        wh(wh, "WH-02", "Chennai Plant");
        wh(wh, "WH-03", "Pune Unit");
        wh(wh, "WH-REJ", "Rejection Store");

        ArrayNode ig = db.putArray("ItemGroups");
        String[] groups = {"Raw Material", "Fasteners", "Components", "Consumables", "Electricals", "Packaging"};
        for (int i = 0; i < groups.length; i++) ig.addObject().put("Number", 101 + i).put("GroupName", groups[i]);

        ArrayNode items = db.putArray("Items");
        item(items, "ITM-1001", "Steel Rod 12mm", 101, "KG", 78.5, "WH-01", 4200, "WH-02", 1150);
        item(items, "ITM-1002", "Aluminium Sheet 2mm", 101, "SHT", 1120, "WH-01", 860, "WH-03", 210);
        item(items, "ITM-1010", "Hex Bolt M10x40", 102, "EA", 3.2, "WH-01", 18500);
        item(items, "ITM-1015", "Industrial Fan Motor 1HP", 103, "EA", 4250, "WH-01", 64);
        item(items, "ITM-1022", "PVC Insulation Tape", 104, "ROL", 22, "WH-02", 2300);
        item(items, "ITM-1030", "Copper Wire 4sq.mm", 105, "MTR", 145, "WH-01", 9800);
        item(items, "ITM-1041", "Corrugated Carton Box L", 106, "EA", 38, "WH-03", 5400);
        item(items, "ITM-1050", "Ball Bearing 6205ZZ", 103, "EA", 95, "WH-01", 1120);
        item(items, "ITM-1060", "Hydraulic Hose 1/2in", 103, "MTR", 210, "WH-01", 640);
        item(items, "ITM-1065", "Stainless Steel Sheet 1mm", 101, "SHT", 1850, "WH-02", 340);
        item(items, "ITM-1070", "Cable Gland M20", 105, "EA", 18, "WH-01", 4200);
        item(items, "ITM-1075", "Rubber Gasket 50mm", 102, "EA", 6.5, "WH-03", 7600);
        item(items, "ITM-1080", "LED Panel Light 40W", 105, "EA", 620, "WH-01", 280);
        item(items, "ITM-1085", "Stretch Wrap Film", 106, "ROL", 145, "WH-03", 1900);

        ArrayNode bg = db.putArray("BusinessPartnerGroups");
        String[] vg = {"Raw Material Suppliers", "Packaging Vendors", "Components Suppliers", "Service Providers",
                "Subcontractors", "Import Vendors", "Domestic Vendors"};
        for (int i = 0; i < vg.length; i++) bg.addObject().put("Code", 101 + i).put("Name", vg[i]).put("Type", "bbpgt_VendorGroup");
        bg.addObject().put("Code", 100).put("Name", "Customers").put("Type", "bbpgt_CustomerGroup");

        ArrayNode bps = db.putArray("BusinessPartners");
        bp(bps, "V-10023", "Precision Auto Components", 103, true, "27AAACP1234F1Z5", "vendor@precisionauto.com");
        bp(bps, "V-10024", "SteelCraft Industries", 101, true, "29AACCS5678K1Z2", "vendor@steelcraft.com");
        bp(bps, "V-10031", "Vantage Electricals", 103, true, "19AAGCV9988N1Z6", "vendor@vantageelec.com");
        bp(bps, "V-10038", "Meridian Tooling Works", 103, true, "27AACCM6543F1Z1", "vendor@meridiantooling.com");
        bp(bps, "V-10041", "GreenLeaf Packaging", 102, true, "06AADCG9081K1Z4", "vendor@greenleafpack.com");
        bp(bps, "V-10056", "Falcon Hydraulics Pvt Ltd", 103, false, "29AAECF3344L1Z7", "vendor@falconhydraulics.com");
        bp(bps, "V-10062", "Apex Castings Pvt Ltd", 101, true, "33AAECA4455M1Z2", "sales@apexcastings.in");
        bp(bps, "V-10064", "Kiran Polymers", 102, true, "24AAFCK7788N1Z3", "orders@kiranpolymers.in");
        bp(bps, "V-10066", "Omega Seals & Gaskets", 103, false, "27AAGCO1122P1Z8", "info@omegaseals.in");

        ArrayNode dep = db.putArray("Departments");
        String[] depts = {"Production", "Maintenance", "Electricals", "Stores"};
        for (int i = 0; i < depts.length; i++) dep.addObject().put("Code", i + 1).put("Name", depts[i]);

        ArrayNode emp = db.putArray("EmployeesInfo");
        emp(emp, 1, "Karthik", "Subramanian", 1, "karthik.s@acmemfg.com");
        emp(emp, 2, "Ananya", "Desai", 2, "ananya.d@acmemfg.com");
        emp(emp, 3, "Suresh", "Iyer", 3, "suresh.i@acmemfg.com");
        emp(emp, 4, "Meena", "Krishnan", 4, "meena.k@acmemfg.com");

        ArrayNode tax = db.putArray("SalesTaxCodes");
        tax.addObject().put("Code", "GST5").put("Name", "GST 5% (CGST+SGST)").put("Rate", 5);
        tax.addObject().put("Code", "GST12").put("Name", "GST 12% (CGST+SGST)").put("Rate", 12);
        tax.addObject().put("Code", "GST18").put("Name", "GST 18% (CGST+SGST)").put("Rate", 18);
        tax.addObject().put("Code", "IGST18").put("Name", "IGST 18% (inter-state)").put("Rate", 18);

        db.putArray("VatGroups");
        ArrayNode prs = db.putArray("PurchaseRequests");
        pr(prs, 1, 5021, "Suresh Iyer", 3, "ITM-1030", "Copper Wire 4sq.mm", "MTR", 1000, "WH-01", 12);
        pr(prs, 2, 5022, "Meena Krishnan", 4, "ITM-1070", "Cable Gland M20", "EA", 500, "WH-01", 18);
        db.putArray("PurchaseOrders");
        db.putArray("Drafts");
        db.putArray("PurchaseDeliveryNotes");

        ObjectNode c = db.putObject("counters");
        c.put("PurchaseRequestsEntry", 3).put("PurchaseRequestsNum", 5023);
        c.put("PurchaseOrdersEntry", 101).put("PurchaseOrdersNum", 1042);
        c.put("DraftsEntry", 501).put("DraftsNum", 501);
        c.put("PurchaseDeliveryNotesEntry", 801).put("PurchaseDeliveryNotesNum", 3311);
        c.put("bpSeriesNext", 30001);
        return db;
    }

    private static void wh(ArrayNode a, String code, String name) {
        a.addObject().put("WarehouseCode", code).put("WarehouseName", name).put("Inactive", "tNO");
    }

    private static void item(ArrayNode a, String code, String name, int group, String uom, double price, Object... stock) {
        ObjectNode i = a.addObject();
        i.put("ItemCode", code).put("ItemName", name).put("ItemsGroupCode", group).put("PurchaseUnit", uom)
                .put("InventoryUOM", uom).put("AvgStdPrice", price).put("Valid", "tYES").put("Frozen", "tNO")
                .put("PurchaseItem", "tYES");
        ArrayNode w = i.putArray("ItemWarehouseInfoCollection");
        for (int k = 0; k < stock.length; k += 2) {
            w.addObject().put("WarehouseCode", (String) stock[k]).put("InStock", (Integer) stock[k + 1]).put("Committed", 0).put("Ordered", 0);
        }
    }

    private static void bp(ArrayNode a, String code, String name, int group, boolean active, String gstin, String email) {
        ObjectNode b = a.addObject();
        b.put("CardCode", code).put("CardName", name).put("CardType", "cSupplier").put("GroupCode", group)
                .put("Valid", active ? "tYES" : "tNO").put("Frozen", active ? "tNO" : "tYES")
                .put("FederalTaxID", gstin.substring(2, 12)).put("EmailAddress", email);
        b.putArray("BPAddresses").addObject().put("AddressName", "Bill To").put("AddressType", "bo_BillTo").put("GSTIN", gstin).put("Country", "IN");
    }

    private static void emp(ArrayNode a, int id, String first, String last, int dept, String email) {
        a.addObject().put("EmployeeID", id).put("FirstName", first).put("LastName", last).put("Department", dept)
                .put("eMail", email).put("Active", "tYES");
    }

    private static void pr(ArrayNode a, int entry, int num, String requester, int dept, String item, String name, String uom,
                           int qty, String wh, int daysAhead) {
        ObjectNode d = a.addObject();
        String date = LocalDate.now().minusDays(5).toString();
        d.put("DocEntry", entry).put("DocNum", num).put("DocDate", date).put("RequriedDate", LocalDate.now().plusDays(daysAhead).toString())
                .put("RequesterName", requester).put("RequesterDepartment", dept).put("DocumentStatus", "bost_Open")
                .put("Comments", "Raised directly in SAP B1").put("UpdateDate", date);
        d.putArray("DocumentLines").addObject().put("LineNum", 0).put("ItemCode", item).put("ItemDescription", name)
                .put("MeasureUnit", uom).put("Quantity", qty).put("RemainingOpenQuantity", qty).put("WarehouseCode", wh)
                .put("RequiredDate", LocalDate.now().plusDays(daysAhead).toString()).put("LineStatus", "bost_Open");
    }
}
