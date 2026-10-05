package com.collabcanvas.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** All boards in memory, plus anonymous identities for connecting peers. */
@Component
public class BoardRegistry {

    public static final int MAX_BOARDS = 1_000;
    static final long IDLE_EVICT_MILLIS = 24L * 60 * 60 * 1000;

    private static final Pattern BOARD_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final String[] ANIMALS = {
        "Otter", "Falcon", "Panda", "Lynx", "Koala", "Heron", "Badger", "Gecko",
        "Puffin", "Bison", "Marten", "Ibis", "Narwhal", "Quokka", "Tapir", "Wombat"
    };
    private static final String[] COLORS = {
        "#ef4444", "#f97316", "#eab308", "#22c55e", "#14b8a6", "#3b82f6", "#8b5cf6", "#ec4899"
    };

    private final Map<String, Board> boards = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Wire wire;

    public BoardRegistry(ObjectMapper mapper) {
        this.wire = new Wire(mapper);
    }

    public Wire wire() {
        return wire;
    }

    /** @return the new board, or empty if the server already holds {@link #MAX_BOARDS}. */
    public Optional<Board> create() {
        if (boards.size() >= MAX_BOARDS) {
            return Optional.empty();
        }
        String id = UUID.randomUUID().toString(); // 122 random bits from SecureRandom: the link is the capability
        Board b = new Board(id, wire);
        boards.put(id, b);
        return Optional.of(b);
    }

    public Optional<Board> get(String boardId) {
        if (boardId == null || !BOARD_ID.matcher(boardId).matches()) {
            return Optional.empty();
        }
        return Optional.ofNullable(boards.get(boardId));
    }

    public static boolean isValidBoardId(String boardId) {
        return boardId != null && BOARD_ID.matcher(boardId).matches();
    }

    /** Random 128-bit user id plus a friendly name and color. */
    public String[] newIdentity() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        String userId = HexFormat.of().formatHex(b);
        String name = "Anonymous " + ANIMALS[random.nextInt(ANIMALS.length)];
        String color = COLORS[random.nextInt(COLORS.length)];
        return new String[] {userId, name, color};
    }

    /** Frees memory of boards nobody has touched for a day (there is no persistence anyway). */
    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void evictIdle() {
        long cutoff = System.currentTimeMillis() - IDLE_EVICT_MILLIS;
        boards.values().removeIf(b -> b.peerCount() == 0 && b.lastActiveMillis() < cutoff);
    }

    int size() {
        return boards.size();
    }
}
