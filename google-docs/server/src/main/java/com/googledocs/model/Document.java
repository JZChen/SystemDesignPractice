package com.googledocs.model;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Document {
    private final String id;
    private String title;
    private final EngineType engineType;
    private final StringBuilder content;
    private long revision;
    private final List<CommittedOperation> otHistory;
    private final List<CrdtCharacter> crdtCharacters;
    private final Map<String, Session> sessions;
    private final Instant createdAt;
    private Instant updatedAt;
    private final ReentrantReadWriteLock rwLock;

    public Document(String id, String title, EngineType engineType) {
        this.id = id;
        this.title = (title != null && !title.isBlank()) ? title.trim() : "Untitled Document";
        this.engineType = engineType != null ? engineType : EngineType.OT;
        this.content = new StringBuilder();
        this.revision = 0;
        this.otHistory = Collections.synchronizedList(new ArrayList<>());
        this.crdtCharacters = Collections.synchronizedList(new ArrayList<>());
        this.sessions = new ConcurrentHashMap<>();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.rwLock = new ReentrantReadWriteLock();
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        this.updatedAt = Instant.now();
    }

    public EngineType getEngineType() {
        return engineType;
    }

    public StringBuilder getContentBuffer() {
        return content;
    }

    public String getContent() {
        rwLock.readLock().lock();
        try {
            return content.toString();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    public List<CommittedOperation> getOtHistory() {
        return otHistory;
    }

    public List<CrdtCharacter> getCrdtCharacters() {
        return crdtCharacters;
    }

    public Map<String, Session> getSessions() {
        return sessions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void markUpdated() {
        this.updatedAt = Instant.now();
    }

    public ReentrantReadWriteLock getRwLock() {
        return rwLock;
    }

    public Session registerSession(String sessionId) {
        return sessions.computeIfAbsent(sessionId, Session::new);
    }

    public Session getSession(String sessionId) {
        return sessions.get(sessionId);
    }
}
