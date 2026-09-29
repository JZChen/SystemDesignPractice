package com.googledocs.controller;

import com.googledocs.model.DocumentResponse;
import com.googledocs.model.EngineType;
import com.googledocs.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller exposing Document Creation and Reading APIs.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * API 1: Creates document and returns document URL with session.
     */
    @PostMapping
    public ResponseEntity<DocumentResponse> createDocument(@RequestBody(required = false) Map<String, Object> body) {
        String title = "Untitled Document";
        EngineType engineType = EngineType.OT;

        if (body != null) {
            if (body.containsKey("title") && body.get("title") != null) {
                title = body.get("title").toString();
            }
            if (body.containsKey("engineType") && body.get("engineType") != null) {
                try {
                    engineType = EngineType.valueOf(body.get("engineType").toString().toUpperCase());
                } catch (IllegalArgumentException ignored) {}
            }
        }

        DocumentResponse response = documentService.createDocument(title, engineType);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * API 2: Reads document by document URL / ID.
     */
    @GetMapping("/{docId}")
    public ResponseEntity<DocumentResponse> readDocument(
            @PathVariable String docId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        DocumentResponse response = documentService.getDocument(docId, sessionId);
        return ResponseEntity.ok(response);
    }
}
