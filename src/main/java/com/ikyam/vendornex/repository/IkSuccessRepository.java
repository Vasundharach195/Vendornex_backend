package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.IkSuccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IkSuccessRepository extends JpaRepository<IkSuccess, String> {

    List<IkSuccess> findByTypeOrderByExecutedAtDesc(String type);
}
