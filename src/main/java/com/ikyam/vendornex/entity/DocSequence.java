package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Maps {@code doc_sequences}. The actual allocate-and-return counter operation
 * ({@code DocNumbers.next()}) stays a native {@code INSERT ... ON CONFLICT ... RETURNING} query
 * (not expressible as a Spring Data derived/entity operation) — this entity exists for schema
 * validation and any future read/admin screen, not for the hot allocation path.
 */
@Entity
@Table(name = "doc_sequences")
public class DocSequence {

    @EmbeddedId
    private DocSequenceId id;

    @Column(nullable = false, length = 20)
    private String prefix;

    @Column(name = "next_value", nullable = false)
    private int nextValue;

    protected DocSequence() {}

    public DocSequenceId getId() { return id; }
    public String getPrefix() { return prefix; }
    public int getNextValue() { return nextValue; }
}
