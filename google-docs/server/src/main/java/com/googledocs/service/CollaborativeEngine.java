package com.googledocs.service;

import com.googledocs.model.Document;
import com.googledocs.model.EngineType;
import com.googledocs.model.OperationRequest;
import com.googledocs.model.OperationResult;

/**
 * Strategy interface for collaborative concurrency control algorithms.
 */
public interface CollaborativeEngine {

    /**
     * Identifies the engine strategy (OT or CRDT).
     */
    EngineType getEngineType();

    /**
     * Applies an incoming character mutation operation to the document.
     * Must be called under document write lock.
     *
     * @param document the target document
     * @param request  the operation request payload
     * @return result containing applied operation, new revision, and debug metadata
     */
    OperationResult applyOperation(Document document, OperationRequest request);
}
