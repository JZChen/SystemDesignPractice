package com.collabcanvas.web;

import com.collabcanvas.board.BoardRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Board lifecycle. Everything else (snapshot, ops, presence) goes over the WebSocket. */
@RestController
@RequestMapping("/api")
public class BoardController {

    private static final String BOOT_ID = UUID.randomUUID().toString();

    private final BoardRegistry registry;

    public BoardController(BoardRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/boards")
    public ResponseEntity<Map<String, String>> create() {
        return registry.create()
                .map(b -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("boardId", b.id())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "too_many_boards")));
    }

    @GetMapping("/boards/{boardId}")
    public ResponseEntity<Map<String, Object>> exists(@PathVariable String boardId) {
        return registry.get(boardId)
                .<ResponseEntity<Map<String, Object>>>map(b -> ResponseEntity.ok(
                        Map.of("boardId", b.id(), "seq", b.seq(), "peers", b.peerCount())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found")));
    }

    /** Liveness plus a per-boot id, so clients can tell the server restarted (boards are gone). */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "bootId", BOOT_ID);
    }
}
