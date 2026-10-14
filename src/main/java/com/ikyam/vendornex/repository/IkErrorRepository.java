package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.IkError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IkErrorRepository extends JpaRepository<IkError, String> {

    List<IkError> findByTypeOrderByExecutedAtDesc(String type);
}
