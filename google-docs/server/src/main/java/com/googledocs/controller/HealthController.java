package com.googledocs.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Lightweight liveness probe used by the browser to detect "server down" and "server restarted".
 *
 * <p>{@code bootId} is a random value generated once per JVM start. Documents live only in memory,
 * so a changed bootId tells an open tab that its document is gone and it must reload.
 * It carries no secrets and no document data.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final String bootId = UUID.randomUUID().toString();

    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(Map.of("status", "UP", "bootId", bootId));
    }
}
