package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.ApprovalStep;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalStepRepository extends JpaRepository<ApprovalStep, UUID> {

    /** Mirrors {@code SELECT * FROM approval_steps WHERE id=? AND company_id=? FOR UPDATE}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApprovalStep a where a.id = :id and a.companyId = :companyId")
    Optional<ApprovalStep> lockByIdAndCompanyId(@Param("id") UUID id, @Param("companyId") UUID companyId);

    @Query("select coalesce(max(a.round), 0) + 1 from ApprovalStep a where a.entityType = :entityType and a.entityId = :entityId")
    int nextRound(@Param("entityType") String entityType, @Param("entityId") UUID entityId);

    Optional<ApprovalStep> findFirstByEntityTypeAndEntityIdAndRoundAndStatusOrderBySeq(
            String entityType, UUID entityId, int round, String status);

    @Modifying
    @Query("update ApprovalStep a set a.status = 'SKIPPED' where a.entityType = :entityType and a.entityId = :entityId " +
            "and a.round = :round and a.status = 'NOT_STARTED'")
    void skipRemainingNotStarted(@Param("entityType") String entityType, @Param("entityId") UUID entityId, @Param("round") int round);

    /** Entity withdrawn (e.g. PR cancelled): every step still waiting, in any round, is skipped. */
    @Modifying
    @Query("update ApprovalStep a set a.status = 'SKIPPED' where a.entityType = :entityType and a.entityId = :entityId " +
            "and a.status in ('PENDING', 'NOT_STARTED')")
    void skipOpenSteps(@Param("entityType") String entityType, @Param("entityId") UUID entityId);

    List<ApprovalStep> findByEntityTypeAndEntityIdAndRound(String entityType, UUID entityId, int round);
}
