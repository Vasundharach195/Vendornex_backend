package com.ikyam.vendornex.service;

import com.ikyam.vendornex.common.Settings;
import com.ikyam.vendornex.controller.UserController;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.entity.ApprovalStep;
import com.ikyam.vendornex.entity.User;
import com.ikyam.vendornex.entity.Vendor;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.repository.ApprovalQueries;
import com.ikyam.vendornex.repository.ApprovalStepRepository;
import com.ikyam.vendornex.repository.UserRepository;
import com.ikyam.vendornex.repository.VendorRepository;
import com.ikyam.vendornex.security.CurrentUser;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Configurable multi-stage approval chain shared by Vendors, Requesters and Purchase Requests.
 * Stages run in the configured order; each stage can only be actioned by a user holding it
 * (ADMIN stage -&gt; Admin role; FINANCE / PROCUREMENT / COMPLIANCE -&gt; Approvers assigned that stage).
 * A rejection ends the round; resubmission starts a new round so history is kept.
 *
 * <p>{@code VendorService} and {@code PurchaseRequestService} are injected {@code @Lazy} because the
 * reference is mutual (both also call back into this class). For vendors: this class's
 * {@code onApproved} calls into {@code VendorService.onApprovalComplete}, while
 * {@code VendorService.submit} calls back into this class's {@code start}/{@code onApproved} — the
 * same mutual-call shape the original static-method version had, now expressed as a Spring bean
 * graph, where a plain constructor cycle would fail to start.
 */
@Service
public class ApprovalService {

    private final ApprovalStepRepository steps;
    private final ApprovalQueries queries;
    private final VendorRepository vendors;
    private final UserRepository users;
    private final VendorService vendorService;
    private final PurchaseRequestService purchaseRequestService;

    public ApprovalService(ApprovalStepRepository steps, ApprovalQueries queries, VendorRepository vendors,
                            UserRepository users, @Lazy VendorService vendorService,
                            @Lazy PurchaseRequestService purchaseRequestService) {
        this.steps = steps;
        this.queries = queries;
        this.vendors = vendors;
        this.users = users;
        this.vendorService = vendorService;
        this.purchaseRequestService = purchaseRequestService;
    }

    /** Result of starting a chain: AUTO means no stages configured — the entity is approved immediately. */
    public enum Start { AUTO, PENDING }

    @Transactional(rollbackFor = Exception.class)
    public Start start(UUID companyId, String entityType, UUID entityId) {
        List<String> stages = Settings.stages(companyId, entityType);
        if (stages.isEmpty()) return Start.AUTO;
        int round = this.steps.nextRound(entityType, entityId);
        List<ApprovalStep> toSave = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            toSave.add(new ApprovalStep(companyId, entityType, entityId, stages.get(i), i + 1, i == 0 ? "PENDING" : "NOT_STARTED", round));
        }
        this.steps.saveAll(toSave);
        return Start.PENDING;
    }

    /** Steps of the latest round, with approver names. */
    public List<Row> steps(String entityType, UUID entityId) {
        return queries.steps(entityType, entityId);
    }

    /** SQL fragment giving the latest-round step summary as JSON for list screens. Stays a plain
     * static string-concatenation helper (no DB access of its own) — spliced as literal SQL text
     * into callers' own JdbcTemplate queries, never a bind parameter. */
    public static String summarySql(String entityType, String idColumn) {
        return "(SELECT json_agg(json_build_object('stage', a.stage, 'status', a.status) ORDER BY a.seq) FROM approval_steps a " +
                "WHERE a.entity_type = '" + entityType + "' AND a.entity_id = " + idColumn +
                " AND a.round = (SELECT max(round) FROM approval_steps b WHERE b.entity_type = '" + entityType + "' AND b.entity_id = " + idColumn + "))";
    }

    /** Final approval. Returns a sync transaction to run after commit, if the approval triggers a B1 write. */
    @Transactional(rollbackFor = Exception.class)
    public UUID onApproved(String type, UUID entityId, UUID companyId, UUID userId) {
        return switch (type) {
            case "VENDOR" -> vendorService.onApprovalComplete(companyId, entityId, userId);
            case "REQUESTER" -> { UserController.onRequesterApproved(entityId); yield null; }
            case "PURCHASE_REQUEST" -> purchaseRequestService.onApproved(companyId, entityId, userId);
            default -> throw new IllegalStateException(type);
        };
    }

    /** Result of {@link #act}: the response row plus any sync transaction id to run after commit. */
    public static final class ActResult {
        public final Row response;
        public final List<UUID> postCommitSyncIds;
        ActResult(Row response, List<UUID> postCommitSyncIds) {
            this.response = response;
            this.postCommitSyncIds = postCommitSyncIds;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public ActResult act(UUID stepId, CurrentUser u, boolean approve, String remarks) {
        List<UUID> postCommit = new ArrayList<>();
        ApprovalStep step = steps.lockByIdAndCompanyId(stepId, u.company()).orElseThrow(() -> ApiException.notFound("Approval step"));
        if (!"PENDING".equals(step.getStatus())) throw ApiException.conflict("This step has already been actioned");
        if (!u.canActOnStage(step.getStage())) {
            throw ApiException.forbidden("Only a " + step.getStage().toLowerCase(Locale.ROOT) + " approver can action this stage");
        }
        String type = step.getEntityType();
        UUID entityId = step.getEntityId();
        if ("PURCHASE_REQUEST".equals(type)) {
            if (u.userId().equals(purchaseRequestService.requesterOf(entityId))) throw ApiException.forbidden("You cannot approve your own purchase request");
        }
        step.setStatus(approve ? "APPROVED" : "REJECTED");
        step.setActedByUserId(u.userId());
        step.setActedAt(java.time.Instant.now());
        step.setRemarks(remarks);
        steps.save(step);
        if (!approve) {
            steps.skipRemainingNotStarted(type, entityId, step.getRound());
            onRejected(type, entityId, remarks);
            return new ActResult(new Row().with("outcome", "REJECTED"), postCommit);
        }
        var next = steps.findFirstByEntityTypeAndEntityIdAndRoundAndStatusOrderBySeq(type, entityId, step.getRound(), "NOT_STARTED");
        if (next.isPresent()) {
            ApprovalStep n = next.get();
            n.setStatus("PENDING");
            steps.save(n);
            return new ActResult(new Row().with("outcome", "NEXT_STAGE").with("nextStage", n.getStage()), postCommit);
        }
        UUID txId = onApproved(type, entityId, u.company(), u.userId());
        if (txId != null) postCommit.add(txId);
        return new ActResult(new Row().with("outcome", "APPROVED"), postCommit);
    }

    @Transactional(rollbackFor = Exception.class)
    public void onRejected(String type, UUID entityId, String reason) {
        switch (type) {
            case "VENDOR" -> {
                Vendor v = vendors.findById(entityId).orElseThrow();
                v.setStatus("REJECTED");
                v.setRejectionReason(reason);
                vendors.save(v);
            }
            case "REQUESTER" -> {
                User u = users.findById(entityId).orElseThrow();
                u.setStatus("REJECTED");
                u.setRejectionReason(reason);
                users.save(u);
            }
            case "PURCHASE_REQUEST" -> purchaseRequestService.onRejected(entityId, reason);
            default -> throw new IllegalStateException(type);
        }
    }
}
