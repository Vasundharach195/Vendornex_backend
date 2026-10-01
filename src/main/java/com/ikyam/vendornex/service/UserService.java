package com.ikyam.vendornex.service;

import com.ikyam.vendornex.common.Validators;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.dto.CreateInternalUserRequest;
import com.ikyam.vendornex.dto.CreateRequesterRequest;
import com.ikyam.vendornex.dto.UpdateInternalUserRequest;
import com.ikyam.vendornex.dto.UpdateRequesterRequest;
import com.ikyam.vendornex.entity.User;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.http.Json;
import com.ikyam.vendornex.repository.UserQueries;
import com.ikyam.vendornex.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Business logic moved out of {@code UserController} (internal users/approvers, and requesters).
 * {@code user_approval_stages} is an {@code @ElementCollection} on {@link User}, so
 * create/update/list all go through {@link UserRepository} entities directly — no raw SQL needed
 * for the "ARRAY(subquery)" shape the pre-migration code built by hand. Requester screens still
 * join against not-yet-converted tables ({@code sap_employees}) or splice
 * {@link ApprovalService#summarySql} and stay on {@link UserQueries} (JdbcTemplate) accordingly.
 */
@Service
public class UserService {

    private static final Set<String> STAGES = Set.of("FINANCE", "PROCUREMENT", "COMPLIANCE");

    private final UserRepository users;
    private final UserQueries queries;
    private final ApprovalService approvalService;

    public UserService(UserRepository users, UserQueries queries, ApprovalService approvalService) {
        this.users = users;
        this.queries = queries;
        this.approvalService = approvalService;
    }

    // ------------------------------------------------------------------ internal users

    public List<Row> listInternal(UUID companyId) {
        return users.findByCompanyIdAndRoleIn(companyId, List.of("ADMIN", "APPROVER")).stream()
                .sorted((a, b) -> {
                    int r = a.getRole().compareTo(b.getRole());
                    return r != 0 ? r : a.getName().compareTo(b.getName());
                })
                .map(this::toInternalRow)
                .toList();
    }

    private Row toInternalRow(User u) {
        Row r = new Row();
        r.put("id", u.getId().toString());
        r.put("name", u.getName());
        r.put("email", u.getEmail());
        r.put("role", u.getRole());
        r.put("status", u.getStatus());
        r.put("lastLoginAt", u.getLastLoginAt() == null ? null : u.getLastLoginAt().toString());
        r.put("createdAt", u.getCreatedAt() == null ? null : u.getCreatedAt().toString());
        r.put("stages", new TreeSet<>(u.getApprovalStages()));
        r.put("inviteLink", AuthService.inviteLink(u.getInviteToken()));
        return r;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createInternal(CreateInternalUserRequest req, UUID companyId) {
        String name = Json.reqText(req.name, "name", 150);
        String email = Validators.email(Json.reqText(req.email, "email"));
        String role = Json.reqText(req.role, "role");
        if (!Set.of("ADMIN", "APPROVER").contains(role)) throw ApiException.badRequest("role must be ADMIN or APPROVER");
        List<String> stages = validateStages(req.stages);
        if ("APPROVER".equals(role) && stages.isEmpty()) throw ApiException.badRequest("Pick at least one approval stage for an approver");
        ensureEmailFree(email);

        User u = new User();
        u.setCompanyId(companyId);
        u.setName(name);
        u.setEmail(email);
        u.setRole(role);
        u.setStatus("INVITED");
        if ("APPROVER".equals(role)) u.setApprovalStages(new LinkedHashSet<>(stages));
        users.save(u);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", u.getId());
        out.put("inviteLink", AuthService.issueInvite(u.getId()));
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<Row> updateInternal(UUID id, UpdateInternalUserRequest req, UUID companyId) {
        User u = users.findByIdAndCompanyId(id, companyId)
                .filter(x -> "ADMIN".equals(x.getRole()) || "APPROVER".equals(x.getRole()))
                .orElseThrow(() -> ApiException.notFound("User"));
        String name = Json.optText(req.name);
        List<String> stages = validateStages(req.stages);
        if ("APPROVER".equals(u.getRole()) && stages.isEmpty()) throw ApiException.badRequest("An approver needs at least one stage");
        if (name != null) u.setName(name);
        if ("APPROVER".equals(u.getRole())) u.setApprovalStages(new LinkedHashSet<>(stages));
        users.save(u);
        return listInternal(companyId);
    }

    private static List<String> validateStages(List<String> raw) {
        List<String> stages = Json.textList(raw);
        for (String s : stages) if (!STAGES.contains(s)) throw ApiException.badRequest("Unknown stage " + s);
        return stages;
    }

    public String reinvite(UUID id, UUID companyId) {
        User u = users.findByIdAndCompanyId(id, companyId).orElseThrow(() -> ApiException.notFound("User"));
        if (!"INVITED".equals(u.getStatus())) throw ApiException.conflict("Only users who have not activated yet can be re-invited");
        return AuthService.issueInvite(u.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void setStatus(UUID id, boolean active, UUID companyId, UUID currentUserId) {
        User u = users.findByIdAndCompanyId(id, companyId).orElseThrow(() -> ApiException.notFound("User"));
        if (u.getId().equals(currentUserId)) throw ApiException.badRequest("You cannot disable your own account");
        if (active) {
            if (!"DISABLED".equals(u.getStatus())) throw ApiException.conflict("User is not disabled");
            u.setStatus(u.getPasswordHash() == null ? "INVITED" : "ACTIVE");
        } else {
            u.setStatus("DISABLED");
            u.setInviteToken(null);
        }
        users.save(u);
    }

    public List<Row> loginActivity(UUID companyId) {
        return queries.loginActivity(companyId);
    }

    // ------------------------------------------------------------------ requesters

    public List<Row> listRequesters(UUID companyId) {
        List<Row> rows = queries.listRequesters(companyId);
        rows.forEach(row -> row.put("inviteLink", AuthService.inviteLink((String) row.remove("inviteToken"))));
        return rows;
    }

    public Row requesterDetail(UUID id, UUID companyId) {
        Row u = queries.requesterDetail(id, companyId);
        if (u == null) throw ApiException.notFound("Requester");
        u.put("inviteLink", AuthService.inviteLink((String) u.remove("inviteToken")));
        u.put("approvals", approvalService.steps("REQUESTER", u.uuid("id")));
        return u;
    }

    @Transactional(rollbackFor = Exception.class)
    public Row createRequester(CreateRequesterRequest req, UUID companyId) {
        String name = Json.reqText(req.name, "name", 150);
        String email = Validators.email(Json.reqText(req.email, "email"));
        String dept = Json.optText(req.department);
        checkEmployee(companyId, req.sapEmployeeId);
        ensureEmailFree(email);

        User u = new User();
        u.setCompanyId(companyId);
        u.setName(name);
        u.setEmail(email);
        u.setRole("REQUESTER");
        u.setStatus("PENDING_APPROVAL");
        u.setDepartment(dept);
        u.setSapEmployeeId(req.sapEmployeeId);
        users.save(u);

        Row r = new Row().with("id", u.getId().toString());
        if (approvalService.start(companyId, "REQUESTER", u.getId()) == ApprovalService.Start.AUTO) {
            r.put("inviteLink", AuthService.issueInvite(u.getId()));
        }
        return r;
    }

    @Transactional(rollbackFor = Exception.class)
    public Row updateRequester(UUID id, UpdateRequesterRequest req, UUID companyId) {
        User u = users.findByIdAndCompanyId(id, companyId)
                .filter(x -> "REQUESTER".equals(x.getRole()))
                .orElseThrow(() -> ApiException.notFound("Requester"));
        checkEmployee(companyId, req.sapEmployeeId);
        String name = Json.optText(req.name);
        u.setName(name != null ? name : u.getName());
        u.setDepartment(Json.optText(req.department));
        u.setSapEmployeeId(req.sapEmployeeId);
        users.save(u);
        return requesterDetail(id, companyId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Row resubmitRequester(UUID id, UUID companyId) {
        User u = users.findByIdAndCompanyId(id, companyId)
                .filter(x -> "REQUESTER".equals(x.getRole()))
                .orElseThrow(() -> ApiException.notFound("Requester"));
        if (!"REJECTED".equals(u.getStatus())) throw ApiException.conflict("Only rejected requesters can be resubmitted");
        u.setStatus("PENDING_APPROVAL");
        u.setRejectionReason(null);
        users.save(u);
        if (approvalService.start(companyId, "REQUESTER", u.getId()) == ApprovalService.Start.AUTO) {
            AuthService.issueInvite(u.getId());
        }
        return requesterDetail(id, companyId);
    }

    private void checkEmployee(UUID companyId, Integer emp) {
        if (emp != null && !queries.employeeExists(companyId, emp)) {
            throw ApiException.badRequest("SAP B1 employee " + emp + " not found — sync masters first");
        }
    }

    public void ensureEmailFree(String email) {
        if (users.existsByEmailIgnoreCase(email)) throw ApiException.conflict("A user with e-mail " + email + " already exists");
    }
}
