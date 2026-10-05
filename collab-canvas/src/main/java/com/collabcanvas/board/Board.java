package com.collabcanvas.board;

import com.collabcanvas.model.Op;
import com.collabcanvas.model.Shape;
import com.collabcanvas.model.ShapeType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One whiteboard: the authoritative set of shapes plus a monotonically increasing {@code seq}.
 *
 * <p>All state changes happen under {@link #lock}: validate against current state, apply, bump
 * {@code seq}, and enqueue the {@code committed} message to every peer. Enqueueing is non-blocking
 * (see {@link Peer}), so the critical section is tiny, and because it happens under the lock all
 * peers observe exactly the same order.
 *
 * <p>No operational transformation is needed: ops address shapes by id, so concurrent ops never
 * shift each other. Concurrent updates to the same field resolve as last-writer-wins in seq order;
 * an update/delete of a shape that is already gone is rejected.
 *
 * <p>TODO(persistence): in-memory only; a restart loses every board.
 */
public final class Board {

    public static final int DEFAULT_MAX_SHAPES = 20_000;
    public static final int DEFAULT_MAX_POINT_COORDS = 2_000_000;   // ~16 MB of doubles per board
    public static final int MAX_PEERS = 50;

    /** Outcome of {@link #apply}: {@code reason} is null on success. */
    public record Result(long seq, String reason) {
        public boolean ok() {
            return reason == null;
        }
    }

    private final String id;
    private final Wire wire;
    private final int maxShapes;
    private final int maxPointCoords;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Shape> shapes = new HashMap<>();
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private long seq;
    private long pointCoords;
    private volatile long lastActiveMillis = System.currentTimeMillis();

    public Board(String id, Wire wire) {
        this(id, wire, DEFAULT_MAX_SHAPES, DEFAULT_MAX_POINT_COORDS);
    }

    Board(String id, Wire wire, int maxShapes, int maxPointCoords) {
        this.id = id;
        this.wire = wire;
        this.maxShapes = maxShapes;
        this.maxPointCoords = maxPointCoords;
    }

    public String id() {
        return id;
    }

    public long lastActiveMillis() {
        return lastActiveMillis;
    }

    // ------------------------------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------------------------------

    /** Adds the peer and sends it a snapshot; returns false if the board is full. */
    public boolean join(Peer peer) {
        lock.lock();
        try {
            if (peers.size() >= MAX_PEERS) {
                return false;
            }
            peers.put(peer.userId(), peer);
            touch();
            // Snapshot is enqueued under the lock: every later committed op has a higher seq.
            peer.enqueue(wire.welcome(id, peer, seq, sortedShapes(), peers.values()));
            broadcast(wire.presence(peers.values()), peer);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void leave(Peer peer) {
        lock.lock();
        try {
            if (peers.remove(peer.userId()) != null) {
                touch();
                broadcast(wire.presence(peers.values()), null);
            }
        } finally {
            lock.unlock();
        }
    }

    public int peerCount() {
        lock.lock();
        try {
            return peers.size();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Ops
    // ------------------------------------------------------------------------------------------

    public Result apply(Peer author, Op op) {
        lock.lock();
        try {
            touch();
            Shape result;
            switch (op) {
                case Op.Add a -> {
                    Shape s = a.shape();
                    if (shapes.containsKey(s.id())) {
                        return reject(author, op, "duplicate_id");
                    }
                    if (shapes.size() >= maxShapes) {
                        return reject(author, op, "board_full");
                    }
                    if (pointCoords + s.pointCount() > maxPointCoords) {
                        return reject(author, op, "board_full");
                    }
                    // Re-adding a previously deleted id is allowed: that's how "undo delete" works.
                    result = s.withServerFields(author.userId(), seq + 1);
                    shapes.put(s.id(), result);
                    pointCoords += s.pointCount();
                }
                case Op.Update u -> {
                    Shape cur = shapes.get(u.shapeId());
                    if (cur == null) {
                        return reject(author, op, "shape_deleted");
                    }
                    if (u.set().points() != null && cur.type() != ShapeType.STROKE) {
                        return reject(author, op, "points_mismatch");
                    }
                    int delta = (u.set().points() != null ? u.set().points().length : cur.pointCount())
                            - cur.pointCount();
                    if (pointCoords + delta > maxPointCoords) {
                        return reject(author, op, "board_full");
                    }
                    result = cur.patched(u.set(), seq + 1);
                    shapes.put(cur.id(), result);
                    pointCoords += delta;
                }
                case Op.Delete d -> {
                    Shape cur = shapes.remove(d.shapeId());
                    if (cur == null) {
                        return reject(author, op, "shape_deleted");
                    }
                    pointCoords -= cur.pointCount();
                    result = null;
                }
            }
            long s = ++seq;
            broadcast(wire.committed(s, op, author.userId(), result), null);
            return new Result(s, null);
        } finally {
            lock.unlock();
        }
    }

    /** Ephemeral messages (preview, cursor) go to everyone except the sender and are never stored. */
    public void relay(Peer from, String message) {
        lock.lock();
        try {
            broadcast(message, from);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Read access (snapshots for tests and REST)
    // ------------------------------------------------------------------------------------------

    public long seq() {
        lock.lock();
        try {
            return seq;
        } finally {
            lock.unlock();
        }
    }

    public List<Shape> snapshot() {
        lock.lock();
        try {
            return sortedShapes();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------

    private Result reject(Peer author, Op op, String reason) {
        author.enqueue(wire.rejected(op.opId(), reason));
        return new Result(seq, reason);
    }

    private void broadcast(String message, Peer except) {
        // Copy: a peer whose queue overflows closes itself; its leave() runs later on another thread.
        for (Peer p : new ArrayList<>(peers.values())) {
            if (p != except) {
                p.enqueue(message);
            }
        }
    }

    private List<Shape> sortedShapes() {
        List<Shape> list = new ArrayList<>(shapes.values());
        list.sort(Comparator.comparing(Shape::z).thenComparing(Shape::id));
        return list;
    }

    private void touch() {
        lastActiveMillis = System.currentTimeMillis();
    }
}
