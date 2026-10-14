package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.IkConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IkConfigRepository extends JpaRepository<IkConfig, UUID> {

    Optional<IkConfig> findBySchemaId(String schemaId);
}
