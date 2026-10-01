package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findByIdAndCompanyId(UUID id, UUID companyId);

    List<User> findByCompanyIdAndRoleOrderByCreatedAt(UUID companyId, String role);

    List<User> findByCompanyIdAndRoleIn(UUID companyId, List<String> roles);
}
