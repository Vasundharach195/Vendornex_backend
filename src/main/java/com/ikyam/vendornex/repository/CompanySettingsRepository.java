package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.CompanySettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CompanySettingsRepository extends JpaRepository<CompanySettings, UUID> {
}
