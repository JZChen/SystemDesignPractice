package com.googledocs.model;

import java.time.Instant;
import java.util.List;
import java.util.Random;

public class Session {
    private final String sessionId;
    private final String displayName;
    private final String color;
    private Instant lastActiveAt;

    private static final List<String> ANIMALS = List.of(
        "Anonymous Penguin", "Anonymous Panda", "Anonymous Falcon", "Anonymous Otter",
        "Anonymous Fox", "Anonymous Koala", "Anonymous Cheetah", "Anonymous Owl",
        "Anonymous Dolphin", "Anonymous Llama", "Anonymous Badger", "Anonymous Tiger"
    );

    private static final List<String> COLORS = List.of(
        "#3b82f6", "#10b981", "#f59e0b", "#ef4444",
        "#8b5cf6", "#ec4899", "#06b6d4", "#14b8a6",
        "#f97316", "#6366f1", "#84cc16", "#d946ef"
    );

    private static final Random RANDOM = new Random();

    public Session(String sessionId) {
        this.sessionId = sessionId;
        this.displayName = ANIMALS.get(RANDOM.nextInt(ANIMALS.size()));
        this.color = COLORS.get(RANDOM.nextInt(COLORS.size()));
        this.lastActiveAt = Instant.now();
    }

    public Session(String sessionId, String displayName, String color) {
        this.sessionId = sessionId;
        this.displayName = displayName;
        this.color = color;
        this.lastActiveAt = Instant.now();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getColor() {
        return color;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void touch() {
        this.lastActiveAt = Instant.now();
    }
}
