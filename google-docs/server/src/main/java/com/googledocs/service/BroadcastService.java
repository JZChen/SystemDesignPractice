package com.googledocs.service;

import com.googledocs.model.OperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Server-Sent Event (SSE) emitters for real-time document broadcasts to global peers.
 */
@Service
public class BroadcastService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);
    // docId -> Set of active SseEmitters
    private final Map<String, Set<SseEmitter>> docEmitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String docId, String sessionId) {
        // 30 minute timeout for long-lived collaboration sessions
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        docEmitters.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(docId, emitter));
        emitter.onTimeout(() -> removeEmitter(docId, emitter));
        emitter.onError(e -> removeEmitter(docId, emitter));

        try {
            emitter.send(SseEmitter.event()
                .name("connected")
                .data(Map.of("docId", docId, "sessionId", sessionId, "message", "Connected to live sync stream")));
        } catch (IOException e) {
            removeEmitter(docId, emitter);
        }

        return emitter;
    }

    public void broadcastOperation(String docId, OperationResult result, String authorSessionId) {
        Set<SseEmitter> emitters = docEmitters.get(docId);
        if (emitters == null || emitters.isEmpty()) return;

        // LinkedHashMap: Map.of caps at 10 entries and rejects nulls (CRDT results have no ops).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("docId", docId);
        payload.put("revision", result.getRevision());
        payload.put("engineType", result.getEngineType());
        payload.put("type", result.getType());
        payload.put("position", result.getPosition());
        payload.put("text", result.getText());
        payload.put("length", result.getLength());
        payload.put("ops", result.getOps());
        payload.put("clientOpId", result.getClientOpId() != null ? result.getClientOpId() : "");
        payload.put("contentLength", result.getContentLength());
        // TODO(j2cl-client): drop full content for OT docs once every client applies ops (see SYSTEM_DESIGN_PLAN).
        payload.put("content", result.getContent());
        payload.put("authorSessionId", authorSessionId != null ? authorSessionId : "");
        payload.put("debugInfo", result.getDebugInfo());

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("operation")
                    .data(payload));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }
        deadEmitters.forEach(e -> removeEmitter(docId, e));
    }

    public void broadcastPresence(String docId, Collection<?> activeUsers) {
        Set<SseEmitter> emitters = docEmitters.get(docId);
        if (emitters == null || emitters.isEmpty()) return;

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("presence")
                    .data(Map.of("activeUsers", activeUsers)));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }
        deadEmitters.forEach(e -> removeEmitter(docId, e));
    }

    private void removeEmitter(String docId, SseEmitter emitter) {
        Set<SseEmitter> emitters = docEmitters.get(docId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                docEmitters.remove(docId);
            }
        }
    }
}
