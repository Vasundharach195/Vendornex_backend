package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** E-mails are unique across all companies, so this checks global_users, not one company's users table. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM global_users WHERE lower(email) = lower(:email))", nativeQuery = true)
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    Optional<User> findByIdAndCompanyId(UUID id, UUID companyId);

    List<User> findByCompanyIdAndRoleOrderByCreatedAt(UUID companyId, String role);

    List<User> findByCompanyIdAndRoleIn(UUID companyId, List<String> roles);
}
