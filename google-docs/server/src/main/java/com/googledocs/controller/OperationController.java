package com.googledocs.controller;

import com.googledocs.model.CommittedOperation;
import com.googledocs.model.OperationRequest;
import com.googledocs.model.OperationResult;
import com.googledocs.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller exposing Document Operations (edit, insert, remove characters) via OT / CRDT.
 */
@RestController
@RequestMapping("/api/documents/{docId}/operations")
public class OperationController {

    private final DocumentService documentService;

    public OperationController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * API 3: Applies a character-level mutation operation (INSERT, DELETE, REPLACE).
     */
    @PostMapping
    public ResponseEntity<OperationResult> applyOperation(
            @PathVariable String docId,
            @Valid @RequestBody OperationRequest request) {
        OperationResult result = documentService.applyOperation(docId, request);
        return ResponseEntity.ok(result);
    }

    /**
     * Optional catch-up API: retrieves committed operations since a given revision.
     */
    @GetMapping
    public ResponseEntity<List<CommittedOperation>> getOperationsSince(
            @PathVariable String docId,
            @RequestParam(defaultValue = "0") long sinceRevision) {
        List<CommittedOperation> ops = documentService.getOperationsSince(docId, sinceRevision);
        return ResponseEntity.ok(ops);
    }
}
