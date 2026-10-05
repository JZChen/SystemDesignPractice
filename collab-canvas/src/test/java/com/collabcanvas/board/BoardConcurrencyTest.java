package com.collabcanvas.board;

import com.collabcanvas.model.Op;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Many writers on one board: seq has no gaps and every peer sees the identical commit order. */
class BoardConcurrencyTest {

    private static final int THREADS = 8;
    private static final int OPS_PER_THREAD = 100;   // 800 committed ops, under the peer queue capacity

    @Test
    void allPeersSeeTheSameContiguousOrder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Board board = new Board("b1", new Wire(mapper));
        List<Peer> peers = new ArrayList<>();
        List<RecordingSink> sinks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            RecordingSink sink = new RecordingSink();
            Peer p = RecordingSink.peer("user" + i, sink);
            sinks.add(sink);
            peers.add(p);
            board.join(p);
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < THREADS; t++) {
            int thread = t;
            pool.submit(() -> {
                start.await();
                Peer me = peers.get(thread);
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    String id = "t" + thread + "-" + i;
                    board.apply(me, new Op.Add("op-" + id, BoardTest.rect(id, i)));
                    if (i % 3 == 0) {
                        board.apply(me, new Op.Update("op-m" + id, id, BoardTest.moveTo(i + 1)));
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        int expectedOps = THREADS * (OPS_PER_THREAD + (OPS_PER_THREAD + 2) / 3);
        assertEquals(expectedOps, board.seq());
        assertEquals(THREADS * OPS_PER_THREAD, board.snapshot().size());

        List<List<Long>> orders = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            assertTrue(peers.get(i).awaitDrained(5000));
            Thread.sleep(20);
            List<Long> seqs = new ArrayList<>();
            List<String> opIds = new ArrayList<>();
            for (String m : sinks.get(i).messages) {
                var n = mapper.readTree(m);
                if (n.get("t").asText().equals("committed")) {
                    seqs.add(n.get("seq").asLong());
                    opIds.add(n.get("opId").asText());
                }
            }
            for (int s = 0; s < seqs.size(); s++) {
                assertEquals(s + 1, seqs.get(s), "contiguous seq for peer " + i);
            }
            assertEquals(expectedOps, seqs.size());
            orders.add(seqs);
            if (i > 0) {
                assertEquals(orders.get(0), seqs);
            }
            assertEquals(-1, sinks.get(i).closedWith, "no peer was dropped");
        }
    }
}
