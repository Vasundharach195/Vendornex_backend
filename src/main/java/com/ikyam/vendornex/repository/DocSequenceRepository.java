package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.DocSequence;
import com.ikyam.vendornex.entity.DocSequenceId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocSequenceRepository extends JpaRepository<DocSequence, DocSequenceId> {
}
