package com.googledocs.controller;

import com.googledocs.service.BroadcastService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Controller exposing real-time SSE stream for instantaneous multi-peer updates.
 */
@RestController
@RequestMapping("/api/documents/{docId}/events")
public class SyncStreamController {

    private final BroadcastService broadcastService;

    public SyncStreamController(BroadcastService broadcastService) {
        this.broadcastService = broadcastService;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToSync(
            @PathVariable String docId,
            @RequestParam(required = false) String sessionId) {
        return broadcastService.subscribe(docId, sessionId);
    }
}
