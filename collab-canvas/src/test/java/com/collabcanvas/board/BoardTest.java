package com.collabcanvas.board;

import com.collabcanvas.model.Op;
import com.collabcanvas.model.Shape;
import com.collabcanvas.model.ShapePatch;
import com.collabcanvas.model.ShapeType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoardTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Board board;
    private RecordingSink aliceSink;
    private RecordingSink bobSink;
    private Peer alice;
    private Peer bob;

    @BeforeEach
    void setUp() {
        board = new Board("b1", new Wire(mapper), 5, 100);
        aliceSink = new RecordingSink();
        bobSink = new RecordingSink();
        alice = RecordingSink.peer("alice", aliceSink);
        bob = RecordingSink.peer("bob", bobSink);
        board.join(alice);
        board.join(bob);
    }

    static Shape rect(String id, double x) {
        return new Shape(id, ShapeType.RECT, x, 0, 10, 10, null, "#000000", "none", 2, "a0", null, 0);
    }

    static Shape stroke(String id, int coords) {
        return new Shape(id, ShapeType.STROKE, 0, 0, 0, 0, new double[coords], "#000000", "none", 2, "a0", null, 0);
    }

    static ShapePatch moveTo(double x) {
        return new ShapePatch(x, null, null, null, null, null, null, null, null);
    }

    private List<JsonNode> received(Peer p, RecordingSink sink) throws Exception {
        assertTrue(p.awaitDrained(2000));
        Thread.sleep(20);
        return sink.messages.stream().map(m -> {
            try {
                return mapper.readTree(m);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();
    }

    @Test
    void joinSendsSnapshotThenPresence() throws Exception {
        List<JsonNode> msgs = received(bob, bobSink);
        assertEquals("welcome", msgs.get(0).get("t").asText());
        assertEquals(0, msgs.get(0).get("seq").asLong());
        assertEquals(2, msgs.get(0).get("peers").size());
    }

    @Test
    void addIsBroadcastWithServerSetAuthorAndVersion() throws Exception {
        Board.Result r = board.apply(alice, new Op.Add("op-1", rect("s1", 5)));
        assertTrue(r.ok());
        assertEquals(1, r.seq());

        Shape stored = board.snapshot().get(0);
        assertEquals(alice.userId(), stored.author());
        assertEquals(1, stored.version());

        JsonNode committed = received(bob, bobSink).stream()
                .filter(m -> m.get("t").asText().equals("committed")).findFirst().orElseThrow();
        assertEquals("op-1", committed.get("opId").asText());
        assertEquals(alice.userId(), committed.get("op").get("shape").get("author").asText());
    }

    @Test
    void concurrentUpdatesToSameFieldAreLastWriterWinsInSeqOrder() {
        board.apply(alice, new Op.Add("op-1", rect("s1", 0)));
        board.apply(alice, new Op.Update("op-2", "s1", moveTo(100)));
        board.apply(bob, new Op.Update("op-3", "s1", moveTo(200)));
        assertEquals(200, board.snapshot().get(0).x());
        assertEquals(3, board.snapshot().get(0).version());
    }

    @Test
    void updatesToDifferentFieldsBothSurvive() {
        board.apply(alice, new Op.Add("op-1", rect("s1", 0)));
        board.apply(alice, new Op.Update("op-2", "s1", moveTo(100)));
        board.apply(bob, new Op.Update("op-3", "s1",
                new ShapePatch(null, null, null, null, null, "#ff0000", null, null, null)));
        Shape s = board.snapshot().get(0);
        assertEquals(100, s.x());
        assertEquals("#ff0000", s.stroke());
    }

    @Test
    void updateOrDeleteOfDeletedShapeIsRejectedToAuthorOnly() throws Exception {
        board.apply(alice, new Op.Add("op-1", rect("s1", 0)));
        board.apply(bob, new Op.Delete("op-2", "s1"));
        Board.Result move = board.apply(alice, new Op.Update("op-3", "s1", moveTo(9)));
        Board.Result del = board.apply(alice, new Op.Delete("op-4", "s1"));

        assertEquals("shape_deleted", move.reason());
        assertEquals("shape_deleted", del.reason());
        assertEquals(2, board.seq(), "rejected ops don't consume a seq");
        assertTrue(received(alice, aliceSink).stream().anyMatch(m -> m.get("t").asText().equals("rejected")
                && m.get("opId").asText().equals("op-3")));
        assertTrue(received(bob, bobSink).stream().noneMatch(m -> m.get("t").asText().equals("rejected")));
    }

    @Test
    void deletedIdCanBeReAddedForUndo() {
        board.apply(alice, new Op.Add("op-1", rect("s1", 0)));
        board.apply(alice, new Op.Delete("op-2", "s1"));
        assertTrue(board.apply(alice, new Op.Add("op-3", rect("s1", 0))).ok());
        assertEquals(1, board.snapshot().size());
    }

    @Test
    void duplicateLiveIdIsRejected() {
        board.apply(alice, new Op.Add("op-1", rect("s1", 0)));
        assertEquals("duplicate_id", board.apply(bob, new Op.Add("op-2", rect("s1", 0))).reason());
    }

    @Test
    void pointsUpdateOnNonStrokeIsRejected() {
        board.apply(alice, new Op.Add("op-1", rect("s1", 0)));
        Board.Result r = board.apply(alice, new Op.Update("op-2", "s1",
                new ShapePatch(null, null, null, null, new double[] {1, 2}, null, null, null, null)));
        assertEquals("points_mismatch", r.reason());
    }

    @Test
    void shapeAndPointBudgetsAreEnforced() {
        for (int i = 0; i < 5; i++) {
            assertTrue(board.apply(alice, new Op.Add("op-" + i, rect("s" + i, i))).ok());
        }
        assertEquals("board_full", board.apply(alice, new Op.Add("op-x", rect("sx", 0))).reason());

        Board small = new Board("b2", new Wire(mapper), 100, 100);
        small.join(alice);
        assertTrue(small.apply(alice, new Op.Add("p-1", stroke("k1", 60))).ok());
        assertEquals("board_full", small.apply(alice, new Op.Add("p-2", stroke("k2", 60))).reason());
        small.apply(alice, new Op.Delete("p-3", "k1"));
        assertTrue(small.apply(alice, new Op.Add("p-4", stroke("k2", 60))).ok(), "delete frees budget");
    }

    @Test
    void snapshotIsSortedByZThenId() {
        board.apply(alice, new Op.Add("op-1",
                new Shape("b", ShapeType.RECT, 0, 0, 1, 1, null, "#000000", "none", 1, "a5", null, 0)));
        board.apply(alice, new Op.Add("op-2",
                new Shape("a", ShapeType.RECT, 0, 0, 1, 1, null, "#000000", "none", 1, "a5", null, 0)));
        board.apply(alice, new Op.Add("op-3",
                new Shape("c", ShapeType.RECT, 0, 0, 1, 1, null, "#000000", "none", 1, "Z", null, 0)));
        assertEquals(List.of("c", "a", "b"), board.snapshot().stream().map(Shape::id).toList());
    }

    @Test
    void relayGoesToOthersOnly() throws Exception {
        board.relay(alice, "{\"t\":\"cursor\"}");
        assertTrue(received(bob, bobSink).stream().anyMatch(m -> m.get("t").asText().equals("cursor")));
        assertTrue(received(alice, aliceSink).stream().noneMatch(m -> m.get("t").asText().equals("cursor")));
    }

    @Test
    void leaveBroadcastsPresence() throws Exception {
        board.leave(bob);
        JsonNode last = received(alice, aliceSink).getLast();
        assertEquals("presence", last.get("t").asText());
        assertEquals(1, last.get("peers").size());
    }
}
