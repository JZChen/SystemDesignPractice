package com.collabcanvas.ws;

import com.collabcanvas.board.Board;
import com.collabcanvas.board.BoardRegistry;
import com.collabcanvas.board.OpValidator;
import com.collabcanvas.board.OpValidator.InvalidMessage;
import com.collabcanvas.board.Peer;
import com.collabcanvas.board.Wire;
import com.collabcanvas.model.Op;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

/**
 * One WebSocket per (browser tab, board). Client messages:
 * <ul>
 *   <li>{@code {"t":"ops","ops":[...]}}: up to 100 ops, each committed or rejected individually</li>
 *   <li>{@code {"t":"preview",...}} / {@code {"t":"cursor",...}}: relayed to other peers, never stored</li>
 * </ul>
 * Abuse handling: per-connection token buckets; repeated invalid messages close the socket (1008).
 */
@Component
public class BoardSocketHandler extends TextWebSocketHandler {

    static final String ATTR_BOARD = "board";
    static final String ATTR_PEER = "peer";
    static final int MAX_INVALID_MESSAGES = 20;

    private static final Logger log = LoggerFactory.getLogger(BoardSocketHandler.class);

    private final BoardRegistry registry;
    private final Wire wire;

    public BoardSocketHandler(BoardRegistry registry) {
        this.registry = registry;
        this.wire = registry.wire();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        Board board = (Board) session.getAttributes().get(ATTR_BOARD);
        if (board == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        String[] identity = registry.newIdentity();
        Peer peer = new Peer(identity[0], identity[1], identity[2], new SessionSink(session));
        session.getAttributes().put(ATTR_PEER, peer);
        if (!board.join(peer)) {
            peer.close(Peer.CLOSE_TOO_SLOW, "board full");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Board board = (Board) session.getAttributes().get(ATTR_BOARD);
        Peer peer = (Peer) session.getAttributes().get(ATTR_PEER);
        if (board == null || peer == null || peer.isClosed()) {
            return;
        }
        JsonNode root;
        try {
            root = wire.mapper().readTree(message.getPayload());
        } catch (JsonProcessingException e) {
            invalid(peer, "malformed_json");
            return;
        }
        if (root == null || !root.isObject() || !root.path("t").isTextual()) {
            invalid(peer, "invalid_message");
            return;
        }
        try {
            switch (root.get("t").textValue()) {
                case "ops" -> handleOps(board, peer, root);
                case "preview" -> {
                    if (peerAllowsEphemeral(peer)) {
                        board.relay(peer, wire.preview(peer.userId(), OpValidator.parsePreview(root)));
                    }
                }
                case "cursor" -> {
                    if (peerAllowsEphemeral(peer)) {
                        board.relay(peer, wire.cursor(peer.userId(), OpValidator.parseCursor(root)));
                    }
                }
                default -> invalid(peer, "invalid_message");
            }
        } catch (InvalidMessage e) {
            invalid(peer, e.getMessage());
        }
    }

    private void handleOps(Board board, Peer peer, JsonNode root) {
        if (root.size() != 2) {
            throw new InvalidMessage("unknown_field");
        }
        JsonNode ops = root.get("ops");
        int n = OpValidator.checkOpCount(ops);
        boolean allowed = peer.tryAcquireOps(n);
        for (JsonNode node : ops) {
            String opId = node.path("opId").isTextual() ? node.get("opId").textValue() : null;
            if (!allowed) {
                rejectRaw(peer, opId, "rate_limited");
                continue;
            }
            Op op;
            try {
                op = OpValidator.parseOp(node);
            } catch (InvalidMessage e) {
                rejectRaw(peer, opId, e.getMessage());
                peer.countInvalid();
                continue;
            }
            board.apply(peer, op);
        }
        if (peer.invalidCount() > MAX_INVALID_MESSAGES) {
            peer.close(Peer.CLOSE_POLICY, "too many invalid messages");
        }
    }

    /** Rejections echo the opId only if it is well-formed (never reflect arbitrary client strings). */
    private void rejectRaw(Peer peer, String opId, String reason) {
        if (opId != null && opId.matches("[A-Za-z0-9-]{1,48}")) {
            peer.enqueue(wire.rejected(opId, reason));
        } else {
            peer.enqueue(wire.error(reason));
        }
    }

    private boolean peerAllowsEphemeral(Peer peer) {
        return peer.tryAcquireEphemeral(); // excess previews/cursors are silently dropped
    }

    private void invalid(Peer peer, String reason) {
        peer.enqueue(wire.error(reason));
        if (peer.countInvalid() > MAX_INVALID_MESSAGES) {
            peer.close(Peer.CLOSE_POLICY, "too many invalid messages");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("transport error on board socket");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Board board = (Board) session.getAttributes().get(ATTR_BOARD);
        Peer peer = (Peer) session.getAttributes().get(ATTR_PEER);
        if (peer != null) {
            peer.close(status.getCode(), "closed");
            if (board != null) {
                board.leave(peer);
            }
        }
    }

    /** Adapts a Spring WebSocket session; only the peer's sender thread calls {@link #send}. */
    private record SessionSink(WebSocketSession session) implements Peer.Sink {
        @Override
        public void send(String message) throws IOException {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
            }
        }

        @Override
        public void close(int code, String reason) {
            try {
                if (session.isOpen()) {
                    session.close(new CloseStatus(code, reason));
                }
            } catch (IOException | IllegalArgumentException e) {
                // already closing
            }
        }
    }
}
