package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.LoginActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LoginActivityRepository extends JpaRepository<LoginActivity, UUID> {
}
