package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.SapConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SapConnectionRepository extends JpaRepository<SapConnection, UUID> {

    /** DB-selector dropdown on the login screen. */
    List<SapConnection> findBySchemaIdAndActiveTrueOrderBySequenceNoAscConnectionNameAsc(String schemaId);

    List<SapConnection> findBySchemaIdOrderBySequenceNoAscConnectionNameAsc(String schemaId);

    Optional<SapConnection> findBySchemaIdAndDefaultConnectionTrue(String schemaId);

    Optional<SapConnection> findByConnectionIdAndSchemaId(UUID connectionId, String schemaId);
}
