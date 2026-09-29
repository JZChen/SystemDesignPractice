package com.googledocs.model;

/**
 * Concurrency strategy supported for the document.
 */
public enum EngineType {
    OT,   // Operational Transformation (Revision log with index shifting)
    CRDT  // Conflict-free Replicated Data Type (Fractional LSeq indexing with tombstones)
}
