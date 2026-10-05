package com.collabcanvas.board;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test transport: records every message a peer was sent. */
final class RecordingSink implements Peer.Sink {

    final List<String> messages = new CopyOnWriteArrayList<>();
    volatile int closedWith = -1;

    @Override
    public void send(String message) {
        messages.add(message);
    }

    @Override
    public void close(int code, String reason) {
        closedWith = code;
    }

    static Peer peer(String userId, RecordingSink sink) {
        String padded = (userId + "00000000").substring(0, 8) + userId;
        return new Peer(padded, "Anonymous Test", "#3b82f6", sink);
    }
}
