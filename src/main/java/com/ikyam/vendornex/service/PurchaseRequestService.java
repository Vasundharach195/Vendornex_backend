package com.ikyam.vendornex.service;

import com.ikyam.vendornex.common.DocNumbers;
import com.ikyam.vendornex.common.Settings;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.dto.CreatePurchaseRequestRequest;
import com.ikyam.vendornex.entity.PurchaseRequest;
import com.ikyam.vendornex.entity.PurchaseRequestLine;
import com.ikyam.vendornex.entity.User;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.http.Json;
import com.ikyam.vendornex.repository.ApprovalStepRepository;
import com.ikyam.vendornex.repository.PurchaseRequestLineRepository;
import com.ikyam.vendornex.repository.PurchaseRequestQueries;
import com.ikyam.vendornex.repository.PurchaseRequestRepository;
import com.ikyam.vendornex.repository.UserRepository;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Purchase Requests. APP mode: requesters raise PRs here, the configured chain approves them and
 * (optionally) they are pushed to B1 as Purchase Requests. B1_SYNC mode: open B1 PRs are mirrored
 * read-only. In both modes approved PR lines can be sourced into an RFQ or straight into a PO.
 *
 * <p>Write flows use {@link TransactionTemplate} so the order is always: DB transaction commits →
 * then the SAP call ({@code SyncService.processNow}) → then the response is read. An SAP outage can
 * therefore never roll back or lose a PR. Legacy static helpers called inside the transaction
 * ({@code DocNumbers}, {@code Settings}, {@code SyncService.enqueue}) join it via {@code Db}'s
 * Spring bridge, so JPA changes are flushed before {@code enqueue} reads the PR.
 */
@Service
@RequiredArgsConstructor
public class PurchaseRequestService {

    private static final String ENTITY_TYPE = "PURCHASE_REQUEST";

    private final PurchaseRequestRepository requests;
    private final PurchaseRequestLineRepository lines;
    private final PurchaseRequestQueries queries;
    private final ApprovalService approvalService;
    private final ApprovalStepRepository approvalSteps;
    private final UserRepository users;
    private final TransactionTemplate tx;

    // ================================================================== reads

    public List<Row> list(CurrentUser u, String status, String source) {
        UUID onlyMine = u.is(Role.REQUESTER) ? u.userId() : null; // requesters see only their own PRs
        return queries.list(u.company(), onlyMine, status, source);
    }

    public Row detail(UUID id, CurrentUser u) {
        Row p = queries.findRow(id, u.company());
        if (p == null) throw ApiException.notFound("Purchase request");
        // A requester opening someone else's PR gets "not found", not "forbidden" — don't reveal it exists.
        if (u.is(Role.REQUESTER) && !u.userId().equals(requesterOf(id))) throw ApiException.notFound("Purchase request");
        p.put("approvalSteps", approvalService.steps(ENTITY_TYPE, id));
        p.put("sourcedInto", queries.sourcedInto(id));
        p.put("sapSync", SyncService.latestFor(ENTITY_TYPE, id));
        return p;
    }

    /** Open (unsourced) quantity on approved PR lines, for the RFQ / PO conversion screens. */
    public List<Row> sourceableLines(String idsParam, UUID companyId) {
        if (idsParam == null) throw ApiException.badRequest("ids is required");
        UUID[] prIds = Arrays.stream(idsParam.split(",")).map(String::trim).map(UUID::fromString).toArray(UUID[]::new);
        return queries.sourceableLines(companyId, prIds);
    }

    /** Who raised the PR (used for "requester sees own only" and "cannot approve your own PR"). */
    public UUID requesterOf(UUID prId) {
        return requests.findById(prId).map(PurchaseRequest::getRequesterUserId).orElse(null);
    }

    // ================================================================== create

    public Row create(CreatePurchaseRequestRequest req, CurrentUser u) {
        UUID c = u.company();
        Row settings = Settings.of(c);
        if (!"APP".equals(settings.str("prMode"))) {
            throw ApiException.conflict("Purchase requests are synced from SAP B1 for this company — raise them in B1");
        }

        // ---- header validation (before opening a transaction)
        LocalDate required = Json.optDate(req.getRequiredDate(), "requiredDate");
        if (required != null && required.isBefore(LocalDate.now())) throw ApiException.badRequest("Required date cannot be in the past");
        String justification = Json.reqText(req.getJustification(), "justification", 2000);
        List<CreatePurchaseRequestRequest.Line> lineReqs = req.getLines();
        if (lineReqs == null || lineReqs.isEmpty()) throw ApiException.badRequest("'lines' must contain at least one entry");
        User me = users.findById(u.userId()).orElseThrow(() -> ApiException.notFound("User"));
        String department = Optional.ofNullable(Json.optText(req.getDepartment())).orElse(me.getDepartment());

        List<UUID> postCommit = new ArrayList<>();
        UUID prId = tx.execute(status -> {
            // ---- header
            PurchaseRequest pr = requests.save(PurchaseRequest.builder()
                    .companyId(c)
                    .prNo(DocNumbers.next(c, "PR"))
                    .source("APP")
                    .requesterUserId(u.userId())
                    .requesterName(me.getName())
                    .department(department)
                    .requiredDate(required)
                    .justification(justification)
                    .status("PENDING_APPROVAL")
                    .build());

            // ---- lines: item and warehouse must exist (and be active) in the SAP master-data cache
            List<PurchaseRequestLine> toSave = new ArrayList<>();
            int n = 0;
            for (CreatePurchaseRequestRequest.Line l : lineReqs) {
                if (l == null) l = new CreatePurchaseRequestRequest.Line();
                String item = Json.reqText(l.getItemCode(), "itemCode");
                BigDecimal qty = Json.reqPositive(l.getQuantity(), "quantity");
                String wh = Optional.ofNullable(Json.optText(l.getWarehouseCode())).orElse(settings.str("defaultWarehouseCode"));
                if (wh == null) throw ApiException.badRequest("Line " + (n + 1) + ": pick a warehouse");
                Row it = queries.findActiveItem(c, item);
                if (it == null) throw ApiException.badRequest("Line " + (n + 1) + ": item " + item + " is not an active purchase item in SAP B1");
                if (!queries.activeWarehouseExists(c, wh)) {
                    throw ApiException.badRequest("Line " + (n + 1) + ": warehouse " + wh + " not found in SAP B1");
                }
                toSave.add(PurchaseRequestLine.builder()
                        .purchaseRequestId(pr.getId())
                        .lineNum(n++)
                        .itemCode(item)
                        .itemName(it.str("itemName"))
                        .uom(it.str("purchaseUom"))
                        .quantity(qty)
                        .warehouseCode(wh)
                        .requiredDate(Optional.ofNullable(Json.optDate(l.getRequiredDate(), "requiredDate")).orElse(required))
                        .build());
            }
            requests.flush();            // header must be in the DB before its lines (FK)
            lines.saveAllAndFlush(toSave);

            // ---- start approval; no stages configured = approved immediately
            startApproval(c, pr.getId(), u.userId(), postCommit);
            return pr.getId();
        });

        postCommit.forEach(SyncService::processNow); // after commit: push to SAP B1 if needed
        return queries.findRow(prId, c);
    }

