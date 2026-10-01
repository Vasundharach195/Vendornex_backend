package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.PurchaseRequestLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PurchaseRequestLineRepository extends JpaRepository<PurchaseRequestLine, UUID> {
}