    // ================================================================== cancel / resubmit / retry

    public Row cancel(UUID prId, CurrentUser u) {
        PurchaseRequest p = ownOrAdmin(prId, u);
        if (!"APP".equals(p.getSource())) throw ApiException.conflict("B1 purchase requests are cancelled in SAP B1");
        if (!Set.of("PENDING_APPROVAL", "APPROVED", "REJECTED").contains(p.getStatus())) throw ApiException.conflict("This PR has already been sourced");
        tx.executeWithoutResult(status -> {
            p.setStatus("CANCELLED");
            requests.save(p);
            approvalSteps.skipOpenSteps(ENTITY_TYPE, prId);
        });
        return detail(prId, u);
    }

    /** A rejected PR goes back into the approval chain as a new round (history is kept). */
    public Row resubmit(UUID prId, CurrentUser u) {
        PurchaseRequest p = ownOrAdmin(prId, u);
        if (!"REJECTED".equals(p.getStatus())) throw ApiException.conflict("Only rejected purchase requests can be resubmitted");
        UUID c = u.company();
        List<UUID> postCommit = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            p.setStatus("PENDING_APPROVAL");
            p.setRejectionReason(null);
            requests.saveAndFlush(p);
            startApproval(c, prId, u.userId(), postCommit);
        });
        postCommit.forEach(SyncService::processNow);
        return detail(prId, u);
    }

    /** Admin retry of a failed push to SAP B1 (payload is rebuilt from current data). */
    public Row retryPush(UUID prId, CurrentUser u) {
        PurchaseRequest p = ownOrAdmin(prId, u);
        if (!"FAILED".equals(p.getSapPushStatus())) throw ApiException.conflict("Nothing to retry");
        Row last = SyncService.latestFor(ENTITY_TYPE, prId);
        if (last == null) throw ApiException.conflict("Nothing to retry");
        SyncService.retry(u.company(), last.uuid("id"));
        return detail(prId, u);
    }

    // ================================================================== lifecycle hooks (approval engine, RFQ, PO)

    /**
     * Final approval: optionally mirror the PR into SAP B1.
     *
     * @return the sync transaction to run after commit, or null
     */
    @Transactional(rollbackFor = Exception.class)
    public UUID onApproved(UUID companyId, UUID prId, UUID userId) {
        PurchaseRequest pr = requests.findById(prId).orElseThrow(() -> ApiException.notFound("Purchase request"));
        pr.setStatus("APPROVED");
        if (Settings.of(companyId).bool("pushPrToB1")) {
            pr.setSapPushStatus("QUEUED");
            requests.saveAndFlush(pr); // enqueue reads the PR through Db — it must already be written
            return SyncService.enqueue(companyId, "CREATE_PURCHASE_REQUEST", prId, userId);
        }
        requests.save(pr);
        return null;
    }

    @Transactional(rollbackFor = Exception.class)
    public void onRejected(UUID prId, String reason) {
        PurchaseRequest pr = requests.findById(prId).orElseThrow(() -> ApiException.notFound("Purchase request"));
        pr.setStatus("REJECTED");
        pr.setRejectionReason(reason);
        requests.save(pr);
    }

    /** After lines were pulled into an RFQ / PO (called once their transaction has committed). */
    @Transactional(rollbackFor = Exception.class)
    public void recomputeSourcing(UUID prId) {
        queries.recomputeSourcing(prId);
    }

    // ================================================================== helpers

    private void startApproval(UUID companyId, UUID prId, UUID userId, List<UUID> postCommit) {
        if (approvalService.start(companyId, ENTITY_TYPE, prId) == ApprovalService.Start.AUTO) {
            UUID syncId = onApproved(companyId, prId, userId);
            if (syncId != null) postCommit.add(syncId);
        }
    }

    /** The PR, if the caller may change it: admins any PR of their company, requesters only their own. */
    private PurchaseRequest ownOrAdmin(UUID id, CurrentUser u) {
        PurchaseRequest p = requests.findByIdAndCompanyId(id, u.company()).orElseThrow(() -> ApiException.notFound("Purchase request"));
        if (u.is(Role.REQUESTER) && !u.userId().equals(p.getRequesterUserId())) throw ApiException.notFound("Purchase request");
        return p;
    }
}
